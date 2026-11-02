package com.xayah.feature.main.dashboard

import android.content.Intent
import android.util.Log
import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.data.repository.ResticRepoLocator
import com.xayah.core.data.repository.ResticRepoLocator.ResolveResult
import com.xayah.core.datastore.readKeepAliveHintShown
import com.xayah.core.datastore.readLastBackupTime
import com.xayah.core.datastore.saveKeepAliveHintShown
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoConfigId
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.datastore.readUpdateChannel
import com.xayah.core.datastore.saveResticPassword
import com.xayah.core.datastore.saveResticRepoConfigId
import com.xayah.core.datastore.saveResticRepoPath
import com.xayah.core.datastore.readResticOtgRepoConfigId
import com.xayah.core.datastore.readResticOtgRepoPath
import com.xayah.core.datastore.saveResticOtgRepoPath
import com.xayah.core.datastore.readResticOtgPassword
import com.xayah.core.datastore.saveResticOtgRepoConfigId
import com.xayah.core.datastore.saveResticOtgPassword
import com.xayah.core.model.database.DirectoryEntity
import com.xayah.core.network.model.Release
import com.xayah.core.network.retrofit.GitHubRepository
import com.xayah.core.restic.ResticRepository
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.command.KeepAlive
import com.xayah.core.util.command.KeepAliveStatus
import com.xayah.core.util.command.PreparationUtil
import com.xayah.core.util.toBrowser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject

data class IndexUiState(
    val latestRelease: Release? = null,
    /** 本地 code 与最新 code 之间的全部 release（含最新），按 code 升序（旧→新） */
    val newerReleases: List<Release> = emptyList(),
) : UiState

