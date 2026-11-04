package com.xayah.core.service.util

import android.content.Context
import android.view.SurfaceControlHidden
import com.xayah.core.datastore.ConstantUtil.DEFAULT_IDLE_TIMEOUT
import com.xayah.core.datastore.readScreenOffTimeout
import com.xayah.core.datastore.saveScreenOffTimeout
import com.xayah.core.rootservice.service.RemoteRootService
import kotlinx.coroutines.flow.first

/**
 * 撤销「自动息屏」留下的副作用：把任务开始时备份的系统超时写回，再点亮屏幕。
 *
 * 正常收尾由 `postProcessing()` 调用；取消、OTG 断线这类不走 `postProcessing()` 的退出路径必须
 * 自己调一次 —— 息屏现在是「真正睡眠」，不还原就会一直黑着，只能靠用户按电源键才能出来。
 *
 * 幂等：只有备份值（> [DEFAULT_IDLE_TIMEOUT]）还在时才回写并清空，重复调用不会拿默认值覆盖
 * 用户真实的系统设置（开关关闭时该值恒为 [DEFAULT_IDLE_TIMEOUT]，直接跳过）。
 */
suspend fun RemoteRootService.restoreScreenState(context: Context) {
    val original = runCatching { context.readScreenOffTimeout().first() }.getOrDefault(DEFAULT_IDLE_TIMEOUT)
    if (original > 0) {
        setScreenOffTimeout(original)
        runCatching { context.saveScreenOffTimeout(DEFAULT_IDLE_TIMEOUT) }
    }
    setDisplayPowerMode(SurfaceControlHidden.POWER_MODE_NORMAL, allowRiskyPath = true)
}
