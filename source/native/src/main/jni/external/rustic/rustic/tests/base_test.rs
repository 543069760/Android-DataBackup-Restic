use std::collections::HashMap;
use std::error::Error;
use std::fs;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use rustic::RusticProgressCallback;

fn temp_path(name: &str) -> Result<std::path::PathBuf, Box<dyn Error>> {
    Ok(std::env::temp_dir().join(format!(
        "rustic-{name}-{}",
        SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos()
    )))
}

#[test]
fn detects_and_validates_repository() -> Result<(), Box<dyn Error>> {
    let root = temp_path("detect-repository")?;
    let repository = root.join("repo");
    let repository_path = repository.to_str().unwrap();
    let password = "password";

    assert!(!rustic::repository_exists(repository_path, &HashMap::new())?);
    fs::create_dir_all(&repository)?;
    fs::write(repository.join("unrelated"), b"data")?;
    assert!(!rustic::repository_exists(repository_path, &HashMap::new())?);

    fs::remove_dir_all(&repository)?;
    rustic::init_repository(repository_path, password, &HashMap::new())?;
    assert!(rustic::repository_exists(repository_path, &HashMap::new())?);
    rustic::validate_repository(repository_path, password, &HashMap::new())?;
    assert!(rustic::validate_repository(repository_path, "incorrect", &HashMap::new()).is_err());

    fs::remove_dir_all(root)?;
    Ok(())
}

#[test]
fn create_restore_and_check_snapshot_lifecycle() -> Result<(), Box<dyn Error>> {
    run_snapshot_lifecycle(
        "snapshot-lifecycle",
        "note.txt",
        b"Hello from rustic",
        |repository, password, source_paths, tags| {
            rustic::create_snapshot(
                repository.to_str().unwrap(),
                password,
                source_paths,
                tags,
                &HashMap::new(),
            )
        },
    )
}

#[derive(Debug)]
struct RecordingProgress {
    events: Arc<Mutex<Vec<(u64, u64, f32, u64, u64)>>>,
}

impl RusticProgressCallback for RecordingProgress {
    fn on_progress(
        &self,
        read_bytes: u64,
        read_total: u64,
        read_progress: f32,
        written_bytes: u64,
        written_speed: u64,
    ) {
        println!(
            "progress: read_bytes={read_bytes}, read_total={read_total}, \
             read_progress={read_progress}, written_bytes={written_bytes}, \
             written_speed={written_speed}"
        );
        self.events.lock().unwrap().push((
            read_bytes,
            read_total,
            read_progress,
            written_bytes,
            written_speed,
        ));
    }
}

#[test]
fn create_restore_and_check_snapshot_lifecycle_with_progress() -> Result<(), Box<dyn Error>> {
    let content = vec![b'x'; 1024 * 1024];
    let events = Arc::new(Mutex::new(Vec::new()));

    run_snapshot_lifecycle(
        "snapshot-lifecycle-progress",
        "payload.bin",
        &content,
        |repository, password, source_paths, tags| {
            rustic::create_snapshot_with_progress(
                repository.to_str().unwrap(),
                password,
                source_paths,
                tags,
                &HashMap::new(),
                RecordingProgress {
                    events: events.clone(),
                },
            )
        },
    )?;

    let events = events.lock().unwrap();
        assert!(!events.is_empty());
        assert!(
            events
                .iter()
                .all(|(read_bytes, _read_total, read_progress, _written_bytes, _written_speed)| {
                    *read_bytes > 0 && *read_progress >= 0.0 && *read_progress <= 1.0
                })
        );
        // 读取字节单调不减（元组第 0 位）
        assert!(events.windows(2).all(|w| w[0].0 <= w[1].0));
        // 写出字节单调不减（元组第 3 位）
        assert!(events.windows(2).all(|w| w[0].3 <= w[1].3));
        println!("progress events: {}", events.len());

    Ok(())
}

#[test]
fn create_and_restore_snapshot_with_multiple_direct_sources() -> Result<(), Box<dyn Error>> {
    let root = temp_path("multi-source-snapshot")?;
    let repository = root.join("repo");
    let app = root.join("app");
    let files = root.join("files");
    let staging = root.join("staging");
    let restore = root.join("restore");
    let password = "password";

    fs::create_dir_all(&app)?;
    fs::create_dir_all(&files)?;
    fs::create_dir_all(&staging)?;
    fs::write(app.join("app-data.txt"), b"app")?;
    fs::write(files.join("user-file.txt"), b"file")?;
    fs::write(staging.join("manifest.json"), b"manifest")?;

    rustic::init_repository(repository.to_str().unwrap(), password, &HashMap::new())?;
    let source_paths = [app, files, staging].map(|path| path.to_string_lossy().into_owned());
    let snapshot_id = rustic::create_snapshot(
        repository.to_str().unwrap(),
        password,
        &source_paths,
        &["databackup".to_string()],
        &HashMap::new(),
    )?;

    assert!(!snapshot_id.is_empty());
    rustic::restore_snapshot(
        repository.to_str().unwrap(),
        password,
        &snapshot_id,
        restore.to_str().unwrap(),
        &HashMap::new(),
    )?;
    rustic::check_repository(repository.to_str().unwrap(), password, &HashMap::new())?;
    assert_eq!(fs::read(find_file(&restore, "app-data.txt")?)?, b"app");
    assert_eq!(fs::read(find_file(&restore, "user-file.txt")?)?, b"file");
    assert_eq!(
        fs::read(find_file(&restore, "manifest.json")?)?,
        b"manifest"
    );

    fs::remove_dir_all(root)?;
    Ok(())
}

