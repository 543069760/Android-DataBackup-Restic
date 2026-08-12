package com.xayah.core.restic

import android.content.Context
import android.util.Log
import com.xayah.core.datastore.readResticCompressionLevel
import kotlinx.coroutines.flow.first

/**
 * rustic 压缩配置桥接工具。
 *
 * 与 Rust 侧 repository.rs 中的常量必须完全一致：
 *   const COMPRESSION_KEY: &str = "__databackup_compression__";
 *
 * 数值语义（"配置层"语义，对应 rustic_core ConfigOptions.set_compression）：
 *   -1 = AUTO  → 不放入 options（不设 set_compression 字段）→ rustic v2 默认压缩
 *    0 = OFF   → set_compression=Some(0) → 关闭压缩
 *  1..22       → 指定 zstd 级别
 *
 * 注意：不要把 -1（默认）写成 0；"默认"必须是"完全不设该字段"。
 */
const val COMPRESSION_KEY = "__databackup_compression__"

/**
 * 读取设置页压缩级别，转换成传给 initRusticRepository 的 options 片段。
 * -1(auto) 返回 emptyMap()（不设字段）；其余（含 0=关闭、1..22=级别）返回带 COMPRESSION_KEY 的 map。
 */
suspend fun Context.resticCompressionOptions(): Map<String, String> {
    val level = readResticCompressionLevel().first()
    val result = if (level == -1) {
        emptyMap()
    } else {
        mapOf(COMPRESSION_KEY to level.toString())
    }
    Log.i("ResticCompression", "resticCompressionOptions level=$level -> $result")
    return result
}