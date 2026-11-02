package com.xayah.core.restic

/**
 * rustic 写入路径自验证（extra_verify）开关的桥接常量。
 *
 * 与 Rust 侧 repository.rs 中的常量必须完全一致：
 *   const EXTRA_VERIFY_KEY: &str = "__databackup_extra_verify__";
 *
 * 语义：
 *   - 建库时不带该 key → 保持 rustic 默认 true（每写一个 blob 先解密+解压+逐字节比对，写端 CPU 接近翻倍）
 *   - "0"/"false"       → config.set_extra_verify = Some(false)，关闭自验证
 *   - 其它值            → config.set_extra_verify = Some(true)，显式开启
 *
 * 该 key 属于"保留 key"，只能出现在 init 的 options 里；
 * Rust 侧 backends()/backends_with_progress() 在构造后端前会把它过滤掉，
 * 因此它不会污染 opendal/rclone 的后端配置。
 */
const val EXTRA_VERIFY_KEY = "__databackup_extra_verify__"

/**
 * 建库时合并进 `initRusticRepository` 的 options 片段：关闭写入自验证。
 *
 * 关闭后丢失的仅是"加密压缩后在内存里自校验"这一层保护（防的是进程内 crypto/zstd bug
 * 或内存翻转，概率极低）；数据完整性仍由 SHA-256 内容寻址 + Poly1305 AEAD tag 在读路径兜底。
 * 只影响写入路径，已有快照、restore、check 完全不受影响。
 */
fun resticExtraVerifyOptions(): Map<String, String> = mapOf(EXTRA_VERIFY_KEY to "0")
