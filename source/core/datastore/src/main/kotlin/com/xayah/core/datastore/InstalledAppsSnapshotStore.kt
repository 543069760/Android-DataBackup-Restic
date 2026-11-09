package com.xayah.core.datastore

import android.content.Context

/**
 * app 侧「已安装应用快照」的轻量状态。
 *
 * 刻意使用 **device-protected storage**：Direct Boot 阶段 CE 存储尚不可读，
 * 若把 digest 放在 CE，解锁后会读到空值（安全地走 FULL）；放在 DE 则任何阶段
 * 都能读出一致的状态，便于区分「真的没变」与「读不到」。
 *
 * 只存标量，不依赖 core/rootservice 的 DTO，避免模块反向依赖。
 */
class InstalledAppsSnapshotStore(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun digest(): String = prefs.getString(KEY_DIGEST, "") ?: ""

    fun isPartial(): Boolean = prefs.getBoolean(KEY_PARTIAL, true)

    /** 上次同步时的「显示系统应用」偏好；null 表示尚无记录。 */
    fun lastLoadSystemApps(): Boolean? =
        if (prefs.contains(KEY_LOAD_SYSTEM_APPS)) prefs.getBoolean(KEY_LOAD_SYSTEM_APPS, false) else null

    /**
     * @param digest 为空表示本次没有可复用的基线（partial / 门禁失败）。
     * @param partial 上次快照是否为「部分可见集合」，true 时下次强制 FULL。
     */
    fun save(digest: String, partial: Boolean, unlockedCsv: String, loadSystemApps: Boolean) {
        prefs.edit()
            .putString(KEY_DIGEST, digest)
            .putBoolean(KEY_PARTIAL, partial)
            .putString(KEY_UNLOCKED, unlockedCsv)
            .putBoolean(KEY_LOAD_SYSTEM_APPS, loadSystemApps)
            .apply()
    }

    /** 收到用户解锁广播时调用：清空基线，下次启动一律走 FULL 重算。 */
    fun markStale() {
        prefs.edit()
            .putString(KEY_DIGEST, "")
            .putBoolean(KEY_PARTIAL, true)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "installed_apps_snapshot"
        private const val KEY_DIGEST = "digest"
        private const val KEY_PARTIAL = "partial"
        private const val KEY_UNLOCKED = "unlocked"
        private const val KEY_LOAD_SYSTEM_APPS = "load_system_apps"
    }
}
