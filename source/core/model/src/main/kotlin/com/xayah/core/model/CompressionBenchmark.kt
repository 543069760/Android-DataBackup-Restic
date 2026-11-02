package com.xayah.core.model

/**
 * 单个压缩档位的一次测量结果。
 *
 * 口径（与生产备份日志一致，便于横向对照）：
 * - [fedBytes] 是 tar 实际喂给 rustic 的字节数（来自 `--totals` 的 `Total bytes written:`）
 * - [dataBytes] 只统计 `<repo>/data` 下的纯 pack 字节，不含 index/config/snapshots
 *   （后三者与档位无关，计入会稀释档位之间的差异）
 * - [elapsedMs] 只包含 `backupTarToRustic` 一次调用，不含建库 / validate / 统计 / 删库
 */
data class BenchmarkSample(
    val level: Int,
    val fedBytes: Long = 0L,
    val dataBytes: Long = 0L,
    val elapsedMs: Long = 0L,
    val tarCode: Int = -1,
    val reliable: Boolean = false,
    val note: String = "",
) {
    /** 展示吞吐（MiB/s）。含约 600 ms 固定开销，因此是系统性低估，越低的档位低估越多。 */
    val throughputMBps: Double
        get() = if (elapsedMs > 0) fedBytes / 1024.0 / 1024.0 / (elapsedMs / 1000.0) else 0.0

    /** 压缩比 = 压缩后 / 原始。OFF 档应≈1.0（AEAD 不改变长度）。 */
    val ratio: Double
        get() = if (fedBytes > 0) dataBytes.toDouble() / fedBytes else 0.0
}

/**
 * 一次完整基准（5 档串行）的产物，整体以 JSON 落到 Preferences DataStore。
 *
 * 设备指纹用于失效判定：换机后结果不可用；主版本变化只提示不强制；
 * [measuredAt] 超过 90 天视为"结果较旧"。
 */
data class BenchmarkReport(
    val version: Int = 1,
    val measuredAt: Long = 0L,
    val deviceModel: String = "",
    val socModel: String = "",
    val appVersionName: String = "",
    val corpusBytes: Long = 0L,
    val charging: Boolean = false,
    val batteryPercent: Int = 0,
    val samples: List<BenchmarkSample> = emptyList(),
    /** -1 = 无法推荐（无有效样本或所有档位收益都不到 10%）。 */
    val recommendedLevel: Int = -1,
) {
    val validSamples: List<BenchmarkSample> get() = samples.filter { it.reliable }

    /** 是否有任何有效样本；全档失败时不覆盖已有报告。 */
    val hasAnyValid: Boolean get() = validSamples.isNotEmpty()
}
