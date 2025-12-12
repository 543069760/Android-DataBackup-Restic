package com.xayah.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ResticProgressState(
    val filesFinished: Long = 0,
    val filesTotal: Long = 0,
    val filesSkipped: Long = 0,
    val bytesWritten: Long = 0,
    val bytesTotal: Long = 0,
    val bytesSkipped: Long = 0,
    val percentage: Float = 0f,
    val speed: String = "0 B/s",
    val timeElapsed: String = "00:00",
    val currentDataTypeIndex: Int = 0,
    val totalDataTypes: Int = 6
) {
    val progressText: String
        get() = "$filesFinished/$filesTotal files"

    val sizeText: String
        get() = "${bytesWritten.formatSize()}/${bytesTotal.formatSize()}"

    private fun Long.formatSize(): String {
        return when {
            this < 1024 -> "$this B"
            this < 1024 * 1024 -> "${this / 1024} KiB"
            this < 1024 * 1024 * 1024 -> "${this / (1024 * 1024)} MiB"
            else -> "${this / (1024 * 1024 * 1024)} GiB"
        }
    }
}