<div align="center">    

<span style="font-weight: bold"> <a href="./README.md"> English </a> | <a href="./README_zh-CN.md"> 中文 </a> </span>    

<img src="./fastlane/metadata/android/en-US/images/icon.png" alt="logo" width="128px" />    

<h1 align="center">DataBackup Revived</h1>    

免费开源的数据备份应用

</div>    

## 概述

<a href="https://hellogithub.com/repository/3e9dc382d4764688856238a83616de5b" target="_blank"><img src="https://abroad.hellogithub.com/v1/widgets/recommend.svg?rid=3e9dc382d4764688856238a83616de5b&claim_uid=POXv2xVC71JHihc&theme=neutral" alt="Featured｜HelloGitHub" style="width: 250px; height: 54px;" width="250" height="54" /></a>

:star: 派生于 [XayahSuSuSu](https://github.com/XayahSuSuSu/Android-DataBackup)。

## 功能特性

* :deciduous_tree: **需要 root 权限。支持 [Magisk](https://github.com/topjohnwu/Magisk)、[KernelSU](https://github.com/tiann/KernelSU) 和 [APatch](https://github.com/bmax121/APatch)**
* :cyclone: **多用户支持**
* :cloud: **支持多种云存储协议**
* :sunglasses: **100% 数据完整性保证**
* :zap: **快速**
* :sunny: **简单易用**
* :sparkles: **支持多版本备份**
* :rose: **所有本地备份均基于 Restic 实现块级去重**
* :rocket: **集成 libsu 以增强 root 操作**

## 版本对比

### 云存储协议支持情况

| 功能 | 旧版 (DataBackup) | 新版 (DataBackup Revived 3.0.0) |    
|------|------------------|----------------------------------|    
| **S3 协议** | ❌ 不支持 | ✅ 支持可选 HTTP/HTTPS |    
| **FTP 协议** | ✅ 支持 | ✅ 支持 |    
| **SFTP 协议** | ✅ 支持 | ✅ 支持 |    
| **WebDAV 协议** | ✅ 支持 | ✅ 支持 |    
| **SMB/CIFS 协议** | ✅ 支持多版本 | ✅ 支持多版本 |    
| **本地存储** | ✅ 支持 | ✅ **基于 Restic 的去重** |  

### 备份架构演进

| 功能 | 旧版 | 新版（已完成） |  
|------|------|----------------|  
| **本地备份引擎** | 仅 tar+zstd 压缩 | **Restic 块级去重** |  
| **Root 访问方式** | 自定义实现 | **集成 libsu** |  
| **存储效率** | 线性增长 | **节省 60–90% 空间** |  
| **数据加密** | 无 | **AES-256 加密** |  
| **增量备份** | 不支持 | **原生支持** |  
| **版本管理** | 文件覆盖 | **基于快照的版本控制** |  
| **APK 备份** | 仅压缩 | **Restic 去重** |  
| **文件备份** | 仅压缩 | **Restic 去重** |  

### 技术变更

| 项目 | 旧版 | 新版 |  
|------|------|------|  
| **应用包名** | `com.xayah.databackup.*` | `com.xayah.databackup.revived.*` |  
| **版本号** | 2.x.x | **3.0.0**（完成 Restic 全面迁移） |  
| **Root 框架** | 自定义 root 服务 | **libsu 集成** |  
| **备份引擎** | 单层压缩 | **双层：压缩 + Restic** |  

## 架构里程碑达成（2025-12-31）

> 🎯 **完成 Restic 全面迁移：所有本地备份现已采用块级去重，并集成 libsu**

### 🏗️ 完整的双层备份架构

#### 最终备份流程

原始数据 → tar+zstd 压缩 → Restic 块级去重 → 本地存储 [+ 云存储]

---

#### APK 备份逻辑

- **第一层：压缩层**
  - 功能：将 APK 文件压缩为 `.tar.zst` 格式
  - 输出：`apk.tar.zst`

- **第二层：Restic 去重层**
  - 功能：块级去重、AES-256 加密、快照管理
  - 标签格式：`userId-packageName-timestamp-apk`

#### 应用数据备份逻辑

- **第一层：压缩层**
  - 功能：将应用私有数据压缩为 `.tar.zst` 格式
  - 输出：`data.tar.zst`、`user.tar.zst` 等

- **第二层：Restic 去重层**
  - 功能：块级去重、加密与版本管理
  - 标签格式：`userId-packageName-timestamp-data`

#### 文件备份逻辑

- **第一层：压缩层**
  - 功能：将用户选择的媒体或通用文件打包为未压缩的 `.tar` 归档，以最大化去重效率
  - 输出：`media.tar` 及配套的 `media_restore_config.json`（保存原始路径与元数据）

- **第二层：Restic 去重层**
  - 功能：对归档文件及其配置文件分别进行块级去重、AES-256 加密和快照追踪
  - 标签格式：`userId-media-timestamp-file`

> ✅ **说明**：通过在文件备份层避免压缩，Restic 能更高效地识别并消除不同备份之间重复的数据块。

---

### 🔄 libsu 集成

#### Root 访问现代化
- **之前**：自定义 root 服务实现
- **当前**：**集成 libsu**，提升稳定性与兼容性
- **优势**：
  - 更好地支持 Magisk / KernelSU / APatch
  - 改进的错误处理机制
  - 更高的安全性

### 📦 Restic 全面应用

#### 全面覆盖
- **APK 备份**：现已使用 Restic 去重
- **应用数据备份**：现已使用 Restic 去重
- **文件备份**：现已使用 Restic 去重
- **云同步**：原生支持 Restic

#### 精准恢复功能
- **应用级精度**：可恢复指定应用
- **文件级精度**：支持按文件筛选恢复
- **版本选择**：可选择任意历史版本

### 🎨 用户体验增强

#### 新增 UI 组件
- Restic 恢复列表页：浏览并选择快照备份
- 快照详情页：显示备份类型与进度
- 文件恢复过滤：精准选择待恢复文件
- 恢复进度追踪：实时显示恢复状态

#### 优化交互流程
配置 Restic 仓库 → 浏览快照 → 选择应用/文件版本 → 查看详情 → 一键恢复

### 📊 技术指标（最终版）

| 功能 | 旧版备份 | Restic 备份（全面应用） |  
|------|----------|------------------------|  
| **存储效率** | 基础压缩 | **块级去重 + 压缩** |  
| **增量备份** | 不支持 | **原生支持** |  
| **数据加密** | 无 | **AES-256 加密** |  
| **版本管理** | 文件覆盖 | **基于快照的版本控制** |  
| **云同步** | 需额外实现 | **原生支持** |  
| **恢复粒度** | 批量恢复 | **支持按应用和按文件精确恢复** |  
| **存储占用** | 线性增长 | **节省 60–90% 空间** |  
| **Root 访问** | 自定义实现 | **libsu 集成** |  

### 🛡️ 向后兼容性

- **旧版备份**：完全支持，自动降级处理
- **迁移路径**：从仅压缩模式无缝升级至 Restic 模式
- **API 兼容性**：保留现有恢复工作流

### 🎉 里程碑成果

本次全面迁移实现了：

- **存储革命**：所有备份类型普遍节省 60–90% 存储空间
- **安全升级**：所有数据均采用 AES-256 加密与不可变快照
- **精准控制**：支持按应用、按文件恢复
- **现代 Root 集成**：通过 libsu 提升兼容性
- **完整架构**：所有备份类型均采用统一的双层处理流程

> **DataBackup Revived 现已成为一个完整的现代化数据管理平台，全面采用基于 Restic 的去重机制与 libsu 集成。**

## 截图 – Restic

<div align="center">    
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotsrestic/20251219233244_345_20.png" width="275px">
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotsrestic/20251219233246_347_20.png" width="275px">
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotsrestic/20251219233248_349_20.png" width="275px">    
</div>    

## 截图 – S3

<div align="center">    
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233930_19_20.jpg" width="275px">
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233931_20_20.jpg" width="275px">
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233931_21_20.jpg" width="275px">    
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233932_22_20.jpg" width="275px">
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112233933_23_20.jpg" width="275px">
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshotss3/20251112234045_24_20.jpg" width="275px">    
</div>    

## 截图

<div align="center">    
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/01.jpg" width="275px">
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/02.jpg" width="275px">
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/03.jpg" width="275px">    
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/04.jpg" width="275px">
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/05.jpg" width="275px">
    <img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/06.jpg" width="275px">    
</div>    

## 下载

请从 [Releases](https://github.com/543069760/Android-DataBackup-S3/releases) 获取 APK。

## 翻译

[<img src="https://hosted.weblate.org/widget/databackup/main/open-graph.png" alt="翻译">](https://hosted.weblate.org/engage/databackup/)

## 贡献者

感谢以下所有了不起的贡献者！

[[贡献者](https://contrib.rocks/image?repo=543069760/Android-DataBackup-S3)](https://github.com/543069760/Android-DataBackup-S3/graphs/contributors)

## 支持

如果你喜欢本应用并希望帮助它变得更好，欢迎赞助我！

[<img src="./docs/static/img/pp_h_rgb.svg" alt="PayPal" height="60">](https://paypal.me/XayahSuSuSu)  
[<img src="./docs/static/img/afdian.svg" alt="爱发电" height="60">](https://afdian.net/a/XayahSuSuSu)

## 许可证

[GNU General Public License v3.0](./LICENSE)