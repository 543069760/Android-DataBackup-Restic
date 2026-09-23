package com.xayah.core.data.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.rootservice.util.withIOContext
import com.xayah.core.util.command.PreparationUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OTG restic 仓库路径解析器（档位 B：config_id 身份 + 扫描重对齐）。
 *
 * 背景：restic 仓库可初始化到 OTG 外接存储 /mnt/media_rw/<UUID>/restic_repo。
 * <UUID> 由文件系统卷标决定，拔插/换盘/格式化会导致挂载点变化，而 DataStore
 * 存的是绝对路径。本类在备份/恢复开始前扫描当前所有外接挂载点，按仓库 config_id
 * 精确定位目标仓库的真实当前路径。
 *
 * 关键约定：
 *  - 仅当 savedPath 为 OTG 前缀（/mnt/media_rw/）时才扫描；非 OTG（内部存储/云端）
 *    直接返回 Passthrough(savedPath)，行为与改造前完全一致。
 *  - “比对一致不更新”只省掉 DataStore 写入与后续重校验；扫描定位这一步
 *    （listExternalStorage + rusticRepositoryConfigId）无法省略，因为不扫描就
 *    无从得知当前真实路径是否变化。
 */
@Singleton
class ResticRepoLocator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
) {
    companion object {
        private const val TAG = "ResticRepoLocator"

        /** OTG 外接存储挂载点前缀，由系统 vold 分配。 */
        const val OTG_MOUNT_PREFIX = "/mnt/media_rw/"

        /** 与 ResticViewModel.initializeOrValidateRepository 保持一致的固定子目录名。 */
        const val RESTIC_REPO_CHILD = "restic_repo"

        fun isOtgPath(path: String): Boolean = path.startsWith(OTG_MOUNT_PREFIX)
    }

    sealed interface ResolveResult {
        /** 唯一命中目标 config_id。changed 表示解析出的路径与 savedPath 是否不同。 */
        data class Matched(val path: String, val changed: Boolean) : ResolveResult

        /** 多个不同挂载点命中同一 id，或存在多个候选需用户选择。 */
        data class Ambiguous(val candidates: List<String>) : ResolveResult

        /** 已插盘扫描但无任何候选匹配目标 id（离线/换盘/仓库不在场）。 */
        data object NotFound : ResolveResult

        /** 非 OTG 路径（内部存储/云端），原样放行，不做任何扫描。 */
        data class Passthrough(val path: String) : ResolveResult
    }

    /**
     * 发现模式的单个结果：扫描到的合法 OTG restic 仓库。
     *
     * @param path     该仓库的绝对路径（<挂载点>/restic_repo）
     * @param configId 该仓库的 config_id（十六进制身份锚点，建库时固定）
     */
    data class DiscoveredRepo(
        val path: String,
        val configId: String,
    )

    /**
     * 单次批次内的短时缓存：key = savedConfigId（null 用空串占位），
     * value = 解析结果。避免同一备份/恢复批次内重复 mount 调用。
     * 批次开始时调用 invalidateCache() 清空。
     */
    private val batchCache = HashMap<String, ResolveResult>()

    /** 备份/恢复批次开始时调用，丢弃上一批次的扫描缓存。 */
    fun invalidateCache() {
        batchCache.clear()
    }

    /**
     * 解析目标 restic 仓库的当前真实路径。
     *
     * @param savedPath   DataStore 里存的旧绝对路径（readResticRepoPath）
     * @param savedConfigId DataStore 里存的目标仓库 config_id（readResticRepoConfigId），
     *                      档位 B 用它做精确身份匹配；为 null 时退化为“扫到任一合法仓库即命中”。
     */
    suspend fun resolveCurrentResticRepoPath(
        savedPath: String,
        savedConfigId: String?,
    ): ResolveResult = withIOContext {
        // 1. 非 OTG 路径：内部存储/云端不受影响，直接放行。
        if (savedPath.isEmpty() || !isOtgPath(savedPath)) {
            return@withIOContext ResolveResult.Passthrough(savedPath)
        }

        // 2. 命中批次缓存直接返回。
        val cacheKey = savedConfigId ?: ""
        batchCache[cacheKey]?.let {
            Log.d(TAG, "resolveCurrentResticRepoPath: hit batch cache -> $it")
            return@withIOContext it
        }

        // 3. 枚举当前所有 /mnt/media_rw/<UUID> 挂载点，拼出各自的 restic_repo 候选路径。
        //    注意：扫描定位无法省略 —— 不扫描就无从得知当前真实路径是否变化。
        val mountPoints = runCatching { PreparationUtil.listExternalStorage().out }
            .getOrElse { e ->
                Log.w(TAG, "listExternalStorage failed: ${e.message}")
                emptyList()
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        Log.d(TAG, "resolveCurrentResticRepoPath: mountPoints=$mountPoints, savedPath=$savedPath, savedConfigId=$savedConfigId")

        // 4. 对每个候选先确认是合法 rustic 仓库，再读 config_id 做精确身份比对。
        //    单个坏候选（打开失败/非仓库）不得中断整轮扫描。
        val matched = mutableListOf<String>()
        for (mount in mountPoints) {
            val candidate = File(mount, RESTIC_REPO_CHILD).absolutePath
            val exists = runCatching { rootService.rusticRepositoryExists(candidate) }
                .getOrElse { e ->
                    Log.w(TAG, "rusticRepositoryExists threw for $candidate: ${e.message}")
                    false
                }
            if (!exists) continue

            if (savedConfigId.isNullOrEmpty()) {
                // 无身份锚点：退化为“扫到任一合法仓库即候选”（档位 A 兜底行为）。
                matched.add(candidate)
                continue
            }

            val configId = runCatching { rootService.rusticRepositoryConfigId(candidate) }
                .getOrElse { e ->
                    Log.w(TAG, "rusticRepositoryConfigId threw for $candidate: ${e.message}")
                    null
                }
            if (configId != null && configId == savedConfigId) {
                matched.add(candidate)
            }
        }

        // 5. 按命中数量归类。
        val result = when {
            matched.isEmpty() -> ResolveResult.NotFound
            matched.size == 1 -> {
                val path = matched.first()
                ResolveResult.Matched(path = path, changed = path != savedPath)
            }
            else -> ResolveResult.Ambiguous(candidates = matched)
        }

        Log.d(TAG, "resolveCurrentResticRepoPath: result=$result")
        batchCache[cacheKey] = result
        result
    }

    /**
     * 发现模式：换新机/全新安装（DataStore 为空、无 savedConfigId）时使用。
     *
     * 与 resolveCurrentResticRepoPath 不同，本方法不依赖已存身份，而是无差别扫描
     * 当前所有 OTG 挂载点上的合法 restic 仓库，返回 (path, configId) 列表，供上层
     * bootstrap 编排：0 个=没插盘/空盘；1 个=可自动登记；多个=需用户选择。
     *
     * rusticRepositoryExists / rusticRepositoryConfigId 只读 config 文件，均不需密码。
     */
    suspend fun discoverOtgRepositories(): List<DiscoveredRepo> = withIOContext {
        val mountPoints = runCatching { PreparationUtil.listExternalStorage().out }
            .getOrElse { e ->
                Log.w(TAG, "discoverOtgRepositories: listExternalStorage failed: ${e.message}")
                emptyList()
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        Log.d(TAG, "discoverOtgRepositories: mountPoints=$mountPoints")

        val discovered = mutableListOf<DiscoveredRepo>()
        for (mount in mountPoints) {
            val candidate = File(mount, RESTIC_REPO_CHILD).absolutePath
            val exists = runCatching { rootService.rusticRepositoryExists(candidate) }
                .getOrElse { e ->
                    Log.w(TAG, "discoverOtgRepositories: rusticRepositoryExists threw for $candidate: ${e.message}")
                    false
                }
            // 逐候选打印：区分“盘上无 restic_repo(exists=false)” vs “有仓库但 config_id 读不出”
            Log.d(TAG, "discoverOtgRepositories: candidate=$candidate exists=$exists")
            if (!exists) continue

            val configId = runCatching { rootService.rusticRepositoryConfigId(candidate) }
                .getOrElse { e ->
                    Log.w(TAG, "discoverOtgRepositories: rusticRepositoryConfigId threw for $candidate: ${e.message}")
                    null
                }
            // 打印原始 config_id：为空/null=读取失败；全 0=native 返回退化值
            Log.d(TAG, "discoverOtgRepositories: candidate=$candidate configId=$configId")
            if (configId.isNullOrEmpty()) continue

            discovered.add(DiscoveredRepo(path = candidate, configId = configId))
        }

        Log.d(TAG, "discoverOtgRepositories: discovered=$discovered")
        discovered
    }

    /**
     * OTG 挂载/拔插事件流，只负责"发信号"，不做扫描。
     *
     * 订阅方（如首页 IndexViewModel）收到 Unit 后自行触发 detectOtgRepository() 重扫，
     * 从而摆脱"回到前台才刷新"的被动模式，实现插盘/拔盘即时更新 UI。
     *
     * 注册两组广播：
     *  (a) 存储介质挂载/卸载/弹出 —— MEDIA_* 系受保护系统广播，需 addDataScheme("file")。
     *  (b) USB 设备插拔 —— UsbManager.ACTION_USB_DEVICE_ATTACHED/DETACHED，
     *      USB 广播不能带 dataScheme，必须单独用一个 IntentFilter 注册。
     *
     * 进入时先 trySend(Unit) 发一次初始信号，保证首次订阅即触发一轮扫描。
     */
    fun otgMountEvents(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                Log.d(TAG, "otgMountEvents: received ${intent?.action}")
                trySend(Unit)
            }
        }

        // (a) 存储介质挂载/卸载/弹出，需带 file scheme
        val mediaFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        // (b) USB 设备插拔，不能带 dataScheme，单独注册
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+：显式指定不导出，避免隐式广播注册异常。
            // MEDIA_* 为受保护系统广播，USB 自定义 filter 也统一带上该标志。
            context.registerReceiver(receiver, mediaFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(receiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, mediaFilter)
            context.registerReceiver(receiver, usbFilter)
        }

        // 首次订阅即发一次初始信号，触发首轮扫描。
        trySend(Unit)

        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
                .onFailure { Log.w(TAG, "otgMountEvents: unregisterReceiver failed: ${it.message}") }
        }
    }
}