package com.xayah.core.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.Log
import com.xayah.core.model.AppIconType

/**
 * 桌面图标切换。
 *
 * 每个 [AppIconType] 对应 AndroidManifest 中一个 activity-alias（共同指向 SplashActivity），
 * 通过「启用目标 + 禁用其余」改变桌面上显示的图标。任一时刻必须恰好有一个别名处于启用态，
 * 否则桌面会出现多个入口或丢失入口。
 */
object AppIconUtil {

    private const val TAG = "AppIconUtil"
    private const val ALIAS_CLASS_PREFIX = "com.xayah.databackup.SplashActivity"

    /**
     * 图标样式对应的启动组件完整类名（均为 activity-alias）。
     *
     * 与 Telegram 一致：真正的 SplashActivity 不带 LAUNCHER，桌面入口全部由别名提供，
     * 因此切换时只在三个别名之间启停，绝不改动 SplashActivity 自身的启用状态。
     */
    val AppIconType.aliasClassName: String
        get() = when (this) {
            AppIconType.DEFAULT -> "${ALIAS_CLASS_PREFIX}Default"
            AppIconType.DEVICE_BRIDGE -> "${ALIAS_CLASS_PREFIX}Bridge"
            AppIconType.SHIELD_SYNC -> "${ALIAS_CLASS_PREFIX}Shield"
        }

    fun getComponent(context: Context, type: AppIconType): ComponentName =
        ComponentName(context, type.aliasClassName)

    /**
     * 启用 [type] 对应的别名并禁用其余别名。
     *
     * 先启用再禁用，避免中间出现「没有任何 launcher 入口」的空窗导致桌面图标被短暂移除。
     */
    fun apply(context: Context, type: AppIconType) {
        val pm = context.packageManager
        runCatching {
            pm.setComponentEnabledSetting(
                getComponent(context, type),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { Log.e(TAG, "Failed to enable ${type.aliasClassName}", it) }

        AppIconType.values().filter { it != type }.forEach { other ->
            runCatching {
                pm.setComponentEnabledSetting(
                    getComponent(context, other),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }.onFailure { Log.e(TAG, "Failed to disable ${other.aliasClassName}", it) }
        }
    }

    /** 图标样式对应的 mipmap 资源名。 */
    private val AppIconType.mipmapName: String
        get() = when (this) {
            AppIconType.DEFAULT -> "ic_launcher"
            AppIconType.DEVICE_BRIDGE -> "ic_launcher_bridge"
            AppIconType.SHIELD_SYNC -> "ic_launcher_shield"
        }

    /**
     * 读取某个图标样式实际会展示的图标，用于设置页预览。
     *
     * 优先按资源名加载 mipmap：三个样式里只有当前启用的是 enabled，其余都是 disabled，
     * 而 PackageManager 默认不会解析已禁用组件——用它取图只会拿到「当前那一个」，
     * 其余返回 null 表现为空白方块。直接读资源则与启用状态无关。
     */
    fun getIcon(context: Context, type: AppIconType): Drawable? {
        val id = context.resources.getIdentifier(type.mipmapName, "mipmap", context.packageName)
        if (id != 0) {
            runCatching { context.resources.getDrawable(id, context.theme) }
                .onFailure { Log.e(TAG, "Failed to load mipmap ${type.mipmapName}", it) }
                .getOrNull()
                ?.let { return it }
        }
        return runCatching {
            context.packageManager.getActivityIcon(getComponent(context, type))
        }.onFailure {
            Log.e(TAG, "Failed to load icon of ${type.aliasClassName}", it)
        }.getOrNull()
    }

    /**
     * 切换图标后立刻退出应用。
     *
     * 桌面图标由 launcher 缓存，进程存活期间它不会感知别名变化；结束进程后 launcher
     * 才会重新加载组件信息并换成新图标。
     */
    fun exitApp(context: Context) {
        runCatching { context.getActivity()?.finishAffinity() }
        Process.killProcess(Process.myPid())
    }
}
