// src/counting_backend.rs
use std::sync::{Arc, Mutex};
use std::time::Instant;

use rustic_core::{FileType, Id, ReadBackend, RusticResult, WriteBackend};
use bytes::Bytes;

use crate::progress::{RusticProgressCallback, ThrottledProgressState};

/// 上传进度累加器：复用 progress.rs 里既有的节流/速率逻辑，
/// 由 write_bytes 真正写出的字节数驱动（去重+压缩+加密之后的字节）。
#[derive(Debug)]
pub(crate) struct UploadProgress {
    callback: Arc<dyn RusticProgressCallback>,
    state: Mutex<ThrottledProgressState>,
}

impl UploadProgress {
    pub(crate) fn new(callback: Arc<dyn RusticProgressCallback>) -> Self {
        Self {
            callback,
            state: Mutex::new(ThrottledProgressState::new(Instant::now())),
        }
    }

    /// 每次 write_bytes 成功后调用，传入本次写出的 buffer 大小。
    fn record(&self, bytes: u64) {
        let now = Instant::now();
        let event = self.state.lock().unwrap().advance(bytes, now, false);
        if let Some(event) = event {
            self.callback
                .on_progress(event.bytes_done, event.speed, event.progress);
        }
    }

    /// backup 结束后调用一次，报告整段平均速率（镜像 AndroidProgress::finish）。
    pub(crate) fn finish(&self) {
        let now = Instant::now();
        let event = self.state.lock().unwrap().advance(0, now, true);
        if let Some(event) = event {
            self.callback
                .on_progress(event.bytes_done, event.speed, event.progress);
        }
    }
}

/// 透明的 WriteBackend 包装：统计真正写出（网络出口）的字节，经 UploadProgress 上报。
#[derive(Debug)]
pub(crate) struct CountingBackend {
    inner: Arc<dyn WriteBackend>,
    progress: Arc<UploadProgress>,
}

impl CountingBackend {
    pub(crate) fn new(inner: Arc<dyn WriteBackend>, progress: Arc<UploadProgress>) -> Self {
        Self { inner, progress }
    }
}

// ---- ReadBackend: 纯透传 ----------------------------------------------------
impl ReadBackend for CountingBackend {
    fn location(&self) -> String {
        self.inner.location()
    }

    fn list_with_size(&self, tpe: FileType) -> RusticResult<Vec<(Id, u32)>> {
        self.inner.list_with_size(tpe)
    }

    fn list(&self, tpe: FileType) -> RusticResult<Vec<Id>> {
        self.inner.list(tpe)
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

// ---- WriteBackend: 透传 + 在 write_bytes 出口统计字节 -----------------------
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
        // 在 buf 被移动进内部 backend 之前先取长度。
        let len = buf.len() as u64;
        self.inner.write_bytes(tpe, id, cacheable, buf)?;
        // 仅统计真正被接受上传的字节（失败/中断不计）。
        self.progress.record(len);
        Ok(())
    }

    fn remove(&self, tpe: FileType, id: &Id, cacheable: bool) -> RusticResult<()> {
        self.inner.remove(tpe, id, cacheable)
    }
}