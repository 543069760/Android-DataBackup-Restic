use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use bytes::Bytes;
use rustic_core::{
    ErrorKind, FileType, Id, ReadBackend, RepositoryBackends, RusticError, RusticResult,
    WriteBackend,
};

/// 包裹真实后端，在写入路径入口检查取消标志。
/// 一旦 flag 被翻成 true，下一次 create / write_bytes / remove 立即返回含
/// "cancelled" 的 Err，使 repo.backup() 在 packer finalize/join 时冒泡中止、不落快照。
#[derive(Debug)]
struct CancellableBackend {
    inner: Arc<dyn WriteBackend>,
    flag: Arc<AtomicBool>,
}

impl CancellableBackend {
    fn new(inner: Arc<dyn WriteBackend>, flag: Arc<AtomicBool>) -> Self {
        Self { inner, flag }
    }

    #[inline]
    fn check_cancel(&self) -> RusticResult<()> {
        if self.flag.load(Ordering::SeqCst) {
            log::info!(
                "[RusticCancel] backend abort write, msg=The backup was cancelled by the user."
            );
            return Err(RusticError::new(
                ErrorKind::Backend,
                "The backup was cancelled by the user.",
            ));
        }
        Ok(())
    }
}

impl ReadBackend for CancellableBackend {
    fn location(&self) -> String {
        self.inner.location()
    }

    fn list(&self, tpe: FileType) -> RusticResult<Vec<Id>> {
        self.inner.list(tpe)
    }

    fn list_with_size(&self, tpe: FileType) -> RusticResult<Vec<(Id, u32)>> {
        self.inner.list_with_size(tpe)
    }

    fn read_full(&self, tpe: FileType, id: &Id) -> RusticResult<Bytes> {
        self.inner.read_full(tpe, id)
    }

    fn read_partial(
        &self,
        tpe: FileType,
        id: &Id,
        cacheable: bool,
        offset: u32,
        length: u32,
    ) -> RusticResult<Bytes> {
        self.inner.read_partial(tpe, id, cacheable, offset, length)
    }

    fn warmup_path(&self, tpe: FileType, id: &Id) -> String {
        self.inner.warmup_path(tpe, id)
    }

    fn needs_warm_up(&self) -> bool {
        self.inner.needs_warm_up()
    }

    fn warm_up(&self, tpe: FileType, id: &Id) -> RusticResult<()> {
        self.inner.warm_up(tpe, id)
    }
}

impl WriteBackend for CancellableBackend {
    fn create(&self) -> RusticResult<()> {
        self.check_cancel()?;
        self.inner.create()
    }

    fn write_bytes(
        &self,
        tpe: FileType,
        id: &Id,
        cacheable: bool,
        buf: Bytes,
    ) -> RusticResult<()> {
        self.check_cancel()?;
        self.inner.write_bytes(tpe, id, cacheable, buf)
    }

    fn remove(&self, tpe: FileType, id: &Id, cacheable: bool) -> RusticResult<()> {
        self.check_cancel()?;
        self.inner.remove(tpe, id, cacheable)
    }
}

/// 用 CancellableBackend 包裹主写后端与 hot 写后端。
/// cancel_id == 0（不可取消）时 flag 恒为 false，直接返回原 backends 以零开销。
pub fn wrap_write_backends(backends: RepositoryBackends, flag: Arc<AtomicBool>) -> RepositoryBackends {
    let repository: Arc<dyn WriteBackend> =
        Arc::new(CancellableBackend::new(backends.repository(), flag.clone()));

    let repo_hot: Option<Arc<dyn WriteBackend>> = backends
        .repo_hot()
        .map(|be| Arc::new(CancellableBackend::new(be, flag.clone())) as Arc<dyn WriteBackend>);

    log::info!("[RusticCancel] wrap_write_backends applied (hot={})", repo_hot.is_some());
    RepositoryBackends::new(repository, repo_hot)
}