package com.xayah.feature.main.dashboard

import android.util.Log
import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.data.repository.ResticRepoLocator
import com.xayah.core.data.repository.ResticRepoLocator.ResolveResult
import com.xayah.core.datastore.readLastBackupTime
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
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.localBackupSaveDir
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
        data class Registered(
            val usedBytes: Long,
            val totalBytes: Long,
            val backupUsedBytes: Long,
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
                    val release: Release? = if (channel == 1) {
                        githubRepo.getReleases().maxByOrNull { parseReleaseCode(it) }
                    } else {
                        githubRepo.getLatestRelease()
                    }
                    val remoteCode = release?.let { parseReleaseCode(it) } ?: Long.MIN_VALUE
                    if (release != null && remoteCode > BuildConfigUtil.VERSION_CODE) {
                        emitState(state.copy(latestRelease = release))
                    } else {
                        emitState(state.copy(latestRelease = null))
                    }
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

        val savedConfigId = context.readResticOtgRepoConfigId()

        // 情形 A：已登记设备——插上目标盘即常驻显示 OTG 容量卡
        if (!savedConfigId.isNullOrEmpty()) {
            val savedPath = context.readResticOtgRepoPath().orEmpty()
            when (val result = resticRepoLocator.resolveCurrentResticRepoPath(savedPath, savedConfigId)) {
                is ResolveResult.Matched -> {
                    // 挂载点漂移（换盘/重插导致 UUID 变化）时静默改指，不改身份（写 OTG 键，不污染本地）
                    if (result.changed) {
                        context.saveResticOtgRepoPath(result.path)
                    }
                    _otgDiscoveryState.value = buildRegisteredState(result.path)
                }
                // 当前未插目标 OTG 盘 / 多盘歧义 / 配置在内部存储 → 首页不显示 OTG 卡
                is ResolveResult.Ambiguous,
                ResolveResult.NotFound,
                is ResolveResult.Passthrough -> {
                    _otgDiscoveryState.value = OtgDiscoveryState.None
                }
            }
            return
        }

        // 情形 B：未登记设备——无差别发现 + 默认密码静默登记
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
        return OtgDiscoveryState.Registered(
            usedBytes = usedBytes,
            totalBytes = totalBytes,
            backupUsedBytes = backupUsedBytes,
        )
    }

    private fun parseReleaseCode(release: Release): Long {
        release.tagName.substringAfterLast('.', "").toLongOrNull()?.let { return it }
        Regex("Build\\s+(\\d+)").find(release.name)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        return Long.MIN_VALUE
    }

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
}