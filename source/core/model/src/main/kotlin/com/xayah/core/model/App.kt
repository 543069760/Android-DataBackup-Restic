package com.xayah.core.model

data class App(
    val id: Long,
    val packageName: String,
    val label: String,
    val preserveId: Long,
    val isSystemApp: Boolean,
    val selectionFlag: Int,
    val selected: Boolean,
    val backupTimestamp: Long = 0L,
    val isProtected: Boolean = false,
    val blocked: Boolean = false,
    var resticSnapshotId: String? = null
)
