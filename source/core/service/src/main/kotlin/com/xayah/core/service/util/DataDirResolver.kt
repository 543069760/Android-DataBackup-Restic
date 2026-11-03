package com.xayah.core.service.util

import com.xayah.core.model.DataType
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil

/**
 * app 私有数据目录在「app mount namespace」与「init mount namespace」下的等价视图解析。
 *
 * 备份端与恢复端共用，保证两侧的「探测 / 打包 / 解包 / chown / chcon」落在同一个视图上。
 *
 * 实测（ColorOS / Android 15）：libsu RootService 拉起的守护进程（独立 app_process）继承的是
 * app 的 mount namespace（诊断日志里 `same=false`）。该 namespace 下 `/data`、`/data/user`、
 * `/data/data`、`/data/user_de`、`/storage/emulated/0` 这些父目录本身都可见，但**目录项被 ROM
 * 隔离成只含本应用的视图** —— 其他包以及分身用户（`/data/user/999`，它是指向 `/data/data/999`
 * 的符号链接）整体都是 ENOENT 而非 EACCES。
 *
 * `/proc/1/root` 是 init 的根视图，可绕开目录项隔离，可读也可写（写入落到真实 inode，
 * 因此 tar -C、chown -R、chcon -R 都可以直接用这个前缀）。
 */
internal object DataDirResolver {
    /** `/proc/1/root` 是 init 的根视图，可绕开 app mount namespace 的目录项隔离。 */
    private const val INIT_ROOT_PREFIX = "/proc/1/root"

    private const val TAG = "DataDirResolver"

    /** 只有 CE/DE 私有数据需要回退；APK/OBB/MEDIA/DATA 走 `/data/media`，不受该目录项隔离影响。 */
    fun needsFallback(dataType: DataType): Boolean =
        dataType == DataType.PACKAGE_USER || dataType == DataType.PACKAGE_USER_DE

    /**
     * 名义目录不可见时的候选视图，顺序即优先级：
     *   1. `/proc/1/root<原路径>` —— init 的根视图，不在 app 的隔离 namespace 内；
     *   2. `/data/data` —— PACKAGE_USER 的等价挂载（仅主用户）。
     */
    fun fallbackDirs(dataType: DataType, userId: Int): List<String> = buildList {
        when (dataType) {
            DataType.PACKAGE_USER -> {
                add("$INIT_ROOT_PREFIX${PathUtil.getPackageUserDir(userId)}")
                if (userId == 0) add(PathUtil.getDataDataDir())
            }

            DataType.PACKAGE_USER_DE -> add("$INIT_ROOT_PREFIX${PathUtil.getPackageUserDeDir(userId)}")
            else -> {}
        }
    }

    /**
     * 解析出 root 守护进程可见、且其下存在 [packageName] 的父目录。
     *
     * - [primary] 可见 → 原样返回（普通设备 / 未做目录项隔离的 ROM 行为完全不变）；
     * - 否则按 [fallbackDirs] 依次探测，命中即返回该视图；
     * - 全部失败仍返回 [primary]，由调用方维持原有的 Not exist / Cannot open 诊断分支。
     */
    suspend fun resolve(
        primary: String,
        dataType: DataType,
        userId: Int,
        packageName: String,
        rootService: RemoteRootService,
    ): String {
        if (!needsFallback(dataType)) return primary
        if (rootService.exists("$primary/$packageName")) return primary

        for (dir in fallbackDirs(dataType, userId)) {
            if (dir == primary) continue
            if (rootService.exists("$dir/$packageName")) {
                LogUtil.log { TAG to "Dir $primary is invisible (userId=$userId, pkg=$packageName), fallback to $dir." }
                return dir
            }
        }

        LogUtil.log { TAG to "Dir $primary is invisible and no fallback matched (userId=$userId, pkg=$packageName)." }
        return primary
    }
}
