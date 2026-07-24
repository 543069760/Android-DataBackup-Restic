#![deny(improper_ctypes_definitions)]

use std::error::Error;

mod error;
mod jni_bridge;
mod jni_progress;
mod progress;
mod repository;

pub type Result<T> = std::result::Result<T, Box<dyn Error>>;

pub use progress::RusticProgressCallback;
pub use repository::{
    check_repository, create_snapshot, create_snapshot_with_progress, forget_snapshot,
    get_version, init_repository, list_snapshots_db, prune_repository, repository_exists,
    restore_snapshot, validate_repository,
};