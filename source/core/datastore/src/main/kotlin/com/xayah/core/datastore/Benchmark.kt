package com.xayah.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.xayah.core.model.BenchmarkReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 压缩档位基准报告存储。
 *
 * 沿用既有 Preferences DataStore（与 restic 压缩级别同库），Gson 序列化：
 * 不新增 Room 表、无数据库迁移，报告丢失也无副作用（重测即可）。
 */
val KeyCompressionBenchmark = stringPreferencesKey("restic_compression_benchmark")

private val benchmarkGson = Gson()

/** 读取最近一次报告；从未测过或反序列化失败时返回 null。 */
fun Context.readCompressionBenchmark(): Flow<BenchmarkReport?> = dataStore.data.map { preferences ->
    preferences[KeyCompressionBenchmark]?.let { raw ->
        runCatching { benchmarkGson.fromJson(raw, BenchmarkReport::class.java) }.getOrNull()
    }
}

suspend fun Context.readCompressionBenchmarkOnce(): BenchmarkReport? = readCompressionBenchmark().first()

/** 保存报告；传 null 表示清除（调试/重测入口用）。 */
suspend fun Context.saveCompressionBenchmark(report: BenchmarkReport?) {
    dataStore.edit { settings ->
        if (report == null) {
            settings.remove(KeyCompressionBenchmark)
        } else {
            settings[KeyCompressionBenchmark] = benchmarkGson.toJson(report)
        }
    }
}
