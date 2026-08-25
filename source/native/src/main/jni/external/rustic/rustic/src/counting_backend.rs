use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use bytes::Bytes;
use rustic_core::{
    FileType, Id, ReadBackend, RepositoryBackends, RusticResult, WriteBackend,
};

/// 包裹真实写后端，在 write_bytes 成功后累加已写出字节数到共享 counter。
/// 用于 rest: 路径（SFTP 经 librclone serve restic），因为该路径不是 opendal
/// 后端、挂不上 ProgressLayer，需要在 rustic→rest 这一层自行计数。
/// 只在写入成功后累加，避免重试/失败导致虚增（与 progress_layer.rs 的
/// ProgressWriter::write 语义一致）。
#[derive(Debug)]
struct CountingBackend {
    inner: Arc<dyn WriteBackend>,
    counter: Arc<AtomicU64>,
}

impl CountingBackend {
    fn new(inner: Arc<dyn WriteBackend>, counter: Arc<AtomicU64>) -> Self {
        Self { inner, counter }
    }
}

impl ReadBackend for CountingBackend {
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

impl WriteBackend for CountingBackend {
    fn create(&self) -> RusticResult<()> {
        self.inner.create()
    }

    fn write_bytes(
        &self,
        tpe: FileType,
        id: &Id,
        cacheable: bool,
        buf: Bytes,
    ) -> RusticResult<()> {
        // 先取长度，buf 随后被移动进 inner.write_bytes。
        let len = buf.len() as u64;
        self.inner.write_bytes(tpe, id, cacheable, buf)?;
        // 只在成功后累加，避免重试/失败虚增。
        let _ = self.counter.fetch_add(len, Ordering::Relaxed);
        Ok(())
    }

    fn remove(&self, tpe: FileType, id: &Id, cacheable: bool) -> RusticResult<()> {
        self.inner.remove(tpe, id, cacheable)
    }
}

/// 用 CountingBackend 包裹主写后端与 hot 写后端，把 counter 注入写入路径。
pub fn wrap_write_backends_with_counter(
    backends: RepositoryBackends,
    counter: Arc<AtomicU64>,
) -> RepositoryBackends {
    let repository: Arc<dyn WriteBackend> =
        Arc::new(CountingBackend::new(backends.repository(), counter.clone()));

    let repo_hot: Option<Arc<dyn WriteBackend>> = backends
        .repo_hot()
        .map(|be| Arc::new(CountingBackend::new(be, counter.clone())) as Arc<dyn WriteBackend>);

    log::info!(
        "[RusticProgress] wrap_write_backends_with_counter applied (hot={})",
        repo_hot.is_some()
    );
    RepositoryBackends::new(repository, repo_hot)
}