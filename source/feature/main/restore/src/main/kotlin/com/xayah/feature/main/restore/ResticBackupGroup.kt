package com.xayah.feature.main.restore

import com.xayah.core.model.restic.ResticBackupApp
import kotlinx.serialization.Serializable

@Serializable
data class ResticBackupGroup(
    val packageName: String,
    val userId: Int,
    val timestamp: Long,
    val backups: List<ResticBackupApp>,
    val appLabel: String
) {
    val snapshotCount: Int get() = backups.size
}