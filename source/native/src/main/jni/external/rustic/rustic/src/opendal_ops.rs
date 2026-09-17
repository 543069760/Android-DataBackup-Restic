use std::collections::HashMap;
use std::error::Error;
use std::sync::OnceLock;

use opendal::Operator as AsyncOperator;
use opendal::blocking::Operator;
use opendal::layers::RetryLayer;
use opendal::options::ListOptions;
use tokio::runtime::Runtime;

/// 轻量返回结构体,供 jni_bridge.rs 序列化回 Java。
pub struct OpenDalEntry {
    pub name: String,
    pub is_dir: bool,
    pub mtime: i64,
}

/// 复用一个多线程 tokio runtime(与 rustic_core opendal.rs 的做法一致),
/// blocking::Operator::new 需要在 runtime 上下文里构造。
fn runtime() -> &'static Runtime {
    static RUNTIME: OnceLock<Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .unwrap()
    })
}

/// scheme 无关地构造一个阻塞式 Operator。
fn build_operator(
    scheme: &str,
    options: &HashMap<String, String>,
) -> Result<Operator, Box<dyn Error>> {
    let async_op = AsyncOperator::via_iter(scheme, options.clone())?
        .layer(RetryLayer::new().with_max_times(5).with_jitter());
    let _guard = runtime().enter();
    let op = Operator::new(async_op)?;
    Ok(op)
}

/// 非递归列举 path 下一层的条目。
pub fn opendal_list(
    scheme: &str,
    options: &HashMap<String, String>,
    path: &str,
) -> Result<Vec<OpenDalEntry>, Box<dyn Error>> {
    let op = build_operator(scheme, options)?;

    // 归一化为目录前缀(必须以 "/" 结尾;根目录用空串)。
    let dir = if path.is_empty() || path == "/" {
        String::new()
    } else if path.ends_with('/') {
        path.to_string()
    } else {
        format!("{path}/")
    };

    let list_options = ListOptions {
        recursive: false,
        ..Default::default()
    };

    let lister = op.lister_options(&dir, list_options)?;

    let mut result = Vec::new();
    for entry in lister {
        let entry = entry?;
        // 跳过目录自身。
        if entry.path() == dir {
            continue;
        }
        let metadata = entry.metadata();
        let is_dir = metadata.is_dir();
        let name = entry.name().trim_end_matches('/').to_string();
        if name.is_empty() {
            continue;
        }
        let mtime = metadata
            .last_modified()
            .map(|t| t.into_inner().as_second())
            .unwrap_or(0);
        result.push(OpenDalEntry { name, is_dir, mtime });
    }

    Ok(result)
}

/// 创建目录(对象存储即创建以 "/" 结尾的前缀)。
pub fn opendal_create_dir(
    scheme: &str,
    options: &HashMap<String, String>,
    path: &str,
) -> Result<(), Box<dyn Error>> {
    let op = build_operator(scheme, options)?;
    let dir = if path.ends_with('/') {
        path.to_string()
    } else {
        format!("{path}/")
    };
    op.create_dir(&dir)?;
    Ok(())
}