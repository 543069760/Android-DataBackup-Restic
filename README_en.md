<div align="center">      

<span style="font-weight: bold"> <a href="./README_en.md"> English </a> | <a href="./README.md"> 中文 </a> </span>      

<img src="./fastlane/metadata/android/en-US/images/icon.png" alt="logo" width="128px" />      

<h1 align="center">DataBackup Revived</h1>      

**A more user-friendly and powerful Android backup tool.** Backups are automatically encrypted for enhanced security, fully switched to rustic (restic), natively **support incremental backups**, making backups faster and more time-efficient (**for example, incremental backups for WeChat do not require full re-backups, saving time and speeding up the process**), while also saving 60–90% of storage space.

[![GitHub release](https://img.shields.io/github/v/release/543069760/Android-DataBackup-Restic?color=orange)](https://github.com/543069760/Android-DataBackup-S3/releases)
[![License](https://img.shields.io/github/license/543069760/Android-DataBackup-Restic?color=ff69b4)](./LICENSE)
[![Download](https://img.shields.io/github/downloads/543069760/Android-DataBackup-S3/total)](https://github.com/543069760/Android-DataBackup-S3/releases)

</div>

> ℹ️ **No external Restic binary required.** Local backup/restore is powered by the Rust `rustic_core` library (a Rust implementation compatible with the restic repository format), compiled into `librustic.so`, shipped inside the App, and invoked directly over JNI. There is **no longer any need to download, install, or manage a separate Restic Android CGO binary** — the previous DNS/dynamic-linking limitations of the upstream binary no longer apply. See [Native JNI Migration](#native-jni-migration-rustic-jni-branch) for details.

## Features

* :deciduous_tree: **Requires root access. Supports [Magisk](https://github.com/topjohnwu/Magisk), [KernelSU](https://github.com/tiann/KernelSU), and [APatch](https://github.com/bmax121/APatch)**
* :cyclone: **Multi-user support**
* :cloud: **Supports multiple cloud storage protocols**
* :sunglasses: **100% data integrity guarantee**
* :zap: **Fast**
* :sunny: **Simple and easy to use**
* :sparkles: **Multi-version backup support**
* :rose: **Block-level deduplication for local backups via the built-in `rustic_core` (JNI, restic-compatible repositories)**
* :electric_plug: **No external binary — `rustic_core` is compiled into `librustic.so` and called over JNI**
* :rocket: **libsu integration for enhanced root operations**

## Version Comparison

### Cloud Storage Protocol Support

| Feature | Legacy (DataBackup) | New (DataBackup Revived 3.0.0)                                    |      
|---------|------------------|-------------------------------------------------------------------|      
| **Local Storage** | ✅ Supported | ✅ **Block-level deduplication (JNI rustic, done)**                |    
| **Tencent COS Protocol** | ❌ Not supported | ✅ **Block-level deduplication (JNI rustic, done)**                |      
| **FTP Protocol** | ✅ Supported | ✅ **Block-level deduplication (JNI rustic over librclone, done)** |      
| **SFTP Protocol** | ✅ Supported | ✅ **Block-level deduplication (JNI rustic over librclone, done)**                                        |      
| **WebDAV Protocol** | ✅ Supported | ✅ **Block-level deduplication (JNI rustic, done)**                                        |      
| **SMB/CIFS Protocol** | ✅ Multi-version support | ❌ Opendal is not supported and will be removed in the future.     |      

> Currently, both the JNI `rustic_core` **local storage** and **remote protocol** have been migrated to JNI.
> Tencent COS: This is an object storage service compatible with the S3 protocol. Please note: There may be parameter differences between S3 services (e.g., AWS S3, Tencent Cloud COS, Alibaba Cloud OSS). Currently, only Tencent Cloud COS is supported; other S3 object storage services are not yet supported but are being gradually adapted. Stay tuned.

### Backup Architecture Evolution

| Feature | Legacy | New (Completed) |    
|---------|-------|-------|    
| **Local Backup Engine** | tar+zstd compression only | **rustic block-level deduplication (JNI, no binary)** |    
| **Root Access** | Custom implementation | **libsu integration** |    
| **Storage Efficiency** | Linear growth | **60–90% space savings** |    
| **Data Encryption** | None | **AES-256 encryption** |    
| **Incremental Backup** | Not supported | **Native support** |    
| **Version Management** | File overwrite | **Snapshot-based versioning** |    
| **APK Backup (Local)** | Compression only | **rustic deduplication** |    
| **App Data Backups (Local)** | Compression only | **rustic deduplication** |    
| **File Backups (Local)** | Compression only | **rustic deduplication** |    

### Technical Changes

| Item | Legacy                   | New                                                       |    
|------|--------------------------|-----------------------------------------------------------|    
| **Application package name** | `com.xayah.databackup.*` | `com.xayah.databackup.revived.*`                          |    
| **Version number** | 2.x.x                    | **3.0.0** (block-level dedup via rustic)                   |    
| **Root Framework** | Custom root service      | **libsu integration**                                     |    
| **Backup Engine** | Single compression       | **Dual-layer: uncompressed tar + rustic block-level dedup & encryption** |    
| **Restic/rustic runtime** | non                      | **In-process `rustic_core` via JNI (`librustic.so`)** |    

### 🏗️ Dual-Layer Local Backup Architecture

Raw data → **uncompressed** `tar` → **rustic block-level deduplication (JNI)** → Local repository

- **Layer 1: Packaging Layer** — packages APK/data/files into an **uncompressed** `.tar` archive to maximize deduplication efficiency (output e.g. `apk.tar` for OBB/DATA/USER/USER_DE/MEDIA).
- **Layer 2: rustic Deduplication Layer** — block-level deduplication, AES-256 encryption, and snapshot management on restic-compatible repositories (tag format: `userId-packageName-timestamp-apk`).

> ✅ By avoiding compression before dedup, rustic can more effectively identify and eliminate redundant blocks across different backup runs and devices.

### 🔄 libsu Integration
- Root access is handled via **libsu** for better Magisk/KernelSU/APatch support, improved error handling, and enhanced security.

### 📦 Coverage (Local, via JNI rustic)
- **APK Backups**, **App Data Backups**, and **File Backups** all run through the JNI rustic path.
- New UI: Restore List Page (browse/select snapshots) and Snapshot Detail Page (types & progress).
- Flow: Configure repository → Browse snapshots → Select app/file version → View details → One-click restore.

### 📊 Technical Metrics (Local)

| Feature | Legacy Backup | rustic Backup (JNI, Local) |    
|--------|---------------|---------------------------|    
| **Storage Efficiency** | Basic compression | **Block-level deduplication + compression** |    
| **Incremental Backup** | Not supported | **Supported** |    
| **Data Encryption** | None | **AES-256 encryption** |    
| **Version Management** | File overwrite | **Snapshot-based versioning** |    
| **Restore Granularity** | Batch restore | **Per-app and per-file precision** |    
| **Storage Footprint** | Linear growth | **60–90% space savings** |    
| **Root Access** | Custom implementation | **libsu integration** |    
| **Backup Runtime** | External CGO binary | **In-process `rustic_core` via JNI** |  

> **DataBackup Revived is now a modern data management platform with local block-level deduplication (built-in rustic over JNI) and libsu integration; remote protocol migration to JNI is in progress.**

## Native JNI Migration (`rustic-jni` branch)

> 🎯 **Local backup/restore has been migrated from invoking an external Restic Android CGO binary to calling the Rust `rustic_core` library directly over JNI. The Rust code is compiled into `librustic.so` and shipped inside the App, removing any runtime dependency on an external binary.**

### Background & Motivation

Previously, all local backup/restore operations shelled out to an external Restic Android CGO binary. This required distributing/managing a separate binary, forking a child process for every operation, and parsing textual/JSON output; the upstream binary also suffered from DNS-resolution and dynamic-linking issues on Android. The `rustic-jni` migration replaces this with a native, in-process integration:

- **`rustic_core`** is pinned to **`0.12.0`** and vendored as a native submodule reference.
- The Rust crate is compiled to a **static library**, linked into a JNI anchor, and packaged as **`librustic.so`** distributed with the App.
- **No external binary and no child-process spawning** are needed at runtime for local operations.
- The repository format remains **restic-compatible**, so existing repositories keep working.

### Architecture

```  
Kotlin (Rustic.kt)  
      │  external fun native*  (JNI)  
      ▼  
AIDL (IRemoteRootService) ──► RemoteRootService / RemoteRootServiceImpl  (root process)  
      │  
      ▼  
librustic.so  (JNI anchor rustic.cpp + Rust staticlib)  
      │  
      ▼  
rustic_core (Rust)  ──►  Local / S3 repository (via opendal backend)  
      ▲  
      └── progress callback (bytes/speed/percent) ──► Kotlin onProgress(JJF)V  
```  

- **Rust side** (`source/native/src/main/jni/external/rustic/rustic`):
  - `lib.rs` wires the crate modules (`error`, `jni_bridge`, `jni_progress`, `progress`, `repository`) and enables `#![deny(improper_ctypes_definitions)]`.
  - `jni_bridge.rs` exposes the `Java_com_xayah_libnative_Rustic_*` symbols.
  - `repository.rs` wraps the full capability set: `init_repository`, `repository_exists`, `validate_repository`, `create_snapshot` (plus a progress variant), `restore_snapshot` (plus a progress variant), `check_repository`, `forget_snapshot`, `prune_repository`, and `list_snapshots_db`.
  - `list_snapshots_db` opens the repository, reads all snapshots, and **writes them directly into a SQLite `.db` file** using a statically-compiled `rusqlite` (`bundled`). The DB contains 4 tables plus a `v_snapshots_full` view, so the Android side can `SQLiteDatabase.openDatabase(...)` and `rawQuery(...)` directly.

- **Progress reporting**:
  - `progress.rs` defines a JNI-agnostic `RusticProgressCallback` trait, bridged to `rustic_core`'s `ProgressBars` via `AndroidProgressBars`. Only byte-level progress (`ProgressType::Bytes`) is surfaced; spinner/counter progress is hidden.
  - Progress callbacks are throttled to once per second (`PROGRESS_CALLBACK_INTERVAL = 1s`); on `finish`, the average transfer speed for the whole run is reported.
  - `jni_progress.rs` caches the `onProgress(JJF)V` method ID and calls back into Kotlin via `attach_current_thread`.

- **Build wiring (CMake + Corrosion)**:
  - `source/native/src/main/jni/CMakeLists.txt` adds the `rustic` subdirectory.
  - `source/native/src/main/jni/rustic/CMakeLists.txt` uses **Corrosion (v0.6.1)** via `FetchContent` + `corrosion_import_crate` to build the Rust staticlib (`PROFILE release`, `LOCKED`), then links it into the JNI anchor `rustic.cpp` with `--whole-archive` to produce `librustic.so`.
  - A `rustic.map` version script restricts exported symbols to `Java_com_xayah_libnative_Rustic_*` only.
  - The Cargo release profile enables `lto = true`, `codegen-units = 1`, `panic = "abort"`, and `strip = true` for size/performance.

- **Kotlin & AIDL layer**:
  - `Rustic.kt` provides the Kotlin API with backend options passed as a `Map<String,String>` (key/value string arrays zipped into a map over JNI).
  - `IRemoteRootService.aidl` adds 10 Rustic methods (`getRusticVersion`, `initRusticRepository`, `rusticRepositoryExists`, `validateRusticRepository`, `createRusticSnapshot`, `restoreRusticSnapshot`, `checkRusticRepository`, `forgetRusticSnapshot`, `pruneRusticRepository`, `listRusticSnapshotsDb`), with `ICallback` carrying progress.
  - The root process loads the library at startup (`System.loadLibrary("rustic")` + `Rustic.initLogger()`), and `RemoteRootServiceImpl` forwards each AIDL call to `Rustic` under `synchronized(lock)`.

### Migration Phases

- **Phase 0** — Pin `rustic_core` to `0.12.0` and sync upstream source as the JNI reference.
- **Phase 1** — Stand up the JNI bridge and close the loop capability-by-capability: `get_version` → `init`/`exists`/`validate` → `create`/`restore` (with progress) → `check` → `forget`/`prune` → `list_snapshots_db` (direct SQLite write); add opendal backend-options passthrough.
- **Phase 2** — Wire the new JNI symbols into CMake + instrumented tests; AIDL + `RemoteRootService` wiring.
- **Phase 3** — Migrate local **restore** to JNI rustic with progress/plan callbacks; ship the Rust release profile so native performance meets expectations.

### Testing

- **Host tests** (`base_test.rs`): repository probe/validate, snapshot create-restore-check, multi-source snapshots, snapshot-with-progress, forget/prune, and SQLite export.
- **Device tests** (`RusticInstrumentedTest.kt`): full lifecycle plus querying `v_snapshots_full` through `SQLiteDatabase`, covering the new JNI symbols and the `opendal:fs` non-empty options passthrough.

## Screenshots – Restic

<div align="center">      
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotsrestic/20251219233244_345_20.png" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotsrestic/20251219233246_347_20.png" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotsrestic/20251219233248_349_20.png" width="275px">      
</div>      

## Screenshots – S3

<div align="center">      
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233930_19_20.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233931_20_20.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233931_21_20.jpg" width="275px">      
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233932_22_20.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233933_23_20.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112234045_24_20.jpg" width="275px">      
</div>      

## Screenshots

<div align="center">      
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/01.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/02.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/03.jpg" width="275px">      
</div>      
<div align="center">      
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/04.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/05.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/06.jpg" width="275px">      
</div>      

## Download

Get the APK from [Releases](https://github.com/543069760/Android-DataBackup-S3/releases).

**Original Author [XayahSuSuSu](https://github.com/XayahSuSuSu/Android-DataBackup)**:

- PayPal: https://paypal.me/XayahSuSuSu
- Afdian: https://afdian.net/a/XayahSuSuSu

## Open Source License

This project is modified based on [XayahSuSuSu/Android-DataBackup](https://github.com/XayahSuSuSu/Android-DataBackup) and follows the [GNU General Public License v3.0](./LICENSE).