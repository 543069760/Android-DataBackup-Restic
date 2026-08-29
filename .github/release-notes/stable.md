# 🎉 重大版本更新 — DataBackup Revived 3.0.1-Rustic-JNI

本版本完成了备份引擎的**核心迁移**：从旧版的 `tar + zstd` 整包压缩，全面切换到基于 **Rust `rustic_core`（兼容 restic 仓库格式）** 的现代备份引擎，带来**原生增量备份**、块级去重与端到端加密。由于**包名变更**，这是一个独立的应用，需要**重新安装**。
  
---  

## ✨ 核心亮点

- 🧬 **迁移到 rustic（restic）引擎** — 底层备份从 `tar + zstd` 整包压缩换成 Rust `rustic_core`，兼容 restic 仓库格式，更现代、更可靠
- ⚡ **原生增量备份** — 只备份有变化的数据，不再每次整包重来，备份更快、占用更小
- 🧩 **块级去重** — 相同数据块只存一份，节省 **60–90%** 存储空间
- 🔐 **AES-256 加密** — 备份数据原生端到端加密，更安全
- 🕒 **快照式多版本** — 基于快照的版本管理，可随时回退到任意历史版本
- 🔌 **零外部二进制** — `rustic_core` 编译进 `librustic.so`，通过 JNI 直接调用，无需下载/安装/管理独立的 Restic 二进制（也不再受上游二进制在 Android 上的 DNS/动态链接限制）
- ☁️ **云存储 S3 备份** — 支持腾讯云 COS（S3 兼容），以及 FTP / SFTP / WebDAV 协议
- 🌲 **Root 支持** — 兼容 Magisk / KernelSU / APatch，集成 libsu 增强 Root 操作

---  

## ⚠️ 重要提示

- **包名变更**：从 `com.xayah.databackup` 改为 `com.xayah.databackup.revived`
- **无法直接升级**：需要作为独立应用**全新安装**
- **向后兼容**：旧版本创建的备份**不可**在新版本中恢复
- **数据迁移**：建议在安装前**备份重要数据**

---  

## 📥 下载说明

请根据你的设备架构选择对应的 APK：

- `arm64-v8a` — 绝大多数现代手机（推荐）
- `armeabi-v7a` — 较老的 32 位设备
- `x86` / `x86_64` — 模拟器或 x86 设备

> 版本类型：**测试版（Pre-release）** 为不稳定构建，功能可能随时变动调整；追求稳定请选择**正式版**。可在 App「设置 → 更新通道」中切换检测通道。
  
---  

## 🙏 致谢

本项目自 [XayahSuSuSu/Android-DataBackup](https://github.com/XayahSuSuSu/Android-DataBackup) 项目修改而来，感谢原作者的杰出工作。

---

# 🎉 Major Release — DataBackup Revived 3.0.1-Rustic-JNI

This release completes a **core migration** of the backup engine: moving from the old `tar + zstd` whole-archive compression to a modern engine built on **Rust `rustic_core`** (compatible with the restic repository format), bringing **native incremental backups**, block-level deduplication, and end-to-end encryption. Because the **package name has changed**, this is a standalone app and requires a **fresh install**.
  
---  

## ✨ Highlights

- 🧬 **Migrated to the rustic (restic) engine** — the backup core moves from `tar + zstd` whole-archive compression to Rust `rustic_core`, compatible with the restic repository format — more modern and more reliable
- ⚡ **Native incremental backups** — only changed data is backed up instead of repacking everything each time, making backups faster and smaller
- 🧩 **Block-level deduplication** — identical data blocks are stored only once, saving **60–90%** storage space
- 🔐 **AES-256 encryption** — backup data is natively end-to-end encrypted for better security
- 🕒 **Snapshot-based versioning** — snapshot-based version management lets you roll back to any point in history
- 🔌 **Zero external binaries** — `rustic_core` is compiled into `librustic.so` and called directly over JNI, with no need to download, install, or manage a separate Restic binary (and no more of the upstream binary's DNS / dynamic-linking limitations on Android)
- ☁️ **Cloud S3 backup** — supports Tencent Cloud COS (S3-compatible), plus FTP / SFTP / WebDAV protocols
- 🌲 **Root support** — works with Magisk / KernelSU / APatch, with libsu integration for enhanced root operations

---  

## ⚠️ Important Notes

- **Package name changed**: from `com.xayah.databackup` to `com.xayah.databackup.revived`
- **No in-place upgrade**: it must be installed as a **separate app**
- **Backwards Compatibility**: Backups created with older versions **cannot** be restored in this version.
- **Data migration**: back up important data **before installing**

---  

## 📥 Download

Pick the APK that matches your device architecture:

- `arm64-v8a` — most modern phones (recommended)
- `armeabi-v7a` — older 32-bit devices
- `x86` / `x86_64` — emulators or x86 devices

> Release channel: **Pre-release** builds are unstable — features may change at any time. If you want stability, choose the **stable** release. You can switch the detection channel in the app under **Settings → Update Channel**.
  
---  

## 🙏 Acknowledgements

This project is forked from [XayahSuSuSu/Android-DataBackup](https://github.com/XayahSuSuSu/Android-DataBackup). Many thanks to the original author for their outstanding work.