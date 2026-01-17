<div align="center">

<span style="font-weight: bold"> <a href="./README.md"> English </a> | <a href="./README_zh-CN.md"> 中文 </a> </span>

<img src="./fastlane/metadata/android/en-US/images/icon.png" alt="logo" width="128px" />

<h1 align="center">DataBackup Revived</h1>

免费且开源的数据备份应用程序

</div>

## 项目概览

<a href="[https://hellogithub.com/repository/3e9dc382d4764688856238a83616de5b](https://hellogithub.com/repository/3e9dc382d4764688856238a83616de5b)" target="_blank"><img src="[https://abroad.hellogithub.com/v1/widgets/recommend.svg?rid=3e9dc382d4764688856238a83616de5b&claim_uid=POXv2xVC71JHihc&theme=neutral](https://abroad.hellogithub.com/v1/widgets/recommend.svg?rid=3e9dc382d4764688856238a83616de5b&claim_uid=POXv2xVC71JHihc&theme=neutral)" alt="Featured｜HelloGitHub" style="width: 250px; height: 54px;" width="250" height="54" /></a>

:star: 派生自 [XayahSuSuSu](https://github.com/XayahSuSuSu/Android-DataBackup)。

## 功能特性

* :deciduous_tree: **需要 Root 权限。支持 [Magisk](https://github.com/topjohnwu/Magisk), [KernelSU](https://github.com/tiann/KernelSU) 以及 [APatch**](https://github.com/bmax121/APatch)
* :cyclone: **多用户支持**
* :cloud: **支持多种云存储协议**
* :sunglasses: **100% 数据完整性保证**
* :zap: **极速**
* :sunny: **简单易用**
* :sparkles: **支持多版本备份**
* :rose: **本地备份全面支持基于 Restic 的块级去重**
* :rocket: **集成 libsu 以增强 Root 操作体验**

## 版本对比

### 云存储协议支持

| 功能 | 旧版 (DataBackup) | 新版 (DataBackup Revived 3.0.0) |
| --- | --- | --- |
| **S3 协议** | ❌ 不支持 | ✅ 支持（可选 HTTP/HTTPS） |
| **FTP 协议** | ✅ 已支持 | ✅ 已支持 |
| **SFTP 协议** | ✅ 已支持 | ✅ 已支持 |
| **WebDAV 协议** | ✅ 已支持 | ✅ 已支持 |
| **SMB/CIFS 协议** | ✅ 支持多版本 | ✅ 支持多版本 |
| **本地存储** | ✅ 已支持 | ✅ **基于 Restic 的重复数据删除** |

### 备份架构演进

| 功能 | 旧版 | 新版 (已完成)                 |
| --- | --- |--------------------------|
| **本地备份引擎** | 仅 tar+zstd 压缩 | **Restic 块级增量去重**        |
| **Root 权限** | 自定义实现 | **集成 libsu**             |
| **存储效率** | 线性增长 | **节省 60–90% 空间**         |
| **数据加密** | 无 | **AES-256 加密**           |
| **增量备份** | 不支持 | **原生支持**                 |
| **版本管理** | 文件覆盖 | **基于快照的版本控制**            |
| **APK 备份** | 仅压缩 | **Restic 增量去重**          |
| **应用数据备份** | 仅压缩 | **Restic 增量去重**          |
| **文件备份** | 仅压缩 | **Restic 增量去重 (目前仅限本地)** |

### 技术变更

| 项目 | 旧版 | 新版 |
| --- | --- | --- |
| **应用包名** | `com.xayah.databackup.*` | `com.xayah.databackup.revived.*` |
| **版本号** | 2.x.x | **3.0.0** (全面转向 Restic) |
| **Root 框架** | 自定义 Root 服务 | **libsu 集成** |
| **备份引擎** | 单一压缩 | **双层架构：压缩 + Restic** |

## 架构里程碑已完成 (2026-01-17)

> 🎯 **全面完成 Restic 转型与 S3 集成：所有本地备份现在均使用集成了 libsu 的块级去重技术。S3 配置现已初步集成 Restic 仓库初始化，实现了 APK 和应用数据的正常备份操作。**

> ⚠️ **特别说明：截至 2026 年 1 月 17 日，Restic 快照备份已支持本地存储和 S3 兼容服务（仅限 APK 和应用数据），覆盖范围包括 APK、应用私有数据和用户文件。S3 文件备份功能尚未实现。**

### 🏗️ 完整的双层备份架构

#### 最终备份架构

原始数据 → tar (应用数据使用 zstd) 压缩 → Restic 块级去重 → 本地/S3 存储

---

#### 应用备份逻辑

* **第 1 层：压缩层**
* 功能：将 APK 文件打包为 **不压缩** 的 `.tar` 归档，以最大化去重效率
* 输出：`apk.tar（包括 OBB\DATA\USER\USER_DE\MEDIA)`


* **第 2 层：Restic 去重层**
* 功能：块级去重、AES-256 加密、快照管理
* 标签格式：`userId-packageName-timestamp-apk（或 OBB\DATA\USER\USER_DE\MEDIA)`



> ✅ **注意**：通过在 APK 和文件备份层避免压缩，Restic 可以更有效地识别并消除不同备份批次和设备之间的冗余数据块。

---

### 🔄 libsu 集成

#### Root 权限现代化

* **此前**：自定义 Root 服务实现
* **当前**：**集成 libsu** 以获得更高的稳定性和兼容性
* **优势**：
* 更好的 Magisk/KernelSU/APatch 支持
* 改进的错误处理
* 增强的安全性能



### 📦 通用 Restic 实现

#### 全面覆盖

* **APK 备份**：现已使用 Restic 去重
* **应用数据备份**：现已使用 Restic 去重
* **文件备份**：现已使用 Restic 去重（仅限本地，S3 待办）

#### 精准还原特性

* **应用级精度**：还原特定应用程序
* **文件级精度**：支持过滤还原特定文件
* **版本选择**：可选择任意历史版本

### 🎨 增强的用户体验

#### 新 UI 组件

* Restic 还原列表页：浏览并选择快照备份
* 快照详情页：显示备份类型和进度
* 文件还原过滤：精确的文件选择
* 还原进度追踪：实时的还原状态显示

#### 优化的交互流程

配置 Restic 仓库 → 浏览快照 → 选择应用/文件版本 → 查看详情 → 一键还原

### 📊 技术指标 (最终形态)

| 特性 | 旧版备份 | Restic 备份 (通用) |
| --- | --- | --- |
| **存储效率** | 基础压缩 | **块级去重 + 压缩** |
| **增量备份** | 不支持 | **原生支持** |
| **数据加密** | 无 | **AES-256 加密** |
| **版本管理** | 文件覆盖 | **基于快照的版本控制** |
| **云端同步** | ❌ | **支持 S3 (AWS\COS\MINIO\OSS) 用于 APK & 数据** |
| **还原粒度** | 批量还原 | **支持单个应用和单个文件的精准还原** |
| **存储占用** | 线性增长 | **节省 60–90% 空间** |
| **Root 权限** | 自定义实现 | **libsu 集成** |

### 🛡️ 向后兼容性

* **旧版备份**：完全支持并提供自动回退机制
* **迁移路径**：从仅压缩平滑升级至 Restic
* **API 兼容性**：保留现有的还原工作流

### 🎉 里程碑成就

此次全面转型实现了：

* **存储革命**：所有备份类型普遍节省 60–90% 的空间
* **安全升级**：所有数据均享有 AES-256 加密和不可变快照保护
* **精准控制**：支持按应用和按文件进行还原
* **现代 Root 集成**：使用 libsu 增强兼容性
* **完善的架构**：所有备份类型均采用通用的双层处理逻辑
* **云端扩展**：初步集成 S3 用于远程 Restic 仓库（APK & 数据）

### 📋 待办事项 (TODO)

* [ ] **S3 仓库初始化检查**：在尝试初始化新仓库 *之前*，实现对现有 S3 仓库初始化的检查。先使用提供的密码进行探测，判断是否存在已有仓库。
* [ ] **S3 仓库处理逻辑**：根据探测结果，决定是使用现有仓库，还是删除并初始化新仓库。
* [ ] **S3 账户保存条件**：确保 S3 账户保存页面仅在整个初始化过程（包括 Restic）完全成功后才保存配置并报错；否则如果过程提前退出，则不应保存凭据。
* [ ] **S3 云端还原逻辑**：设计并实现 S3 云端还原页面的逻辑。该功能尚需进一步规划和设计。
* [ ] **Restic HTTP 协议联动**：目前 Restic 的 HTTP/HTTPS 协议设置未与设置页面的协议选择联动。需要修正以保持一致性。
* [ ] **网络环境选择重构**：当前的“公网 / 内网”选择项已失效（主要是旧的并发模式所致）。计划将其重构为更详细的 S3 服务商类型选择（例如 AWS S3, MINIO, 腾讯云 COS, 阿里云 OSS 等）。
* [ ] **文件备份 S3 支持**：将 S3 备份能力扩展至文件备份（目前 Restic 文件备份仅支持本地存储）。

> **DataBackup Revived 现已成为一个完整的现代化数据管理平台，具备通用的 Restic 块级去重、libsu 集成以及初步的 S3 支持。**

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

## 翻译项目

[<img src="https://hosted.weblate.org/widget/databackup/main/open-graph.png" alt="Translation">](https://hosted.weblate.org/engage/databackup/)

## 贡献者

感谢所有做出贡献的人！

[[贡献者名单](https://contrib.rocks/image?repo=543069760/Android-DataBackup-S3)](https://github.com/543069760/Android-DataBackup-S3/graphs/contributors)

## 支持与赞助

如果你喜欢这个应用并希望支持它变得更好，欢迎赞助我！

[<img src="./docs/static/img/pp_h_rgb.svg" alt="PayPal" height="60">](https://paypal.me/XayahSuSuSu)

[<img src="./docs/static/img/afdian.svg" alt="Afdian" height="60">](https://afdian.net/a/XayahSuSuSu)

## 开源协议

[GNU General Public License v3.0](https://www.google.com/search?q=./LICENSE)