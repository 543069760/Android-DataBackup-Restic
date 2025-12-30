package com.xayah.feature.main.restore

import com.xayah.core.model.restic.ResticBackupFiles
import kotlinx.serialization.Serializable

@Serializable
data class ResticFileBackupGroup(
    val mediaName: String,
    val fullPath: String,        // 完整路径确保唯一性
    val timestamp: Long,
    val backups: List<ResticBackupFiles>,  // 使用 ResticBackupFiles
    val mediaLabel: String = mediaName
) {
    val snapshotCount: Int get() = backups.size  // 计算属性
}