#[test]
fn forget_removes_snapshot() -> Result<(), Box<dyn Error>> {
    let root = temp_path("forget-snapshot")?;
    let repository = root.join("repo");
    let source = root.join("source");
    let repository_path = repository.to_str().unwrap();
    let password = "password";

    fs::create_dir_all(&source)?;
    fs::write(source.join("file.txt"), b"data")?;
    let source_paths = [source.to_string_lossy().into_owned()];
    let tags = ["databackup".to_string()];

    rustic::init_repository(repository_path, password, &HashMap::new())?;
    let snapshot_id = rustic::create_snapshot(
        repository_path,
        password,
        &source_paths,
        &tags,
        &HashMap::new(),
    )?;
    assert!(!snapshot_id.is_empty());

    rustic::forget_snapshot(repository_path, password, &HashMap::new(), &snapshot_id)?;
    rustic::check_repository(repository_path, password, &HashMap::new())?;

    fs::remove_dir_all(root)?;
    Ok(())
}

#[test]
fn prune_reduces_or_keeps_repository_usable() -> Result<(), Box<dyn Error>> {
    let root = temp_path("prune-repository")?;
    let repository = root.join("repo");
    let source = root.join("source");
    let repository_path = repository.to_str().unwrap();
    let password = "password";

    fs::create_dir_all(&source)?;
    fs::write(source.join("file.txt"), vec![b'x'; 1024 * 512])?;
    let source_paths = [source.to_string_lossy().into_owned()];
    let tags = ["databackup".to_string()];

    rustic::init_repository(repository_path, password, &HashMap::new())?;
    let snapshot_id = rustic::create_snapshot(
        repository_path,
        password,
        &source_paths,
        &tags,
        &HashMap::new(),
    )?;
    assert!(!snapshot_id.is_empty());

    rustic::forget_snapshot(repository_path, password, &HashMap::new(), &snapshot_id)?;
    rustic::prune_repository(repository_path, password, &HashMap::new(), "10%")?;
    rustic::check_repository(repository_path, password, &HashMap::new())?;

    fs::remove_dir_all(root)?;
    Ok(())
}

#[test]
fn writes_snapshots_db() -> Result<(), Box<dyn Error>> {
    let root = temp_path("snapshots-db")?;
    let repository = root.join("repo");
    let source = root.join("source");
    let db_path = root.join("snapshots.db");
    let repository_path = repository.to_str().unwrap();
    let password = "password";

    fs::create_dir_all(&source)?;
    fs::write(source.join("file.txt"), b"data")?;
    let source_paths = [source.to_string_lossy().into_owned()];
    let tags = ["databackup".to_string()];

    rustic::init_repository(repository_path, password, &HashMap::new())?;
    let snapshot_id = rustic::create_snapshot(
        repository_path,
        password,
        &source_paths,
        &tags,
        &HashMap::new(),
    )?;
    assert!(!snapshot_id.is_empty());

    rustic::list_snapshots_db(
        repository_path,
        password,
        &HashMap::new(),
        db_path.to_str().unwrap(),
    )?;

    {
        let conn = rusqlite::Connection::open(&db_path)?;
        let mut stmt = conn.prepare(
            "SELECT id, tags_flat FROM v_snapshots_full WHERE tags_flat IS NOT NULL",
        )?;
        let (id, tags_flat): (String, String) =
            stmt.query_row([], |row| Ok((row.get(0)?, row.get(1)?)))?;
        assert_eq!(id, snapshot_id);
        assert!(tags_flat.split('\u{1f}').any(|t| t == "databackup"));
    } // conn 在这里 drop，句柄释放

    fs::remove_dir_all(root)?;
    Ok(())
}

fn run_snapshot_lifecycle(
    temp_name: &str,
    file_name: &str,
    content: &[u8],
    create_snapshot: impl FnOnce(&Path, &str, &[String], &[String]) -> Result<String, Box<dyn Error>>,
) -> Result<(), Box<dyn Error>> {
    let root = temp_path(temp_name)?;
    let repository = root.join("repo");
    let source = root.join("source");
    let restore = root.join("restore");
    let password = "password";
    let source_paths = [source.to_string_lossy().into_owned()];
    let tags = ["instrumented".to_string()];

    fs::create_dir_all(source.join("nested"))?;
    fs::write(source.join("nested").join(file_name), content)?;

    rustic::init_repository(repository.to_str().unwrap(), password, &HashMap::new())?;
    let snapshot_id = create_snapshot(&repository, password, &source_paths, &tags)?;

    assert!(!snapshot_id.is_empty());

    rustic::restore_snapshot(
        repository.to_str().unwrap(),
        password,
        &snapshot_id,
        restore.to_str().unwrap(),
        &HashMap::new(),
    )?;
    rustic::check_repository(repository.to_str().unwrap(), password, &HashMap::new())?;

    let restored = find_file(&restore, file_name)?;
    assert_eq!(fs::read(restored)?, content);

    fs::remove_dir_all(root)?;
    Ok(())
}

fn find_file(root: &Path, name: &str) -> Result<std::path::PathBuf, Box<dyn Error>> {
    for entry in fs::read_dir(root)? {
        let path = entry?.path();
        if path.is_dir() {
            if let Ok(found) = find_file(&path, name) {
                return Ok(found);
            }
        } else if path.file_name().is_some_and(|file_name| file_name == name) {
            return Ok(path);
        }
    }

    Err(format!("missing restored file {name}").into())
}