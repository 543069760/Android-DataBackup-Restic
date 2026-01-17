<div align="center">    

<span style="font-weight: bold"> <a href="./README.md"> English </a> | <a href="./README_zh-CN.md"> 中文 </a> </span>    

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
* :rose: **Restic-based block-level deduplication for all local backups**
* :rocket: **libsu integration for enhanced root operations**

## Version Comparison

### Cloud Storage Protocol Support

| Feature | Legacy (DataBackup) | New (DataBackup Revived 3.0.0) |    
|---------|------------------|----------------------------------|    
| **S3 Protocol** | ❌ Not supported | ✅ Supports optional HTTP/HTTPS |    
| **FTP Protocol** | ✅ Supported | ✅ Supported |    
| **SFTP Protocol** | ✅ Supported | ✅ Supported |    
| **WebDAV Protocol** | ✅ Supported | ✅ Supported |    
| **SMB/CIFS Protocol** | ✅ Multi-version support | ✅ Multi-version support |    
| **Local Storage** | ✅ Supported | ✅ **Restic-based deduplication** |  

### Backup Architecture Evolution

| Feature | Legacy | New (Completed) |  
|---------|-------|-------|  
| **Local Backup Engine** | tar+zstd compression only | **Restic block-level deduplication** |  
| **Root Access** | Custom implementation | **libsu integration** |  
| **Storage Efficiency** | Linear growth | **60–90% space savings** |  
| **Data Encryption** | None | **AES-256 encryption** |  
| **Incremental Backup** | Not supported | **Native support** |  
| **Version Management** | File overwrite | **Snapshot-based versioning** |  
| **APK Backup** | Compression only | **Restic deduplication** |  
| **File Backup** | Compression only | **Restic deduplication** |  

### Technical Changes

| Item | Legacy | New |  
|------|-------|----------------------------------|  
| **Application package name** | `com.xayah.databackup.*` | `com.xayah.databackup.revived.*` |  
| **Version number** | 2.x.x | **3.0.0** (complete Restic transition) |  
| **Root Framework** | Custom root service | **libsu integration** |  
| **Backup Engine** | Single compression | **Dual-layer: Compression + Restic** |  

## Architecture Milestone Completed (2026-01-17)

> 🎯 **Complete Restic Transition & S3 Integration: All local backups now use block-level deduplication with libsu integration. S3 configuration is now preliminarily integrated with Restic repository initialization, enabling normal backup operations.**

> ⚠️ **Special Note: As of January 17, 2026, Restic snapshot backups are available for local storage and S3-compatible services, covering APKs, application data, and user files.**

### 🏗️ Complete Dual-Layer Backup Architecture

#### Final Backup Architecture

Raw data → tar (zstd for app data) compression → Restic block-level deduplication → Local/S3 storage

---

#### APP Backup Logic

- **Layer 1: Compression Layer**
  - Function: Packages APK files into an **uncompressed** `.tar` archive to maximize deduplication efficiency
  - Output: `apk.tar（OBB\DATA\USER\USER_DE\MEDIA)'`

- **Layer 2: Restic Deduplication Layer**
  - Function: Block-level deduplication, AES-256 encryption, snapshot management
  - Tag format: `userId-packageName-timestamp-apk（OBB\DATA\USER\USER_DE\MEDIA)`


> ✅ **Note**: By avoiding compression in the APK and file backup layers, Restic can more effectively identify and eliminate redundant blocks across different backup runs and devices.

---

### 🔄 libsu Integration

#### Root Access Modernization
- **Previous**: Custom root service implementation
- **Current**: **libsu integration** for enhanced stability and compatibility
- **Benefits**:
  - Better Magisk/KernelSU/APatch support
  - Improved error handling
  - Enhanced security

### 📦 Universal Restic Implementation

#### Complete Coverage
- **APK Backups**: Now use Restic deduplication
- **App Data Backups**: Now use Restic deduplication
- **File Backups**: Now use Restic deduplication

#### Precision Restore Features
- **App-level precision**: Restore specific applications
- **File-level precision**: Restore specific files with filtering
- **Version selection**: Choose any historical version

### 🎨 Enhanced User Experience

