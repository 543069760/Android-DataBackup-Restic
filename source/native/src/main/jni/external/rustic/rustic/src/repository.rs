use std::collections::{BTreeMap, HashMap};

use rusqlite::{Connection, params};
use rustic_backend::BackendOptions;
use rustic_core::{
    BackupOptions, CheckOptions, ConfigOptions, Credentials, Excludes, KeyOptions, LimitOption,
    LocalDestination, LsOptions, OpenStatus, PathList, PruneOptions, Repository,
    RepositoryBackends, RepositoryOptions, RestoreOptions, SnapshotOptions,
};

use crate::Result;
use crate::progress::{AndroidProgressBars, RusticProgressCallback};

pub fn init_repository(
    repository_path: &str,
    password: &str,
    options: &HashMap<String, String>,
) -> Result<()> {
    let credentials = Credentials::password(password);

    Repository::new(&RepositoryOptions::default(), &backends(repository_path, options)?)?.init(
        &credentials,
        &KeyOptions::default(),
        &ConfigOptions::default(),
    )?;

    Ok(())
}

pub fn repository_exists(
    repository_path: &str,
    options: &HashMap<String, String>,
) -> Result<bool> {
    let repo = Repository::new(
        &RepositoryOptions::default(),
        &backends(repository_path, options)?,
    )?;

    Ok(repo.config_id()?.is_some())
}

pub fn validate_repository(
    repository_path: &str,
    password: &str,
    options: &HashMap<String, String>,
) -> Result<()> {
    open_repository(repository_path, password, options)?;

    Ok(())
}

pub fn create_snapshot(
    repository_path: &str,
    password: &str,
    source_paths: &[String],
    tags: &[String],
    options: &HashMap<String, String>,
) -> Result<String> {
    create_snapshot_from_repository(
        open_repository(repository_path, password, options)?,
        source_paths,
        tags,
    )
}

pub fn create_snapshot_with_progress<C: RusticProgressCallback>(
    repository_path: &str,
    password: &str,
    source_paths: &[String],
    tags: &[String],
    options: &HashMap<String, String>,
    callback: C,
) -> Result<String> {
    create_snapshot_from_repository(
        open_repository_with_progress(repository_path, password, options, callback)?,
        source_paths,
        tags,
    )
}

fn create_snapshot_from_repository(
    repo: Repository<OpenStatus>,
    source_paths: &[String],
    tags: &[String],
) -> Result<String> {
    let repo = repo.to_indexed_ids()?;
    let source = source_paths
        .iter()
        .map(std::path::PathBuf::from)
        .collect::<PathList>()
        .sanitize()?;
    let snapshot_options = tags
        .iter()
        .try_fold(SnapshotOptions::default(), |options, tag| {
            options.add_tags(tag)
        })?;
    let snapshot = repo.backup(
        &BackupOptions::default(),
        &source,
        snapshot_options.to_snapshot()?,
    )?;

    Ok(snapshot.id.to_string())
}

pub fn restore_snapshot(
    repository_path: &str,
    password: &str,
    snapshot_id: &str,
    destination_path: &str,
    options: &HashMap<String, String>,
) -> Result<()> {
    let repo = open_repository(repository_path, password, options)?.to_indexed()?;
    let node = repo.node_from_snapshot_path(snapshot_id, |_| true)?;
    let ls_options = LsOptions::default();
    let nodes = repo.ls(&node, &ls_options)?;
    let destination = LocalDestination::new(destination_path, true, !node.is_dir())?;
    let restore_options = RestoreOptions::default();
    let restore_plan =
        repo.prepare_restore(&restore_options, nodes.clone(), &destination, false)?;

    repo.restore(restore_plan, &restore_options, nodes, &destination)?;

    Ok(())
}

pub struct RestorePlanStats {
    pub files_total: u64,
    pub bytes_total: u64,
    pub files_skipped: u64,
    pub bytes_skipped: u64,
}

