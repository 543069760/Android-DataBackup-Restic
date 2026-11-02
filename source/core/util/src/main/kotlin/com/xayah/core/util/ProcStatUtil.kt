package com.xayah.core.util

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.Locale

/**
 * `/proc/<pid>/stat` 的 CPU 时间读取器，用于 FD 备份链路（tar → pipe → rustic）的 CPU 归因。
 *
 * 背景：`[FdBackup] progress` 的速率曲线只能证明"消费端限流"，分不开 CPU 与 IO。
 * 这里把「进程 utime+stime 增量 ÷ 墙钟」换算成"占用核数"，一眼可定性：
 *   - ≈1.0 → 单核打满，CPU bound（rustic 的分块/压缩/加密在喂数据的线程里串行完成）
 *   - >1.0 → 多核并行
 *   - 远小于 1 → 瓶颈不在 CPU，去查 IO / fsync
 *
 * 注意必须读**真正干活的那个进程**：FD 备份的 CPU 在 root 守护进程里，
 * app 主进程只转发 AIDL 进度回调，读它的 /proc/self/stat 恒接近 0，会掩盖瓶颈。
 */
object ProcStatUtil {
    /**
     * Linux 用户态时钟频率（jiffies 每秒）。Android 上恒为 100，取不到时按 100 兜底。
     */
    private val clkTck: Long by lazy {
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L)
    }

    /**
     * 读取进程累计 CPU 时间（utime + stime），单位 tick；读取失败返回 -1。
     *
     * stat 格式为 `pid (comm) state ppid ... utime stime ...`，其中 comm 用括号包裹、
     * 可能含空格或括号，所以从**最后一个** ')' 之后切分。切分后 state 是 index 0（原始第 3 字段），
     * 于是 utime（第 14 字段）落在 index 11、stime（第 15 字段）落在 index 12。
     */
    fun cpuTicks(pid: Int = 0): Long {
        val path = if (pid <= 0) "/proc/self/stat" else "/proc/$pid/stat"
        return runCatching {
            val fields = File(path).readText().substringAfterLast(')').trim().split(' ')
            fields[11].toLong() + fields[12].toLong()
        }.getOrDefault(-1L)
    }

    fun ticksToSeconds(ticks: Long): Double = ticks.toDouble() / clkTck

    /** tick 增量在 [seconds] 墙钟内等效占用多少核。 */
    fun cores(ticks: Long, seconds: Double): Double =
        if (seconds <= 0.0) 0.0 else ticksToSeconds(ticks) / seconds
}

/**
 * 按固定间隔把进程 CPU 增量换算成"占用核数"并格式化一行日志。
 *
 * 用法：在进度回调里反复调 [maybeLog]，内部按 [intervalMs] 节流，未到间隔返回 null。
 * 判据见 [ProcStatUtil] 的类注释。
 */
class CpuCoreSampler(
    private val pid: Int = 0,
    private val intervalMs: Long = 1_000L,
) {
    private val startAt = SystemClock.elapsedRealtime()
    private val startTicks = ProcStatUtil.cpuTicks(pid)
    private var lastAt = startAt
    private var lastTicks = startTicks

    /** 返回格式化后的一行（不含 tag/级别），未到采样间隔或读取失败时返回 null。 */
    fun maybeLog(): String? {
        val now = SystemClock.elapsedRealtime()
        val spanMs = now - lastAt
        if (spanMs < intervalMs) return null
        val ticks = ProcStatUtil.cpuTicks(pid)
        if (ticks < 0 || lastTicks < 0 || startTicks < 0) return null
        val totalSpan = (now - startAt).toDouble() / 1000.0
        val inst = ProcStatUtil.cores(ticks - lastTicks, spanMs.toDouble() / 1000.0)
        val avg = ProcStatUtil.cores(ticks - startTicks, totalSpan)
        val totalCpu = ProcStatUtil.ticksToSeconds(ticks - startTicks)
        lastAt = now
        lastTicks = ticks
        return String.format(
            Locale.US,
            "t=%.1fs cores=%.2f avg=%.2f cpu=%.2fs",
            totalSpan, inst, avg, totalCpu,
        )
    }
}
