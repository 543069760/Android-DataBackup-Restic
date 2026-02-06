package com.xayah.core.model.restic

import com.xayah.core.model.DataType
import kotlinx.serialization.Serializable

@Serializable
data class ResticBackupApp(
    val packageName: String,
    val userId: Int,
    val timestamp: Long,
    val dataType: DataType,
    val snapshotId: String,
    val snapshotTime: String,
    val tags: List<String>,
    val totalBytesProcessed: Long = 0
)