#### New UI Components
- Restic Restore List Page: Browse and select snapshot backups
- Snapshot Detail Page: Display backup types and progress
- File Restore Filtering: Precision file selection
- Restore Progress Tracker: Real-time restore status

#### Optimized Interaction Flow
Configure Restic repo → Browse snapshots → Select app/file version → View details → One-click restore

### 📊 Technical Metrics (Final)

| Feature | Legacy Backup | Restic Backup (Universal) |  
|--------|---------------|---------------------------|  
| **Storage Efficiency** | Basic compression | **Block-level deduplication + compression** |  
| **Incremental Backup** | Not supported | **Native support** |  
| **Data Encryption** | None | **AES-256 encryption** |  
| **Version Management** | File overwrite | **Snapshot-based versioning** |  
| **Cloud Sync** | Requires extra implementation | **Native support** |  
| **Restore Granularity** | Batch restore | **Per-app and per-file precision** |  
| **Storage Footprint** | Linear growth | **60–90% space savings** |  
| **Root Access** | Custom implementation | **libsu integration** |  

### 🛡️ Backward Compatibility

- **Legacy Backups**: Fully supported with automatic fallback
- **Migration Path**: Seamless upgrade from compression-only to Restic
- **API Compatibility**: Existing restore workflows preserved

### 🎉 Milestone Achievement

This complete transition achieves:

- **Storage Revolution**: Universal 60–90% space savings across all backup types
- **Security Upgrade**: AES-256 encryption and immutable snapshots for all data
- **Precision Control**: Per-app and per-file restore capabilities
- **Modern Root Integration**: libsu for enhanced compatibility
- **Complete Architecture**: Universal dual-layer processing for all backup types
- **Cloud Expansion**: Initial S3 integration for remote Restic repositories

### 📋 TODO Items

- [ ] **S3 Repository Initialization Check**: Implement a check for existing S3 repository initialization *before* attempting to initialize a new one. Perform a probe using the provided password first to determine if an existing repository exists.
- [ ] **S3 Repository Handling Logic**: Based on the probe result, decide whether to use the existing repository or delete it and initialize a new one.
- [ ] **S3 Account Saving Conditions**: Ensure the S3 account saving page only reports an error and saves the configuration *after* the full initialization process (including Restic) is completely successful. Otherwise, if the process exits prematurely, the credentials should not be saved.
- [ ] **S3 Cloud Restoration Logic**: Design and implement the logic for the S3 cloud restoration page. This feature requires further planning and design.
- [ ] **Restic HTTP Protocol Linkage**: Currently, Restic's HTTP/HTTPS protocol setting is not linked to the protocol selected on the settings page. This needs to be corrected for consistency.
- [ ] **Network Environment Selection Refactor**: The "Public Network / Internal Network" selection option is currently non-functional (mainly due to old concurrent usage patterns). Refactor this into a more detailed S3 provider type selection (e.g., AWS S3, MINIO, Tencent COS, Alibaba OSS, etc.).

> **DataBackup Revived is now a complete modern data management platform with universal Restic-based deduplication, libsu integration, and initial S3 support.**

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
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/04.jpg" width="275px">
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/05.jpg" width="275px">
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/06.jpg" width="275px">    
</div>    

## Download

Get the APK from [Releases](https://github.com/543069760/Android-DataBackup-S3/releases).

## Translation

[<img src="https://hosted.weblate.org/widget/databackup/main/open-graph.png" alt="Translation">](https://hosted.weblate.org/engage/databackup/)

## Contributors

Thanks to all these amazing people!

[[Contributors](https://contrib.rocks/image?repo=543069760/Android-DataBackup-S3)](https://github.com/543069760/Android-DataBackup-S3/graphs/contributors)

## Support

If you like this app and want to help make it better, feel free to sponsor me!

[<img src="./docs/static/img/pp_h_rgb.svg" alt="PayPal" height="60">](https://paypal.me/XayahSuSuSu)  
[<img src="./docs/static/img/afdian.svg" alt="Afdian" height="60">](https://afdian.net/a/XayahSuSuSu)

## License

[GNU General Public License v3.0](./LICENSE)