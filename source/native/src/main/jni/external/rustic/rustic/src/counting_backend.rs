use std::sync::Arc;

use bytes::Bytes;
// backend 模块在 rustic_core 里是 pub(crate)，外部 crate 不能走 rustic_core::backend::...；
// FileType / ReadBackend / WriteBackend 在 crate 根部有 re-export，走根路径两种可见性都安全。
use rustic_core::{FileType, Id, ReadBackend, RusticResult, WriteBackend};

use crate::progress::SharedProgress;

/// 包装真正的 WriteBackend，在 write_bytes 出口统计去重+压缩+加密后
/// 真正写出的字节，汇聚到 SharedProgress 的写出侧。
///
/// SharedProgress: Clone 且内部为 Arc<Mutex<...>>，因此这里按值持有 SharedProgress，
/// 克隆后仍共享同一份写出计数（与 AndroidProgressBars 读取侧汇聚到同一状态）。
#[derive(Debug, Clone)]
pub(crate) struct CountingBackend {
    inner: Arc<dyn WriteBackend>,
    shared: Arc<SharedProgress>,
}

impl CountingBackend {
    pub(crate) fn new(inner: Arc<dyn WriteBackend>, shared: Arc<SharedProgress>) -> Self {
        Self { inner, shared }
    }
}

impl ReadBackend for CountingBackend {
    fn location(&self) -> String {
        self.inner.location()
    }

    fn list_with_size(&self, tpe: FileType) -> RusticResult<Vec<(Id, u32)>> {
        self.inner.list_with_size(tpe)
    }

    fn read_full(&self, tpe: FileType, id: &Id) -> RusticResult<Bytes> {
        self.inner.read_full(tpe, id)
    }

    fn warmup_path(&self, tpe: FileType, id: &Id) -> String {
        self.inner.warmup_path(tpe, id)
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
        // 先取长度（buf 之后被按值转发，move 走）
        let len = buf.len() as u64;
        let result = self.inner.write_bytes(tpe, id, cacheable, buf);
        // 只统计真正写成功的字节，失败不计入
        if result.is_ok() {
            self.shared.add_written(len);
        }
        result
    }

    fn remove(&self, tpe: FileType, id: &Id, cacheable: bool) -> RusticResult<()> {
        self.inner.remove(tpe, id, cacheable)
    }
}