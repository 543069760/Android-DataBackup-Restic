package com.xayah.core.model

/**
 * 可选的桌面图标样式，与 AndroidManifest 中的 activity-alias 一一对应。
 *
 * 切换原理：三个别名共享同一个 SplashActivity，任一时刻只启用其中一个组件，
 * 其余保持禁用，以此改变桌面显示的图标。
 */
enum class AppIconType(val id: Int) {
    /** 原始图标 */
    DEFAULT(0),

    /** 设备桥 Device Bridge：两台设备之间的数据迁移 */
    DEVICE_BRIDGE(1),

    /** 安全同步 Shield Sync：盾牌与双向同步环 */
    SHIELD_SYNC(2),
    ;

    companion object {
        fun of(id: Int): AppIconType = values().firstOrNull { it.id == id } ?: DEFAULT
    }
}
