package com.xayah.core.model.restic

import com.xayah.core.model.DataType
import kotlinx.serialization.Serializable

@Serializable
data class ResticBackupFiles(
    val mediaName: String,
    val fullPath: String,
    val timestamp: Long,
    val dataType: DataType,
    val snapshotId: String,
    val snapshotTime: String,
    val tags: List<String>
)