pub fn restore_snapshot_with_progress<C: RusticProgressCallback>(
    repository_path: &str,
    password: &str,
    snapshot_id: &str,
    destination_path: &str,
    options: &HashMap<String, String>,
    include_glob: Option<&str>,
    callback: C,
) -> Result<RestorePlanStats> {
    // 复用 backup 那套 ProgressBars：字节进度自动经 callback.on_progress 回传
    let repo =
        open_repository_with_progress(repository_path, password, options, callback)?
            .to_indexed()?;

    let node = repo.node_from_snapshot_path(snapshot_id, |_| true)?;

    // glob 承载在 LsOptions(=TreeStreamerOptions) 的内嵌 Excludes.globs 上，
    // 且 include 语义要用 restic 风格的 "!" 前缀（纯 pattern 为 exclude）。
    let ls_options = match include_glob {
        Some(glob) => LsOptions::default()
            .excludes(Excludes::default().globs(vec![format!("!{glob}")])),
        None => LsOptions::default(),
    };
    let nodes = repo.ls(&node, &ls_options)?;

    let destination = LocalDestination::new(destination_path, true, !node.is_dir())?;
    let restore_options = RestoreOptions::default();
    let restore_plan =
        repo.prepare_restore(&restore_options, nodes.clone(), &destination, false)?;

    // 从 plan 一次性抽统计（字段名以你 fork 的 RestorePlan/RestoreStats 为准）
    let stats = RestorePlanStats {
        files_total:   restore_plan.stats.files.restore,
        bytes_total:   restore_plan.restore_size,
        files_skipped: restore_plan.stats.files.unchanged,
        bytes_skipped: restore_plan.matched_size,
    };

    repo.restore(restore_plan, &restore_options, nodes, &destination)?;
    Ok(stats)
}

pub fn check_repository(
    repository_path: &str,
    password: &str,
    options: &HashMap<String, String>,
) -> Result<()> {
    let repo = open_repository(repository_path, password, options)?;

    repo.check(CheckOptions::default().trust_cache(true))?;

    Ok(())
}

pub fn forget_snapshot(
    repository_path: &str,
    password: &str,
    options: &HashMap<String, String>,
    snapshot_id: &str,
) -> Result<()> {
    let repo = open_repository(repository_path, password, options)?;
    let snapshot = repo.get_snapshot_from_str(snapshot_id, |_| true)?;
    repo.delete_snapshots(&[snapshot.id])?;

    Ok(())
}

pub fn prune_repository(
    repository_path: &str,
    password: &str,
    options: &HashMap<String, String>,
    max_unused: &str,
) -> Result<()> {
    let repo = open_repository(repository_path, password, options)?;

    let limit: LimitOption = max_unused
        .parse()
        .map_err(|e| format!("invalid max_unused: {e}"))?;
    let prune_options = PruneOptions::default().max_unused(limit);

    let prune_plan = repo.prune_plan(&prune_options)?;
    repo.prune(&prune_options, prune_plan)?;

    Ok(())
}

