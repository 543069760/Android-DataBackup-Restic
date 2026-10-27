package com.xayah.core.model

/**
 * 进程内实时进度快照。
 *
 * 三个字段分别对应备份卡片上展示的胶囊：
 * - [total]：源读取累积（未去重前的读入量）；
 * - [increment]：去重压缩后的写出累积（实际入库增量）；
 * - [speed]：瞬时速度（B/s）。
 * - [dedupRatio]：累积缩减率 1 - increment/total，[Double.NaN] 表示样本不足（UI 显示 "-"）。
 *
 * 该结构只存在于内存中（由处理服务的 StateFlow 承载），不落 Room、不产生 schema 迁移。
 */
data class ProgressStat(
    val total: Long = 0L,
    val increment: Long = 0L,
    val speed: Long = 0L,
    val dedupRatio: Double = Double.NaN,
) {
    val isEmpty: Boolean
        get() = total <= 0L && increment <= 0L && speed <= 0L
}

/**
 * 比率样本的最小基数：低于该值算出的比率虚高（开局 written=0 会显示 100%），无意义。
 */
private const val MIN_RATIO_BASE_BYTES = 1L * 1024 * 1024

/**
 * 累积缩减率：源读取量到实际入库量的综合缩减（含去重 + 压缩 + pack 元数据开销）。
 *
 * 必须在同一回调帧取 [total]（readBytes）与 [increment]（writtenBytes），
 * 两个计数器单调，故比率平滑收敛；pack flush 可能让瞬时值越界，钳到 [0,1]。
 *
 * @return 累积缩减率（0.0~1.0）；样本不足返回 [Double.NaN]。
 */
fun dedupRatioOf(total: Long, increment: Long): Double =
    if (total > MIN_RATIO_BASE_BYTES) {
        (1.0 - increment.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    } else {
        Double.NaN
    }

/**
 * 进度快照在内存中的索引键：数据项 key + 数据类型，避免同名项互相覆盖。
 */
fun progressStatKey(key: String, dataType: DataType): String = "$key#${dataType.type}"
