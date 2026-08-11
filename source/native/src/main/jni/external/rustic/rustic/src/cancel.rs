use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, LazyLock, Mutex};

static REGISTRY: LazyLock<Mutex<HashMap<i64, Arc<AtomicBool>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

/// cancel_id == 0 表示不可取消，返回未注册的独立令牌。
pub fn register(cancel_id: i64) -> Arc<AtomicBool> {
    let flag = Arc::new(AtomicBool::new(false));
    if cancel_id != 0 {
        REGISTRY.lock().unwrap().insert(cancel_id, flag.clone());
    }
    log::info!("[RusticCancel] register id={cancel_id}");
    flag
}

pub fn signal(cancel_id: i64) {
    let found = if let Some(flag) = REGISTRY.lock().unwrap().get(&cancel_id) {
        flag.store(true, Ordering::SeqCst);
        true
    } else {
        false
    };
    log::info!("[RusticCancel] signal id={cancel_id}, found={found}");
}

pub fn unregister(cancel_id: i64) {
    if cancel_id != 0 {
        REGISTRY.lock().unwrap().remove(&cancel_id);
    }
    log::info!("[RusticCancel] unregister id={cancel_id}");
}