/// 打开仓库，读取所有快照，直接写入一个 SQLite `.db` 文件。
/// schema（表 + `v_snapshots_full` 视图）逐字对齐 fork 的 `write_snapshots_as_sql`，
/// 供 Android 侧 `SQLiteDatabase.openDatabase` 直接 `rawQuery`。
pub fn list_snapshots_db(
    repository_path: &str,
    password: &str,
    options: &HashMap<String, String>,
    db_path: &str,
) -> Result<()> {
    let repo = open_repository(repository_path, password, options)?;
    let snapshots = repo.get_all_snapshots()?;

    // 若目标已存在先删除，保证幂等
    let _ = std::fs::remove_file(db_path);
    let mut conn = Connection::open(db_path)?;
    conn.execute_batch(SCHEMA_SQL)?;

    let tx = conn.transaction()?;
    {
        let mut insert_snapshot = tx.prepare(
            "INSERT OR IGNORE INTO snapshots \
             (id, time, program_version, parent, tree, hostname, username, uid, gid, \
              original, label, description, delete_condition) \
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
        )?;
        let mut insert_path =
            tx.prepare("INSERT OR IGNORE INTO snapshot_paths (snapshot_id, path) VALUES (?1, ?2)")?;
        let mut insert_tag =
            tx.prepare("INSERT OR IGNORE INTO snapshot_tags (snapshot_id, tag) VALUES (?1, ?2)")?;
        let mut insert_summary = tx.prepare(
            "INSERT OR IGNORE INTO snapshot_summaries (snapshot_id, total_bytes_processed) \
             VALUES (?1, ?2)",
        )?;

        for snap in &snapshots {
            let id = snap.id.to_string();

            insert_snapshot.execute(params![
                id,
                snap.time.to_string(),
                snap.program_version,
                snap.parent.as_ref().map(|p| p.to_string()),
                snap.tree.to_string(),
                snap.hostname,
                snap.username,
                snap.uid,
                snap.gid,
                snap.original.as_ref().map(|o| o.to_string()),
                snap.label,
                snap.description,
                format!("{:?}", snap.delete),
            ])?;

            for path in snap.paths.iter() {
                insert_path.execute(params![id, path])?;
            }
            for tag in snap.tags.iter() {
                insert_tag.execute(params![id, tag])?;
            }
            if let Some(summary) = &snap.summary {
                insert_summary.execute(params![id, summary.total_bytes_processed as i64])?;
            }
        }
    }
    tx.commit()?;

    Ok(())
}

/// schema 常量：4 张表 + `v_snapshots_full` 视图。
const SCHEMA_SQL: &str = r#"
CREATE TABLE IF NOT EXISTS snapshots (
    id TEXT PRIMARY KEY,
    time TEXT NOT NULL,
    program_version TEXT,
    parent TEXT,
    tree TEXT NOT NULL,
    hostname TEXT,
    username TEXT,
    uid INTEGER,
    gid INTEGER,
    original TEXT,
    label TEXT,
    description TEXT,
    delete_condition TEXT
);

CREATE TABLE IF NOT EXISTS snapshot_paths (
    snapshot_id TEXT NOT NULL,
    path TEXT NOT NULL,
    PRIMARY KEY (snapshot_id, path)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS snapshot_tags (
    snapshot_id TEXT NOT NULL,
    tag TEXT NOT NULL,
    PRIMARY KEY (snapshot_id, tag)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS snapshot_summaries (
    snapshot_id TEXT PRIMARY KEY,
    total_bytes_processed INTEGER NOT NULL
);

CREATE VIEW IF NOT EXISTS v_snapshots_full AS
    SELECT
        s.*,
        (SELECT group_concat(path, char(31)) FROM snapshot_paths WHERE snapshot_id = s.id) AS paths_flat,
        (SELECT group_concat(tag, char(31)) FROM snapshot_tags WHERE snapshot_id = s.id) AS tags_flat,
        sum.total_bytes_processed AS total_bytes_processed
    FROM snapshots s
    LEFT JOIN snapshot_summaries sum ON sum.snapshot_id = s.id;
"#;

pub fn get_version() -> Result<String> {
    Ok(env!("CARGO_PKG_VERSION").to_string())
}

fn open_repository(
    repository_path: &str,
    password: &str,
    options: &HashMap<String, String>,
) -> Result<Repository<OpenStatus>> {
    Ok(
        Repository::new(&RepositoryOptions::default(), &backends(repository_path, options)?)?
            .open(&Credentials::password(password))?,
    )
}

fn open_repository_with_progress<C: RusticProgressCallback>(
    repository_path: &str,
    password: &str,
    options: &HashMap<String, String>,
    callback: C,
) -> Result<Repository<OpenStatus>> {
    Ok(Repository::new_with_progress(
        &RepositoryOptions::default(),
        &backends(repository_path, options)?,
        AndroidProgressBars::new(callback),
    )?
    .open(&Credentials::password(password))?)
}

fn backends(
    repository_path: &str,
    options: &HashMap<String, String>,
) -> Result<RepositoryBackends> {
    let options: BTreeMap<String, String> = options
        .iter()
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    Ok(BackendOptions::default()
        .repository(repository_path)
        .options(options)
        .to_backends()?)
}