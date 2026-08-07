// src/progress.rs
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use rustic_core::{Progress, ProgressBars, ProgressType, RusticProgress};

pub(crate) const PROGRESS_CALLBACK_INTERVAL: Duration = Duration::from_secs(1);
// 消除首秒分母过小导致的虚高瞬时速度
const MIN_SPEED_ELAPSED_SECS: f64 = 0.5;

// Repository code depends on this trait, not on any JNI-specific callback type.
// read_* 来自 rustic_core 读取侧；written_* 来自 CountingBackend 写出侧。
pub trait RusticProgressCallback: Send + Sync + 'static + std::fmt::Debug {
    fn on_progress(
        &self,
        read_bytes: u64,
        read_total: u64,
        read_progress: f32,
        written_bytes: u64,
        written_speed: u64,
    );
}

/// 读取侧与写出侧共享的进度状态。AndroidProgress 与 CountingBackend
/// 各持有同一个 Arc<SharedProgress>，任一侧触发都读取“当前全量快照”再 emit，
/// 避免相互覆盖为 0。节流在这一层统一做。
#[derive(Debug)]
pub(crate) struct SharedProgress {
    state: Mutex<SharedState>,
    callback: Arc<dyn RusticProgressCallback>,
}

#[derive(Debug)]
struct SharedState {
    read_bytes: u64,
    read_total: Option<u64>,
    written_bytes: u64,
    started_at: Instant,
    last_emit_at: Option<Instant>,
    last_emit_written: u64,
    finished: bool,
}

#[derive(Debug, Clone, Copy)]
struct ProgressSnapshot {
    read_bytes: u64,
    read_total: u64,
    read_progress: f32,
    written_bytes: u64,
    written_speed: u64,
}

impl SharedProgress {
    pub(crate) fn new(callback: Arc<dyn RusticProgressCallback>) -> Self {
        Self {
            state: Mutex::new(SharedState {
                read_bytes: 0,
                read_total: None,
                written_bytes: 0,
                started_at: Instant::now(),
                last_emit_at: None,
                last_emit_written: 0,
                finished: false,
            }),
            callback,
        }
    }

    pub(crate) fn set_read_total(&self, len: u64) {
        self.state.lock().unwrap().read_total = (len > 0).then_some(len);
    }

    pub(crate) fn add_read(&self, inc: u64) {
        let snapshot = {
            let mut s = self.state.lock().unwrap();
            s.read_bytes = s.read_bytes.saturating_add(inc);
            s.maybe_snapshot(Instant::now(), false)
        };
        if let Some(snapshot) = snapshot {
            self.emit(snapshot);
        }
    }

    /// ProgressLayer 的 WrittenCounter 是单调累计的绝对量（非 delta）。
    /// 轮询线程读到的 total 直接覆盖写出量（取 max 防回退），再走统一节流 emit。
    pub(crate) fn set_written_absolute(&self, total: u64) {
        let snapshot = {
            let mut s = self.state.lock().unwrap();
            s.written_bytes = total.max(s.written_bytes);
            s.maybe_snapshot(Instant::now(), false)
        };
        if let Some(snapshot) = snapshot {
            self.emit(snapshot);
        }
    }

    pub(crate) fn finish(&self) {
        let snapshot = {
            let mut s = self.state.lock().unwrap();
            s.maybe_snapshot(Instant::now(), true)
        };
        if let Some(snapshot) = snapshot {
            self.emit(snapshot);
        }
    }

    fn emit(&self, snapshot: ProgressSnapshot) {
        self.callback.on_progress(
            snapshot.read_bytes,
            snapshot.read_total,
            snapshot.read_progress,
            snapshot.written_bytes,
            snapshot.written_speed,
        );
    }
}

impl SharedState {
    fn maybe_snapshot(&mut self, now: Instant, finish: bool) -> Option<ProgressSnapshot> {
        if self.finished {
            return None;
        }
        if finish {
            self.finished = true;
        }

        // 1 秒节流，保持 Java 回调粗粒度；finish 时强制 emit 收尾。
        let should_emit = finish
            || self.last_emit_at.is_none_or(|last| {
                now.duration_since(last) >= PROGRESS_CALLBACK_INTERVAL
            });
        if !should_emit {
            return None;
        }

        let written_speed = if finish {
            // 结束时报告整段平均写出速率
            bytes_per_second(self.written_bytes, now.duration_since(self.started_at))
        } else {
            let bytes_since = self.written_bytes.saturating_sub(self.last_emit_written);
            let since = self
                .last_emit_at
                .map_or_else(|| now.duration_since(self.started_at), |last| now.duration_since(last));
            bytes_per_second(bytes_since, since)
        };

        self.last_emit_at = Some(now);
        self.last_emit_written = self.written_bytes;

        Some(ProgressSnapshot {
            read_bytes: self.read_bytes,
            read_total: self.read_total.unwrap_or(0),
            read_progress: self.read_progress(),
            written_bytes: self.written_bytes,
            written_speed,
        })
    }

    fn read_progress(&self) -> f32 {
        self.read_total
            .map(|total| (self.read_bytes as f32 / total as f32).clamp(0.0, 1.0))
            .unwrap_or(0.0)
    }
}

#[derive(Debug)]
pub(crate) struct AndroidProgressBars {
    shared: Arc<SharedProgress>,
}

impl AndroidProgressBars {
    pub(crate) fn new(shared: Arc<SharedProgress>) -> Self {
        Self { shared }
    }
}

impl ProgressBars for AndroidProgressBars {
    fn progress(&self, progress_type: ProgressType, _prefix: &str) -> Progress {
        match progress_type {
            // rustic_core 的字节读取进度走这里，汇聚到共享读取计数。
            ProgressType::Bytes => Progress::new(AndroidProgress::new(self.shared.clone())),
            ProgressType::Spinner | ProgressType::Counter => Progress::hidden(),
        }
    }
}

#[derive(Debug)]
struct AndroidProgress {
    shared: Arc<SharedProgress>,
}

impl AndroidProgress {
    fn new(shared: Arc<SharedProgress>) -> Self {
        Self { shared }
    }
}

impl RusticProgress for AndroidProgress {
    fn is_hidden(&self) -> bool {
        false
    }

    fn set_length(&self, len: u64) {
        self.shared.set_read_total(len);
    }

    fn set_title(&self, _title: &str) {}

    fn inc(&self, inc: u64) {
        self.shared.add_read(inc);
    }

    fn finish(&self) {
        self.shared.finish();
    }
}

fn bytes_per_second(bytes: u64, elapsed: Duration) -> u64 {
    let seconds = elapsed.as_secs_f64().max(MIN_SPEED_ELAPSED_SECS);
    (bytes as f64 / seconds).round() as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Debug)]
    struct Recorder {
        events: Mutex<Vec<(u64, u64, f32, u64, u64)>>,
    }
    impl RusticProgressCallback for Recorder {
        fn on_progress(&self, rb: u64, rt: u64, rp: f32, wb: u64, ws: u64) {
            self.events.lock().unwrap().push((rb, rt, rp, wb, ws));
        }
    }

    #[test]
    fn read_progress_uses_total_as_denominator() {
        let shared = SharedProgress::new(Arc::new(Recorder { events: Mutex::new(vec![]) }));
        shared.set_read_total(4096);
        // 第一次 add_read 无 last_emit_at，立即 emit
        shared.add_read(1024);
        // finish 收尾
        shared.finish();
    }

    #[test]
    fn written_bytes_accumulate() {
        let shared = SharedProgress::new(Arc::new(Recorder { events: Mutex::new(vec![]) }));
        shared.add_written(2048);
        shared.finish();
    }  
}