package com.xayah.core.work

import android.os.SystemClock

/**
 * 前台服务通知节流。
 *
 * 原来每处理一个包就 createForegroundInfo + setForeground 一次，几百个包就是几百次
 * 通知构建与 Binder 往返；这里限制为最多每 [intervalMs] 一次，且首帧与末帧必达。
 */
internal class ProgressThrottle(private val intervalMs: Long = 200L) {
    private var lastAt = 0L

    fun shouldEmit(cur: Int, max: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (cur == 0 || cur >= max || now - lastAt >= intervalMs) {
            lastAt = now
            return true
        }
        return false
    }
}
