<div align="center">    

<span style="font-weight: bold"> <a href="./README.md"> 中文 </a> | <a href="./README_en.md"> English </a> </span>    

<img src="./fastlane/metadata/android/en-US/images/icon.png" alt="logo" width="128px" />    

<h1 align="center">DataBackup Revived</h1>  

**更好用、更强大的 安卓备份工具。** 备份自动加密更安全，全面切换到rustic（restic），原生**支持增量备份**，备份更快更省时（**比如微信增量备份，不用重新完整备份，省时快速**），同时节省 60–90% 的存储空间。

[![GitHub release](https://img.shields.io/github/v/release/543069760/Android-DataBackup-Restic?color=orange)](https://github.com/543069760/Android-DataBackup-S3/releases)  
[![License](https://img.shields.io/github/license/543069760/Android-DataBackup-Restic?color=ff69b4)](./LICENSE)  
[![Download](https://img.shields.io/github/downloads/543069760/Android-DataBackup-S3/total)](https://github.com/543069760/Android-DataBackup-S3/releases)

</div>  

> ℹ️ **无需任何外部 Restic 二进制文件。** 本地备份/恢复由 Rust 的 `rustic_core` 库驱动（Rust 实现，兼容 restic 仓库格式），编译进 `librustic.so` 随 App 一同分发，并通过 JNI 直接调用。**不再需要下载、安装或管理独立的 Restic Android CGO 二进制文件**，此前上游二进制在 Android 上的 DNS 解析与动态链接问题也不复存在。详见 [Native JNI 迁移](#native-jni-迁移rustic-jni-分支)。

## 功能特性

* :deciduous_tree: **需要 Root 权限。支持 [Magisk](https://github.com/topjohnwu/Magisk)、[KernelSU](https://github.com/tiann/KernelSU) 以及 [APatch](https://github.com/bmax121/APatch)**
* :cyclone: **多用户支持（备份与恢复列表均提供多用户 Tab，支持分身/多用户应用）**
* :cloud: **支持多种云存储协议**
* :sunglasses: **数据完整性保证**
* :zap: **极速（流式直通备份，tar stdout 经管道直接喂给 rustic，不落盘）**
* :sunny: **简单易用**
* :sparkles: **支持多版本备份**
* :rose: **本地备份基于内置 `rustic_core`（JNI，兼容 restic 仓库）的块级去重**
* :electric_plug: **无需外部二进制——`rustic_core` 编译进 `librustic.so` 并通过 JNI 调用**
* :rocket: **集成 libsu 以增强 Root 操作体验**

## 版本对比

### 云存储协议支持

| 功能                            | 旧版 (DataBackup) | 新版 (DataBackup Revived 3.0.0)             |    
|-------------------------------| --- |-------------------------------------------|    
| **本地存储 / USB OTG外部存储**        | ✅ 已支持 | ✅ **块级去重（JNI rustic，已完成）**                |    
| **AWS S3(兼容Minio Rustfs) 协议** | ❌ 不支持 | ✅ **块级去重（JNI rustic，已完成）**                |    
| **Tencent COS 协议**            | ❌ 不支持 | ✅ **块级去重（JNI rustic，已完成）**                |    
| **Aliyun OSS 协议**             | ❌ 不支持 | ❗ **块级去重（JNI rustic，待实现）**                |    
| **HuaWei OBS 协议**             | ❌ 不支持 | ❗ **块级去重（JNI rustic，待实现）**                |    
| **FTP 协议**                    | ✅ 已支持 | ✅ **块级去重（JNI rustic over librclone，已完成）** |    
| **SFTP 协议**                   | ✅ 已支持 | ✅ **块级去重（JNI rustic over librclone，已完成）** |    
| **WebDAV 协议**                 | ✅ 已支持 | ✅ **块级去重（JNI rustic，已完成）**                |    
| **SMB/CIFS 协议**               | ✅ 已支持 | ❗ **块级去重（JNI rustic over librclone，待实现）** |    

> 阿里云 OOS、华为云OBS、SMB/CIFS：在逐步适配中，敬请期待.

### 备份架构演进

| 功能 | 旧版 | 新版 (已完成)                 |    
| --- | --- |--------------------------|    
| **本地备份引擎** | 仅 tar+zstd 压缩 | **rustic 块级增量去重（JNI，无二进制）** |    
| **数据落地** | 先写压缩包到磁盘 | **fd 流式直通：tar stdout → pipe → rustic，不落盘** |    
| **Root 权限** | 自定义实现 | **集成 libsu**             |    
| **存储效率** | 线性增长 | **节省 60–90% 空间**         |    
| **数据加密** | 无 | **AES-256 加密**           |    
| **增量备份** | 不支持 | **原生支持**                 |    
| **版本管理** | 文件覆盖 | **基于快照的版本控制**            |    
| **APK 备份（本地/云端）** | 仅压缩 | **rustic 增量去重（fd 流式）**          |    
| **应用数据备份（本地/云端）** | 仅压缩 | **rustic 增量去重（fd 流式）**          |    
| **文件/媒体备份（本地/云端）** | 仅压缩 | **rustic 增量去重（fd 流式）** |    

### 技术变更

| 项目 | 旧版                       | 新版 |    
| --- |--------------------------| --- |    
| **应用包名** | `com.xayah.databackup.*` | `com.xayah.databackup.revived.*` |    
| **版本号** | 2.x.x                    | **3.0.0**（基于 rustic 的块级去重） |    
| **Root 框架** | 自定义 Root 服务              | **libsu 集成** |    
| **备份引擎** | 单一压缩                     | **流式架构：非压缩 tar（管道输出）+ rustic 块级去重与加密** |    
| **Restic/rustic 运行时** | 无                        | **进程内 `rustic_core`，通过 JNI（`librustic.so`）** |  

### 🏗️ 流式备份架构

原始数据 → **非压缩** `tar`（stdout 输出）→ **匿名管道 fd** → **rustic 块级去重（JNI，`createSnapshotFromFd`）** → 仓库

- **打包层** — 将 APK/数据/媒体以**非压缩** `tar` 流式输出到管道，以最大化去重效率；tar 记录块、管道容量与 rustic 读端缓冲统一对齐 **1MB**，管道扩容通过 native `fcntl(F_SETPIPE_SZ)` 实现（含 API 30 版本守卫）。
- **rustic 去重层** — 直接在 fd 上进行块级去重、AES-256 加密与快照写入，**全程不落盘**（不再生成 `apk.tar`/`media.tar` 中间文件），本地与云端、应用与媒体备份路径一致；仅 `package_restore_config.json`/`media_restore_config.json` 等小型配置走文件路径快照。
- **可取消与进度** — fd 流传输支持 `cancelFd` 主动取消；UI 实时显示速度与累积传输量（fd 流总长未知，不显示百分比）。
- **快照标签格式** — `user_${userId}-${packageName}-${timestamp}-${type}`（`type` 为 `apk`/`user`/`media`/`config` 等），`user_` 前缀是 `ResticShared.parseAppsDb` 解析必需，请勿省略。

> ✅ 通过在去重前避免压缩与磁盘落地，rustic 能更有效地识别并消除不同备份批次和设备之间的冗余数据块，同时减少 I/O 与存储写放大。

### 🔄 libsu 集成
- Root 权限通过 **libsu** 处理，获得更好的 Magisk/KernelSU/APatch 支持、改进的错误处理与更强的安全性。

### 📦 覆盖范围（本地与云端，经 JNI rustic）
- **APK 备份**、**应用数据备份**、**文件/媒体备份** 均通过 fd 流式 JNI rustic 路径执行（本地与云端一致）。
- 备份列表支持展示**黑名单应用**。
- 恢复列表（本地页 + 云端页）新增**多用户 Tab**，与备份列表对齐；应用恢复列表按应用聚合分组并展示分身用户 ID；支持**全选/反选**并保留详情页往返选择状态。
- 恢复「继续」按钮增加 **FBE 目标用户解锁预检**弹窗；快照解出按候选 subPath 前缀回退，兼容默认目录迁移前的旧中转快照。
- 列表项不再显示快照 ID，界面更简洁。

### 📊 技术指标

| 特性 | 旧版备份  | rustic 备份（JNI） |    
| --- |-------| --- |    
| **存储效率** | 基础压缩  | **块级去重 + 压缩** |    
| **增量备份** | 不支持   | **原生支持** |    
| **数据加密** | 无     | **AES-256 加密** |    
| **版本管理** | 文件覆盖  | **基于快照的版本控制** |    
| **还原粒度** | 批量还原  | **支持单个应用和单个文件的精准还原** |    
| **存储占用** | 线性增长  | **节省 60–90% 空间** |    
| **数据传输** | 压缩包落盘后上传 | **fd 流式直通，不落盘，可取消** |    
| **Root 权限** | 自定义实现 | **libsu 集成** |    
| **备份运行时** | 无     | **进程内 `rustic_core`，通过 JNI** |    

> **DataBackup Revived 现已成为一个现代化数据管理平台，具备本地与云端块级去重（内置 rustic，经 JNI 调用）、fd 流式直通备份与 libsu 集成；剩余远程协议向 JNI 的迁移正在进行中。**

## Native JNI 迁移（`rustic-jni` 分支）

> 🎯 **本地备份/恢复已从"调用外部 Restic Android CGO 二进制"迁移到"通过 JNI 直接调用 Rust 的 `rustic_core` 库"。Rust 代码编译进 `librustic.so` 随 App 分发，彻底消除对外部二进制的运行时依赖。**

### 背景与动机

此前，所有本地备份/恢复都要调用外部 Restic Android CGO 二进制：需要单独分发/管理二进制、每次操作 fork 子进程、并解析文本/JSON 输出；上游二进制在 Android 上还存在 DNS 解析和动态链接问题。`rustic-jni` 迁移用原生进程内集成替代了它：

- **`rustic_core`** 固定到 **`0.12.0`**，作为 native 子模块参考引入。
- Rust crate 编译为**静态库**，链接进 JNI 锚点，打包为 **`librustic.so`** 随 App 分发。
- 本地操作在运行时**不再需要任何外部二进制，也不再 fork 子进程**。
- 仓库格式仍与 **restic 兼容**，现有仓库可继续使用。

### 架构

```  
Kotlin (Rustic.kt)  
      │  external fun native*  (JNI)  
      ▼  
AIDL (IRemoteRootService) ──► RemoteRootService / RemoteRootServiceImpl  （root 进程）  
      │  
      ▼  
librustic.so  （JNI 锚点 rustic.cpp + Rust 静态库）  
      │  
      ▼  
rustic_core (Rust)  ──►  本地 / S3 仓库（经 opendal backend）  
      ▲  
      └── 进度回调（字节/速度/百分比）──► Kotlin onProgress(JJF)V  
```  

- **Rust 侧**（`source/native/src/main/jni/external/rustic/rustic`）：
  - `lib.rs` 组织 crate 模块（`error`、`jni_bridge`、`jni_progress`、`progress`、`repository`），并启用 `#![deny(improper_ctypes_definitions)]`。
  - `jni_bridge.rs` 暴露 `Java_com_xayah_libnative_Rustic_*` 符号。
  - `repository.rs` 封装完整能力集：`init_repository`、`repository_exists`、`validate_repository`、`create_snapshot`（含带进度版本）、`create_snapshot_from_fd`（fd 流式快照）、`restore_snapshot`（含带进度版本）、`check_repository`、`forget_snapshot`、`prune_repository`、`list_snapshots_db`。
  - `list_snapshots_db` 打开仓库读取全部快照，用静态编译的 `rusqlite`（`bundled`）**直写 SQLite `.db` 文件**：含 4 张表 + `v_snapshots_full` 视图，Android 侧可用 `SQLiteDatabase.openDatabase(...)` 直接 `rawQuery(...)`。

- **流式备份**：
  - Kotlin 侧 `RootService.backupTarToRustic` 在 root 进程执行 `tar`（stdout → pipe），把读端 fd 传给 Rust `create_snapshot_from_fd`；rustic 直接消费字节流写快照。
  - 三级缓冲统一 1MB：tar `--record-size`、pipe `F_SETPIPE_SZ`（native fcntl，API 30 守卫）、rustic 读缓冲。
  - 支持 `cancelFd` 取消；进度回调上报速度与累积字节数。

- **进度回调**：
  - `progress.rs` 定义与 JNI 无关的 `RusticProgressCallback` trait，通过 `AndroidProgressBars` 桥接 `rustic_core` 的 `ProgressBars`；只上报字节级进度（`ProgressType::Bytes`），spinner/counter 进度隐藏。
  - 进度回调节流到每秒一次（`PROGRESS_CALLBACK_INTERVAL = 1s`）；`finish` 时上报整段传输的平均速度。
  - `jni_progress.rs` 缓存 `onProgress(JJF)V` 方法 ID，通过 `attach_current_thread` 回调 Kotlin。

- **构建接线（CMake + Corrosion）**：
  - `source/native/src/main/jni/CMakeLists.txt` 加入 `rustic` 子目录。
  - `source/native/src/main/jni/rustic/CMakeLists.txt` 通过 `FetchContent` + `corrosion_import_crate` 使用 **Corrosion (v0.6.1)** 构建 Rust 静态库（`PROFILE release`、`LOCKED`），再用 `--whole-archive` 链接进 JNI 锚点 `rustic.cpp` 生成 `librustic.so`。
  - `rustic.map` 版本脚本仅导出 `Java_com_xayah_libnative_Rustic_*` 符号。
  - Cargo release profile 开启 `lto = true`、`codegen-units = 1`、`panic = "abort"`、`strip = true`，确保原生性能。

- **Kotlin 与 AIDL 层**：
  - `Rustic.kt` 提供带 backend options（`Map<String,String>`，key/value 两个字符串数组经 JNI zip 成 map）的 Kotlin API。
  - `IRemoteRootService.aidl` 暴露 Rustic 系列方法（`getRusticVersion`、`initRusticRepository`、`rusticRepositoryExists`、`validateRusticRepository`、`createRusticSnapshot`、`createRusticSnapshotFromFd`/`backupTarToRustic`、`restoreRusticSnapshot`、`checkRusticRepository`、`forgetRusticSnapshot`、`pruneRusticRepository`、`listRusticSnapshotsDb` 等），并用 `ICallback` 传递进度。
  - root 进程启动时加载库（`System.loadLibrary("rustic")` + `Rustic.initLogger()`），`RemoteRootServiceImpl` 在 `synchronized(lock)` 下把每个 AIDL 调用转发给 `Rustic`。

### 迁移分阶段

- **阶段 0** — 固定 `rustic_core` 到 `0.12.0` 并同步上游源作为 JNI 参考。
- **阶段 1** — 搭建 JNI 桥并逐个能力打通：`get_version` → `init`/`exists`/`validate` → `create`/`restore`（带进度）→ `check` → `forget`/`prune` → `list_snapshots_db` 直写 SQLite；追加 opendal backend-options 贯通。
- **阶段 2** — 为新 JNI 符号接入 CMake + instrumented 测试；AIDL + `RemoteRootService` 接线。
- **阶段 3** — 本地 **restore** 迁移到 JNI rustic，贯通进度/plan 回调；发布 Rust release profile 使原生性能达到预期。
- **阶段 4** — fd 流式直通备份全面落地：本地 app → 云端 app → 文件/媒体；三级缓冲 1MB 对齐 + `F_SETPIPE_SZ` native 扩容（API 30 守卫）；`cancelFd` 取消与 UI 实时速度/累积量展示。

### 测试

- **Host 端测试**（`base_test.rs`）：仓库探测/校验、快照创建-恢复-校验、多源快照、带进度快照、forget/prune、写 SQLite。
- **设备端测试**（`RusticInstrumentedTest.kt`）：完整生命周期，并用 `SQLiteDatabase` 查询 `v_snapshots_full`，覆盖新增 JNI 符号与 `opendal:fs` 非空选项贯通。

## 屏幕截图 – Restic

<div align="center">    

<img src="./fastlane/metadata/android/en-US/images/phoneScreenshotsrestic/20251219233244_345_20.png" width="275px">    

<img src="./fastlane/metadata/android/en-US/images/phoneScreenshotsrestic/20251219233246_347_20.png" width="275px">    

<img src="./fastlane/metadata/android/en-US/images/phoneScreenshotsrestic/20251219233248_349_20.png" width="275px">    

</div>    

## 屏幕截图 – S3

<div align="center">    

<img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233930_19_20.jpg" width="275px">    

<img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233931_20_20.jpg" width="275px">    

<img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233931_21_20.jpg" width="275px">    

<img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233932_22_20.jpg" width="275px">    

<img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233933_23_20.jpg" width="275px">    

<img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112234045_24_20.jpg" width="275px">    

</div>    

## 更多屏幕截图

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

## 下载

请从 [Releases](https://github.com/543069760/Android-DataBackup-S3/releases) 获取 APK。
  
---  

**原作者 [XayahSuSuSu](https://github.com/XayahSuSuSu/Android-DataBackup)**：

- PayPal：https://paypal.me/XayahSuSuSu
- 爱发电：https://afdian.net/a/XayahSuSuSu

## 开源协议

本项目基于 [XayahSuSuSu/Android-DataBackup](https://github.com/XayahSuSuSu/Android-DataBackup) 修改，遵循 [GNU General Public License v3.0](./LICENSE)。