sealed class IndexUiIntent : UiIntent {
    data object Update : IndexUiIntent()
    data class ToBrowser(val context: Context, val url: String) : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val directoryRepo: DirectoryRepository,
    private val githubRepo: GitHubRepository,
    private val rootService: RemoteRootService,
    private val resticRepoLocator: ResticRepoLocator,
    private val resticRepo: ResticRepository,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(IndexUiState(latestRelease = null)) {

    /**
     * 首页 OTG 仓库发现状态：
     *  - None：未插 OTG / 已登记但当前未插该盘，不显示任何 OTG UI。
     *  - Registered：仓库已就绪（已登记盘已插入，或未登记盘默认密码可解已静默登记），显示 OTG 容量卡片。
     *  - NeedsSetup：发现了仓库但默认密码不可解或多盘歧义，显示引导卡片，点击去设置页由 bootstrap 编排接手。
     *  - NotInitialized：插了 OTG 盘但盘上没有 restic 仓库，显示"未初始化"胶囊卡，点击去设置页选路径初始化。
     */
    sealed class OtgDiscoveryState {
        data object None : OtgDiscoveryState()
        data object NeedsReconnect : OtgDiscoveryState()
        data class Registered(
            val usedBytes: Long,
            val totalBytes: Long,
            val backupUsedBytes: Long,
            val fsType: String? = null,
        ) : OtgDiscoveryState()
        data object NeedsSetup : OtgDiscoveryState()
        data object NotInitialized : OtgDiscoveryState()
    }

    init {
        // 事件驱动:OTG 插拔 / 存储挂载变化时自动重扫,替代回前台重触发 Update
        launchOnIO {
            resticRepoLocator.otgMountEvents()
                .flowOnIO()
                .collect {
                    runCatching { detectOtgRepository() }
                }
        }
    }

    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.Update -> {
                // 存量用户的后台存活引导：放在网络请求之前，避免被更新检查拖慢
                ensureKeepAliveThenHint()
                // 全量刷新：update() 末尾对"无选中目录"会调用 resetDir() 选中 DEFAULT_PATH，
                // 使 querySelectedByDirectoryTypeFlow() 返回非空，directoryState 非空后存储卡片即显示，
                // childUsedBytes 填为 rustic 仓库目录大小
                directoryRepo.update()
                // 计算临时缓存（恢复中转目录）大小，与设置页缓存管理一致
                runCatching {
                    _cacheSize.value = rootService.calculateSize("${context.localBackupSaveDir()}/restore")
                }
                // OTG 仓库发现 / 已登记盘重定位
                runCatching {
                    detectOtgRepository()
                }
                runCatching {
                    // 0 = 正式版通道, 1 = 测试版通道
                    val channel = context.readUpdateChannel().first()
                    val releases = githubRepo.getReleases()

                    // code > 本地 VERSION_CODE 的全部 release，按 code 升序（旧→新）。
                    // 本地 APK 的 BASE_CODE 起始点不同，不能用 remoteCode - localCode 推
                    // commit 区间；release 粒度与 parseReleaseCode 检测口径一致。
                    val newer = releases
                        .mapNotNull { r -> parseReleaseCode(r).takeIf { it > Long.MIN_VALUE }?.let { r to it } }
                        .filter { it.second > BuildConfigUtil.VERSION_CODE }
                        .sortedByDescending { it.second }   // 新 → 旧
                        .map { it.first }

                    val release: Release? = if (channel == 1) {
                        // 测试版通道：取区间内 code 最大者（含 prerelease）
                        newer.firstOrNull()
                    } else {
                        // 正式版通道保持 latest 语义（latest 接口不含 prerelease）
                        githubRepo.getLatestRelease()
                            .takeIf { parseReleaseCode(it) > BuildConfigUtil.VERSION_CODE }
                    }

                    emitState(state.copy(
                        latestRelease = release,
                        // 只有确认有更新时才带更新记录，否则清空
                        newerReleases = if (release != null) newer else emptyList(),
                    ))
                }
            }

            is IndexUiIntent.ToBrowser -> {
                runCatching { intent.context.toBrowser(intent.url) }.onFailure { emitEffect(IndexUiEffect.ShowSnackbar(message = context.getString(R.string.no_browser))) }
            }
        }
    }

    /**
     * 首页 OTG 探测。分两种情形：
     *
     * A. 已登记（readResticRepoConfigId 非空）：按 config_id 重定位当前挂载点。
     *    - Matched：插着目标盘 → 路径漂移则静默改指 → 显示 Registered 容量卡（本次预期核心）。
     *    - NotFound / Ambiguous / Passthrough：当前没插该 OTG 盘（或配置在内部存储）→ None，不显示。
     *
     * B. 未登记（换新机/全新安装，configId 为空）：无差别发现 + 默认密码静默登记。
     *    - 0 个 → None；1 个且默认密码可解 → 静默登记 + Registered；
     *      1 个但默认密码不可解 / 多盘 → NeedsSetup，引导去设置页。
     *
     * 全程无任何 saveResticRepoPath("")，发现失败/异常不清空既有配置。
     */
    private suspend fun detectOtgRepository() {
        // 每次刷新丢弃上一批扫描缓存，确保拔插状态实时反映
        resticRepoLocator.invalidateCache()

        // 情形 A：已登记 OTG 仓库——直接读独立 OTG 路径键，对精确路径校验，
        // 与设置页 refreshOtgStatus 完全一致（不依赖 config_id，因 native 恒返回全 0）
        val otgPath = context.readResticOtgRepoPath().orEmpty()
        if (otgPath.isNotEmpty()) {
            val password = context.readResticOtgPassword() ?: "databackup_default"
            val ok = runCatching {
                resticRepo.checkRepository(otgPath, password)
            }.getOrDefault(false)
            if (ok) {
                // 仓库在场且可校验 → 显示容量卡（buildRegisteredState 按分区根取 statFs，嵌套路径正确）
                Log.d("DashboardOtg", "detectOtgRepository[A]: otgPath=$otgPath checkOk=true → Registered")
                _otgDiscoveryState.value = buildRegisteredState(otgPath)
            } else {
                // 校验失败要区分"盘不在场"与"盘在场但仓库被删/密码不符"，
                // 否则未插/拔盘也会弹红条，且红条文案"发现OTG设备中存在仓库"此时语义矛盾。
                val hasDisk = runCatching { directoryRepo.hasLiveExternalStorage() }.getOrDefault(false)
                Log.d("DashboardOtg", "detectOtgRepository[A]: otgPath=$otgPath checkOk=false hasDisk=$hasDisk")
                _otgDiscoveryState.value = if (hasDisk) {
                    // 盘在场但仓库不可校验（被删/换盘/密码不符）→ 红条引导重连/重选
                    OtgDiscoveryState.NeedsReconnect
                } else {
                    // 盘不在场 → 首页不显示任何 OTG UI（红条消失，歧义解决）
                    OtgDiscoveryState.None
                }
            }
            return
        }

        // 情形 B：未登记设备——无差别发现 + 默认密码静默登记（保持原逻辑）
        val discovered = resticRepoLocator.discoverOtgRepositories()
        when {
            discovered.isEmpty() -> {
                // 扫不到仓库，再判是否"实时"插了盘：插了盘但无仓库 → 引导初始化；没插盘 → 不显示
                val hasDisk = runCatching { directoryRepo.hasLiveExternalStorage() }.getOrDefault(false)
                if (hasDisk) {
                    Log.d("DashboardOtg", "detectOtgRepository: 插了 OTG 盘但无仓库 → NotInitialized")
                    _otgDiscoveryState.value = OtgDiscoveryState.NotInitialized
                } else {
                    Log.d("DashboardOtg", "detectOtgRepository: 未插 OTG 盘 → None")
                    _otgDiscoveryState.value = OtgDiscoveryState.None
                }
            }

            discovered.size == 1 -> {
                val repo = discovered.first()
                val defaultPassword = context.readResticOtgPassword() ?: "databackup_default"
                val valid = resticRepo.validateRepository(repo.path, defaultPassword)
                if (valid) {
                    // 默认密码可解：零交互静默登记
                    context.saveResticOtgRepoPath(repo.path)
                    context.saveResticOtgRepoConfigId(repo.configId)
                    context.saveResticOtgPassword(defaultPassword)
                    _otgDiscoveryState.value = buildRegisteredState(repo.path)
                } else {
                    // 默认密码不可解（用了自定义密码）：引导去设置页输入密码
                    _otgDiscoveryState.value = OtgDiscoveryState.NeedsSetup
                }
            }

            else -> {
                // 多盘歧义：首页不弹选择框，引导去设置页
                _otgDiscoveryState.value = OtgDiscoveryState.NeedsSetup
            }
        }
    }

    /**
     * 构造 Registered 容量数据（分区维度）：
     *  - 分区容量：readStatFs(OTG 分区根 /mnt/media_rw/<UUID>)；
     *    usedBytes = totalBytes - availableBytes。
     *  - 仓库占用：calculateSize(repoPath)，只统计 restic 仓库目录本身。
     * repoPath 是在 /mnt/media_rw/<UUID> 内任意层级选出的仓库路径，
     * 因此不能用 File(repoPath).parent（可能落到 /mnt/media_rw 系统层或某个子目录），
     * 而是稳定截出 /mnt/media_rw/ 之后的第一段作为分区根。
     */
    private suspend fun buildRegisteredState(repoPath: String): OtgDiscoveryState.Registered {
        // 分区根：/mnt/media_rw/<UUID>；非 OTG/异常路径回退到原 parent 逻辑，保证零回归
        val partitionRoot = if (repoPath.startsWith("/mnt/media_rw/")) {
            "/mnt/media_rw/" + repoPath.removePrefix("/mnt/media_rw/").substringBefore('/')
        } else {
            File(repoPath).parent ?: repoPath
        }
        val statFs = runCatching { rootService.readStatFs(partitionRoot) }.getOrNull()
        val totalBytes = statFs?.totalBytes ?: 0L
        val availableBytes = statFs?.availableBytes ?: 0L
        val usedBytes = (totalBytes - availableBytes).coerceAtLeast(0L)
        val backupUsedBytes = runCatching { rootService.calculateSize(repoPath) }.getOrDefault(0L)
        // 解析分区真实文件系统类型（blkid 解 fuseblk 背后的真实 fs，取不到回退 mount 类型），
        // 与设置页 listOtgPartitions 获取 fsType 的口径保持一致
        val fsType = runCatching {
            PreparationUtil.getExternalStorageRealType(partitionRoot).out.firstOrNull { it.isNotBlank() }
        }.getOrNull()
        return OtgDiscoveryState.Registered(
            usedBytes = usedBytes,
            totalBytes = totalBytes,
            backupUsedBytes = backupUsedBytes,
            fsType = fsType,
        )
    }

    private fun parseReleaseCode(release: Release): Long {
        release.tagName.substringAfterLast('.', "").toLongOrNull()?.let { return it }
        Regex("Build\\s+(\\d+)").find(release.name)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        return Long.MIN_VALUE
    }

    /**
     * 存量用户的后台存活引导。
     *
     * 升级不会重走初始化向导（CI 只递增 versionCode，versionName 恒定，SplashActivity 的比较永不成立），
     * 所以老用户可能一直是未加固状态。这里在首页刷新时补一次检测：
     *
     * 1. 只有明确查到未加固（[KeepAliveStatus.Unoptimized]）才提示；
     * 2. [KeepAliveStatus.Optimized] / [KeepAliveStatus.Unsupported]（ROM 查不出来）一律不打扰；
     * 3. 这里只读不写 —— 修复动作交给向导页（root 就绪后会自动加固），避免「提示判定」与「静默自愈」互相竞态。
     *
     * 「已提示」标记不是弹出时就写，而是等用户真的处理掉这条 Snackbar（点「去修复」或点关闭）才写：
     * 用户没理它就直接退出的话，下次启动会再提醒一次，不会被静默吞掉。
     */
    private fun ensureKeepAliveThenHint() {
        // 每个 ViewModel 实例只检测一次：Update 由首次进入 + 每次回前台触发，
        // 重复 query 会反复启动 root shell，没有意义。
        if (keepAliveHintChecked) return
        keepAliveHintChecked = true

        // emitEffect 里的 showSnackbar 会挂起到用户处理掉提示为止，
        // 不能占用 Update intent 的协程，否则后续的目录/容量/OTG 刷新会一起卡住。
        launchOnIO {
            // 读不到标记时按「已提示」处理，宁可少弹也不要反复打扰
            if (runCatching { context.readKeepAliveHintShown() }.getOrDefault(true)) return@launchOnIO

            val status = runCatching { KeepAlive.query(context.packageName) }
                .getOrDefault(KeepAliveStatus.Unsupported)
            if (status != KeepAliveStatus.Unoptimized) return@launchOnIO

            emitEffect(
                IndexUiEffect.ShowSnackbar(
                    message = context.getString(R.string.keep_alive_hint),
                    type = SnackbarType.Warning,
                    actionLabel = context.getString(R.string.keep_alive_hint_action),
                    // 带 action 的 Snackbar 用 Indefinite：用户不点就一直留着，避免错过引导；
                    // 同时给关闭按钮，符合 M3E 对 Indefinite + dismiss action 的建议。
                    withDismissAction = true,
                    duration = SnackbarDuration.Indefinite,
                    // 点「去修复」和点关闭都算用户已经处理过这条提示，此后不再打扰；
                    // 两者都不做（直接退出 App）则不写标记，下次启动会再提醒。
                    onActionPerformed = {
                        markKeepAliveHintShown()
                        openSetupWizard()
                    },
                    onDismissed = { markKeepAliveHintShown() },
                )
            )
        }
    }

    /** 记录「后台存活提示已被用户处理」，由 Snackbar 的回调在用户点动作/关闭时调用。 */
    private suspend fun markKeepAliveHintShown() {
        runCatching { context.saveKeepAliveHintShown(true) }
    }

    /**
     * 打开初始化向导的第一步（权限校验页）。
     *
     * feature:main:dashboard 不依赖 feature:setup，只能按类名启动；
     * [SETUP_WIZARD_ACTIVITY] / [SETUP_WIZARD_EXTRA_START] / [SETUP_WIZARD_ROUTE_ONE]
     * 必须与 feature:setup 的 MainActivity 及其 `EXTRA_START_DESTINATION`、`SetupRoutes.One` 保持一致。
     * 用 Application context 启动非 exported 的 Activity，必须显式带 [Intent.FLAG_ACTIVITY_NEW_TASK]。
     */
    private fun openSetupWizard() {
        runCatching {
            context.startActivity(
                Intent().apply {
                    setClassName(context.packageName, SETUP_WIZARD_ACTIVITY)
                    putExtra(SETUP_WIZARD_EXTRA_START, SETUP_WIZARD_ROUTE_ONE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { Log.w(TAG, "open setup wizard failed", it) }
    }

    /** 本次进程内是否已做过后台存活检测，避免每次回首页都重复 query。 */
    private var keepAliveHintChecked = false

    private val _lastBackupTime: Flow<Long> = context.readLastBackupTime().flowOnIO()
    val lastBackupTimeState: StateFlow<Long> = _lastBackupTime.stateInScope(0)

    private val _directory: Flow<DirectoryEntity?> = directoryRepo.querySelectedByDirectoryTypeFlow().flowOnIO()
    val directoryState: StateFlow<DirectoryEntity?> = _directory.stateInScope(null)

    // 临时缓存（恢复中转目录）大小，随 Update 刷新
    private val _cacheSize: MutableStateFlow<Long> = MutableStateFlow(0L)
    val cacheSizeState: StateFlow<Long> = _cacheSize.asStateFlow()

    // OTG 仓库发现状态，供首页 Index.kt 消费
    private val _otgDiscoveryState: MutableStateFlow<OtgDiscoveryState> = MutableStateFlow(OtgDiscoveryState.None)
    val otgDiscoveryState: StateFlow<OtgDiscoveryState> = _otgDiscoveryState.asStateFlow()

    private companion object {
        const val TAG = "DashboardKeepAlive"

        /** feature:setup 模块的 MainActivity 全限定类名（该模块未被 dashboard 依赖，故按字符串引用）。 */
        const val SETUP_WIZARD_ACTIVITY = "com.xayah.feature.setup.MainActivity"
        const val SETUP_WIZARD_EXTRA_START = "start_destination"
        const val SETUP_WIZARD_ROUTE_ONE = "setup_one"
    }
}