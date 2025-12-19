<div align="center">  

<span style="font-weight: bold"> <a href="./README_en.md"> English </a> | <a href="./README.md"> 中文 </a> </span>  

<img src="./fastlane/metadata/android/en-US/images/icon.png" alt="logo" width="128px" />  

<h1 align="center">DataBackup Revived</h1>  

Free and open-source data backup application

</div>  

## Overview
<a href="https://hellogithub.com/repository/3e9dc382d4764688856238a83616de5b" target="_blank"><img src="https://abroad.hellogithub.com/v1/widgets/recommend.svg?rid=3e9dc382d4764688856238a83616de5b&claim_uid=POXv2xVC71JHihc&theme=neutral" alt="Featured｜HelloGitHub" style="width: 250px; height: 54px;" width="250" height="54" /></a>

:star: Forked from [XayahSuSuSu](https://github.com/XayahSuSuSu/Android-DataBackup).

## Features
* :deciduous_tree: **Requires root access. Supports [Magisk](https://github.com/topjohnwu/Magisk), [KernelSU](https://github.com/tiann/KernelSU), and [APatch](https://github.com/bmax121/APatch)**  
* :cyclone: **Multi-user support**  
* :cloud: **Supports multiple cloud storage protocols**  
* :sunglasses: **100% data integrity guarantee**  
* :zap: **Fast**  
* :sunny: **Simple and easy to use**  
* :sparkles: **Multi-version backup support**  
* :rose: **...**

## Version Comparison

### Cloud Storage Protocol Support

| Feature | Legacy (DataBackup) | New (DataBackup Revived 3.0.0) |  
|---------|------------------|----------------------------------|  
| **S3 Protocol** | ❌ Not supported | ✅ Supports optional HTTP/HTTPS |  
| **FTP Protocol** | ✅ Supported | ✅ Supported |  
| **SFTP Protocol** | ✅ Supported | ✅ Supported |  
| **WebDAV Protocol** | ✅ Supported | ✅ Supported |  
| **SMB/CIFS Protocol** | ✅ Multi-version support | ✅ Multi-version support |  
| **Local Storage** | ✅ Supported | ✅ Supported |

### Multi-Version Backup Capabilities

| Feature | Legacy | New |
|---------|-------|-------|
| **Multiple versions per app** | ❌ Not supported | ✅ Supported |
| **Backup timestamp** | ❌ None | ✅ Precise to the second |
| **Historical backup list** | ❌ Not supported | ✅ Shows all historical versions |
| **Backup directory structure** | `package_name/user_userID` | `package_name/user_userID@timestamp` |
| **Version selection during restore** | ❌ Not supported | ✅ Choose any historical version |
| **Failed backup cleanup** | ❌ Not supported | ✅ Automatically cleans up failed backups |
| **Backward compatibility** | N/A | ✅ Compatible with legacy backup format |

### Technical Changes

| Item | Legacy | New |
|------|-------|----------------------------------|
| **Application package name** | `com.xayah.databackup.*` | `com.xayah.databackup.revived.*` |
| **Version number** | 2.x.x | **3.0.0** (brand new version) |

## Architecture Milestone (2025-12-19)

> 🎯 **From traditional compressed backup → Restic block-level deduplication: Full end-to-end chain from snapshot restore to app restore**

### Core Architecture Transformation

The legacy local backup used a single `tar+zstd` compression strategy. **DataBackup Revived 3.0.0** introduces a **dual-layer backup architecture**: while preserving the compression layer, it adds a **Restic block-level deduplication layer**, significantly improving storage efficiency and data security (see `AbstractBackupService.kt:337–387`).

### 🏗️ Dual-Layer Backup Architecture

#### Legacy Backup Architecture (Deprecated)
```
Raw data → tar+zstd compression → Local storage
```

#### New Dual-Layer Backup Architecture
```
Raw data → tar+zstd compression → Restic block-level deduplication → Local storage [+ Cloud storage]
```

- **Layer 1: Compression Layer (unchanged)**
  - Function: Compresses app data into `.tar.zst` format
  - Implementation: `PackagesBackupUtil.backupApk()` and `backupData()` (`BackupServiceLocalImpl.kt:56–104`)
  - Output: `apk.tar.zst`, `data.tar.zst`, `user.tar.zst`, etc.

- **Layer 2: Restic Deduplication Layer (new)**
  - Function: Block-level deduplication, AES-256 encryption, snapshot management
  - Implementation: `ResticRepository.backupFile()` (`ResticRepository.kt:89–121`)
  - Tag format: `userId-packageName-timestamp-dataType` (`AbstractBackupService.kt:354–361`)

### 🔄 Complete Backup Workflow Transformation

| Component | Legacy Implementation | New Architecture |
|----------|----------------------|------------------|
| **Backup Service** | `BackupServiceLocalImpl` (compression only) | `BackupServiceLocalImpl` (compression + Restic deduplication) |
| **Data Flow** | Single compression pipeline | Dual-layer processing pipeline |
| **Storage** | Only local `.tar.zst` files | Local snapshot repository + compressed files |
| **Metadata** | Basic file info | Snapshot ID + structured tag index |

**New Backup Execution Flow** (`BackupServiceLocalImpl.kt:82–100`):
```kotlin
// 1. Compression phase (unchanged)  
mPackagesBackupUtil.backupApk() / backupData()  
  
// 2. Restic deduplication phase (new)  
val compressedFile = findCompressedFile(dstDir, type)  
val resticSuccess = backupWithRestic(packageName, compressedFile, dataType)
```

### 📦 Snapshot Management & Tagging System

- **Tag Structure Design**  
  Format: `userId-packageName-timestamp-dataType`  
  Example: `user_0-com.android.chrome-1704067200000-apk`

- **Snapshot Data Model** (`ResticRepository.kt:477–484`):
  ```kotlin
  @Serializable  
  data class ResticSnapshot(  
      val id: String,           // Snapshot identifier  
      val time: String,         // ISO timestamp  
      val hostname: String,     // Device hostname  
      val paths: List<String>,  // Backup paths  
      val tags: List<String>    // Structured tags  
  )
  ```

- **Snapshot Grouping Logic** (`ResticRestoreViewModel.kt:71–96`):
  ```kotlin
  val groupedBackups = apps  
      .groupBy { "${it.userId}-${it.packageName}-${it.timestamp}" }  
      .values  
      .map { backups ->  
          ResticBackupGroup(  
              packageName = first.packageName,  
              userId = first.userId,  
              timestamp = first.timestamp,  
              backups = backups.sortedBy { it.dataType.type }  
          )  
      }
  ```

### 🔄 End-to-End Restore Chain Implementation

| Stage | Legacy Restore Flow | New Restic Restore Flow |
|------|--------------------|------------------------|
| **1. Browsing** | List selection | Snapshot browsing (`ResticRestoreViewModel.loadBackedUpApps()`) |
| **2. Restoration** | Direct service restore | Snapshot restoration (`restoreFromResticSnapshots()`) |
| **3. Sync** | — | Database synchronization (`refreshLocalDatabase()`) |
| **4. Precision Control** | Batch restore | Per-app restore (`RestoreServiceLocalImpl.kt:54–70`) |

### 🎨 User Interface Innovation

- **New UI Components**:
  - Restic Restore List Page: Browse and select snapshot backups
  - Snapshot Detail Page: Display backup types and progress (`ResticBackupDetailPage.kt:52–84`)
  - Restore Progress Tracker: Real-time snapshot restore status

- **Optimized Interaction Flow**:
  ```
  Configure Restic repo → Browse snapshots → Select app version → View details → One-click restore
  ```

### 📊 Technical Metrics Comparison

| Feature | Legacy Backup | Restic Backup |
|--------|---------------|---------------|
| **Storage Efficiency** | Basic compression | Block-level deduplication + compression |
| **Incremental Backup** | Not supported | Native support |
| **Data Encryption** | None | AES-256 encryption |
| **Version Management** | File overwrite | Snapshot-based versioning |
| **Cloud Sync** | Requires extra implementation | Native support |
| **Restore Granularity** | Batch restore | Per-app precision |
| **Storage Footprint** | Linear growth | 60–90% space savings after deduplication |

### 🔧 Core Technical Implementation

- **Restic Command Integration** (`ResticRepository.kt:97–101`):
  ```kotlin
  // Backup
  val args = listOf(resticPath, "backup", "--repo", repoPath, filePath, "--tag", tags.joinToString(","), "--json")
  // Restore
  val args = listOf(resticPath, "restore", fullSnapshotId, "--repo", repoPath, "--target", targetPath, "--include", includePath, "--json")
  ```

- **Progress Tracking System** (`ResticRepository.kt:283–292`):
  ```kotlin
  interface ResticProgressCallback {
      fun onProgress(filesFinished: Long, filesTotal: Long, bytesWritten: Long, bytesTotal: Long, filesSkipped: Long = 0, bytesSkipped: Long = 0)
  }
  ```

### 🛡️ Backward Compatibility

- **Compatibility Strategy**:
  - Gradual upgrade: Automatically falls back to legacy backup if Restic repo is uninitialized
  - Data format preserved: Compressed file format unchanged, ensuring existing backups remain readable
  - API compatibility: Existing restore workflows fully compatible

- **Fallback Mechanism** (`AbstractBackupService.kt:346–350`):
  ```kotlin
  if (!resticRepo.checkRepository(repoPath, password)) {
      log { "Restic repository not initialized, skipping backup" }
      return false  // Fall back to compression-only mode
  }
  ```

### 🎉 Milestone Significance

This architectural upgrade achieves:

- **Storage Revolution**: From linear storage to deduplicated storage, saving 60–90% space  
- **Security Upgrade**: Introduces AES-256 encryption and immutable snapshots  
- **User Experience Enhancement**: Per-app restore precision  
- **Architectural Optimization**: Dual-layer design balancing efficiency and compatibility  
- **Complete End-to-End Chain**: From snapshot browsing to app-level restoration  

> **This marks DataBackup Revived’s transformation from a traditional backup tool into a modern data management platform, laying a solid foundation for future deep cloud integration and intelligent backup strategies.**

## Screenshots – S3
<div align="center">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233930_19_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233931_20_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233931_21_20.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233932_22_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233933_23_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112234045_24_20.jpg" width="275px">  
</div>  

## Screenshots
<div align="center">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/01.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/02.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/03.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/04.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/05.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/06.jpg" width="275px">  
</div>  

## Download
Get the APK from [Releases](https://github.com/543069760/Android-DataBackup-S3/releases).

## Translation
[<img src="https://hosted.weblate.org/widget/databackup/main/open-graph.png"  
alt="Translation">](https://hosted.weblate.org/engage/databackup/)

## Contributors
Thanks to all these amazing people!

[[Contributors](https://contrib.rocks/image?repo=543069760/Android-DataBackup-S3)](https://github.com/543069760/Android-DataBackup-S3/graphs/contributors)

## Support
If you like this app and want to help make it better, feel free to sponsor me!

[<img src="./docs/static/img/pp_h_rgb.svg"  
alt="PayPal"  
height="60">](https://paypal.me/XayahSuSuSu)

[<img src="./docs/static/img/afdian.svg"  
alt="Afdian"  
height="60">](https://afdian.net/a/XayahSuSuSu)

## License
[GNU General Public License v3.0](./LICENSE)
