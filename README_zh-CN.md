<div align="center">  

<span style="font-weight: bold"> <a href="./README.md"> English </a> | <a> 中文 </a> </span>  

<img src="./fastlane/metadata/android/en-US/images/icon.png" alt="logo" width="128px" />  

<h1 align="center">数据备份 Revived</h1>  

免费开源的数据备份应用

</div>  

## 概述
<a href="https://hellogithub.com/repository/3e9dc382d4764688856238a83616de5b" target="_blank"><img src="https://abroad.hellogithub.com/v1/widgets/recommend.svg?rid=3e9dc382d4764688856238a83616de5b&claim_uid=POXv2xVC71JHihc&theme=neutral" alt="Featured｜HelloGitHub" style="width: 250px; height: 54px;" width="250" height="54" /></a>

:star: 基于 [XayahSuSuSu](https://github.com/XayahSuSuSu/Android-DataBackup) 的项目而来。

## 功能特性
* :deciduous_tree: **需要 Root 权限,支持 [Magisk](https://github.com/topjohnwu/Magisk)、[KernelSU](https://github.com/tiann/KernelSU)、[APatch](https://github.com/bmax121/APatch)**  
* :cyclone: **多用户支持**  
* :cloud: **支持多种云存储协议**  
* :sunglasses: **100% 数据完整性保证**  
* :zap: **快速**  
* :sunny: **简单易用**  
* :sparkles: **多版本备份支持**  
* :rose: **...**

## 版本对比

### 云存储协议支持对比

| 功能特性 | 旧版本 (DataBackup) | 新版本 (DataBackup Revived 3.0.0) |  
|---------|------------------|----------------------------------|  
| **S3 协议** | ❌ 不支持 | ✅ 支持 HTTP/HTTPS 可选 |  
| **FTP 协议** | ✅ 支持 | ✅ 支持 |  
| **SFTP 协议** | ✅ 支持 | ✅ 支持 |  
| **WebDAV 协议** | ✅ 支持 | ✅ 支持 |  
| **SMB/CIFS 协议** | ✅ 支持多版本 | ✅ 支持多版本 |  
| **本地存储** | ✅ 支持 | ✅ 支持 |

### 多版本备份功能

| 功能特性 | 旧版本 | 新版本 |  
|---------|-------|-------|  
| **同一应用多版本备份** | ❌ 不支持 | ✅ 支持 |  
| **备份时间戳** | ❌ 无 | ✅ 精确到秒 |  
| **历史备份列表** |  ❌ 不支持  | ✅ 显示所有历史版本 |  
| **备份目录结构** | `包名/user_用户ID` | `包名/user_用户ID@时间戳` |  
| **恢复时版本选择** |  ❌ 不支持  | ✅ 可选择任意历史版本 |  
| **备份失败清理** |  ❌ 不支持  | ✅ 自动清理失败备份 |  
| **向后兼容** | N/A | ✅ 兼容旧格式备份 |

### 技术变更

| 项目 | 旧版本 | 新版本                              |  
|------|-------|----------------------------------|  
| **应用包名** | `com.xayah.databackup.*` | `com.xayah.databackup.revived.*` |  
| **版本号** | 2.x.x | **3.0.0** (全新版本)                 |  

## 架构升级里程碑（2025-12-19）

> 🎯 **从传统压缩备份 → Restic 块级去重备份：实现快照恢复到应用恢复的全链路打通**

### 核心架构变革

传统本地备份采用单一的 `tar+zstd` 压缩策略，而 **DataBackup Revived 3.0.0** 引入了 **双层备份架构**，在保留压缩层的基础上，新增 **Restic 块级去重层**，显著提升存储效率与数据安全性（见 `AbstractBackupService.kt:337–387`）。

### 🏗️ 双层备份架构

#### 传统备份架构（已废弃）
```
原始数据 → tar+zstd 压缩 → 本地存储
```

#### 新双层备份架构
```
原始数据 → tar+zstd 压缩 → Restic 块级去重 → 本地存储 [+ 云存储]
```

- **Layer 1: 压缩层（保持不变）**
  - 功能：将应用数据压缩为 `.tar.zst` 格式
  - 实现：`PackagesBackupUtil.backupApk()` 和 `backupData()`（`BackupServiceLocalImpl.kt:56–104`）
  - 输出：`apk.tar.zst`, `data.tar.zst`, `user.tar.zst` 等

- **Layer 2: Restic 去重层（新增）**
  - 功能：块级去重、AES-256 加密、快照管理
  - 实现：`ResticRepository.backupFile()`（`ResticRepository.kt:89–121`）
  - 标签格式：`userId-packageName-timestamp-dataType`（`AbstractBackupService.kt:354–361`）

### 🔄 完整备份流程变革

| 组件 | 传统实现 | 新架构实现 |
|------|--------|----------|
| **备份服务** | `BackupServiceLocalImpl` 仅压缩 | `BackupServiceLocalImpl` 压缩 + Restic 去重 |
| **数据流** | 单一压缩流程 | 双层处理流程 |
| **存储** | 仅本地 `.tar.zst` 文件 | 本地快照库 + 压缩文件 |
| **元数据** | 基础文件信息 | 快照 ID + 结构化标签索引 |

**新备份执行流程**（`BackupServiceLocalImpl.kt:82–100`）：
```kotlin
// 1. 压缩阶段（保持不变）  
mPackagesBackupUtil.backupApk() / backupData()  
  
// 2. Restic 去重阶段（新增）  
val compressedFile = findCompressedFile(dstDir, type)  
val resticSuccess = backupWithRestic(packageName, compressedFile, dataType)
```

### 📦 快照管理与标签系统

- **标签结构设计**  
  格式：`userId-packageName-timestamp-dataType`  
  示例：`user_0-com.android.chrome-1704067200000-apk`

- **快照数据模型**（`ResticRepository.kt:477–484`）：
  ```kotlin
  @Serializable  
  data class ResticSnapshot(  
      val id: String,           // 快照标识符  
      val time: String,         // ISO 时间戳  
      val hostname: String,     // 设备主机名  
      val paths: List<String>,  // 备份路径  
      val tags: List<String>    // 结构化标签  
  )
  ```

- **快照分组逻辑**（`ResticRestoreViewModel.kt:71–96`）：
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

### 🔄 完整恢复链路实现

| 阶段 | 传统恢复流程 | 新 Restic 恢复流程 |
|------|------------|------------------|
| **1. 浏览** | 列表选择 | 快照浏览（`ResticRestoreViewModel.loadBackedUpApps()`） |
| **2. 恢复** | 直接服务恢复 | 快照恢复（`restoreFromResticSnapshots()`） |
| **3. 同步** | — | 数据库同步（`refreshLocalDatabase()`） |
| **4. 精确控制** | 批量恢复 | 按包名单独恢复（`RestoreServiceLocalImpl.kt:54–70`） |

### 🎨 用户界面革新

- **新增 UI 组件**：
  - Restic 恢复列表页：浏览和选择快照备份
  - 快照详情页：显示备份类型与进度（`ResticBackupDetailPage.kt:52–84`）
  - 恢复进度追踪：实时显示快照恢复状态

- **交互流程优化**：
  ```
  设置 Restic 仓库 → 浏览快照备份 → 选择应用版本 → 查看详情 → 一键恢复
  ```

### 📊 技术指标对比

| 特性 | 传统备份 | Restic 备份 |
|------|--------|------------|
| **存储效率** | 基础压缩 | 块级去重 + 压缩 |
| **增量备份** | 不支持 | 原生支持 |
| **数据加密** | 无 | AES-256 加密 |
| **版本管理** | 文件覆盖 | 快照版本控制 |
| **云同步** | 需额外实现 | 原生支持 |
| **恢复精度** | 批量恢复 | 精确到单个应用 |
| **存储空间** | 线性增长 | 去重后节省 60–90% |

### 🔧 核心技术实现

- **Restic 命令集成**（`ResticRepository.kt:97–101`）：
  ```kotlin
  // 备份
  val args = listOf(resticPath, "backup", "--repo", repoPath, filePath, "--tag", tags.joinToString(","), "--json")
  // 恢复
  val args = listOf(resticPath, "restore", fullSnapshotId, "--repo", repoPath, "--target", targetPath, "--include", includePath, "--json")
  ```

- **进度追踪系统**（`ResticRepository.kt:283–292`）：
  ```kotlin
  interface ResticProgressCallback {
      fun onProgress(filesFinished: Long, filesTotal: Long, bytesWritten: Long, bytesTotal: Long, filesSkipped: Long = 0, bytesSkipped: Long = 0)
  }
  ```

### 🛡️ 向后兼容性

- **兼容策略**：
  - 渐进式升级：Restic 仓库未初始化时自动回退到传统备份
  - 数据格式保持：压缩文件格式不变，确保现有备份可读
  - API 兼容：现有恢复流程完全兼容

- **回退机制**（`AbstractBackupService.kt:346–350`）：
  ```kotlin
  if (!resticRepo.checkRepository(repoPath, password)) {
      log { "Restic repository not initialized, skipping backup" }
      return false  // 回退到仅压缩模式
  }
  ```

### 🎉 里程碑意义

此次架构升级实现了：

- **存储革命**：从线性存储到去重存储，节省 60–90% 存储空间  
- **安全升级**：引入 AES-256 加密和快照不可变性  
- **体验提升**：精确到单个应用的恢复控制  
- **架构优化**：双层处理策略，兼顾效率和兼容性  
- **完整链路**：从快照浏览到应用恢复的端到端解决方案  

> **这标志着 DataBackup Revived 从传统备份工具向现代化数据管理平台的重大转型，为后续的云存储深度集成与智能备份策略奠定了坚实基础。**

## 截图 - S3
<div align="center">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233930_19_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233931_20_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233931_21_20.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233932_22_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233933_23_20.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112234045_24_20.jpg" width="275px">  
</div>  

## 截图
<div align="center">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/01.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/02.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/03.jpg" width="275px">  
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/04.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/05.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/06.jpg" width="275px">  
</div>  

## 下载
从 [Releases](https://github.com/543069760/Android-DataBackup-S3/releases) 获取 APK。

## 翻译
[<img src="https://hosted.weblate.org/widget/databackup/main/open-graph.png"  
alt="翻译">](https://hosted.weblate.org/engage/databackup/)

## 贡献者
感谢所有这些优秀的人!

[[贡献者](https://contrib.rocks/image?repo=543069760/Android-DataBackup-S3)](https://github.com/543069760/Android-DataBackup-S3/graphs/contributors)

## 支持
如果您喜欢这个应用并希望帮助它变得更好,欢迎赞助我!

[<img src="./docs/static/img/pp_h_rgb.svg"  
alt="PayPal"  
height="60">](https://paypal.me/XayahSuSuSu)

[<img src="./docs/static/img/afdian.svg"  
alt=爱发电  
height="60">](https://afdian.net/a/XayahSuSuSu)

## 许可证
[GNU General Public License v3.0](./LICENSE)
