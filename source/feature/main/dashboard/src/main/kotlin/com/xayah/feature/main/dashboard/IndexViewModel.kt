package com.xayah.feature.main.dashboard

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.data.repository.ResticRepoLocator
import com.xayah.core.datastore.readLastBackupTime
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoConfigId
import com.xayah.core.datastore.readUpdateChannel
import com.xayah.core.datastore.saveResticPassword
import com.xayah.core.datastore.saveResticRepoConfigId
import com.xayah.core.datastore.saveResticRepoPath
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
     *  - None：未插 OTG / 未发现仓库 / 已登记设备（DataStore 非空，不走首页发现），不显示任何 OTG UI。
     *  - Registered：默认密码可解、已静默登记身份，显示 OTG 容量卡片。
     *  - NeedsSetup：发现了仓库但默认密码不可解或多盘歧义，显示引导卡片，点击去设置页由 bootstrap 编排接手。
     */
    sealed class OtgDiscoveryState {
        data object None : OtgDiscoveryState()
        data class Registered(
            val usedBytes: Long,
            val totalBytes: Long,
            val backupUsedBytes: Long,
        ) : OtgDiscoveryState()
        data object NeedsSetup : OtgDiscoveryState()
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
                // OTG 仓库发现（仅在 DataStore 为空、无 savedConfigId 时触发；已登记设备完全跳过）
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
     * 首页 OTG 探测 + 默认密码静默登记。
     *  - 已登记（readResticRepoConfigId 非空）→ 直接置 None，不做任何发现（自愈交给备份/恢复前置对齐）。
     *  - 发现 0 个 → None。
     *  - 发现 1 个且默认密码可解 → 静默保存 path/configId/默认密码，置 Registered（含容量）。
     *  - 发现 1 个但默认密码不可解，或发现多个 → NeedsSetup，引导去设置页。
     * 全程无任何 saveResticRepoPath("")，发现失败/异常不清空既有配置。
     */
    private suspend fun detectOtgRepository() {
        // 已登记设备不走首页发现
        val savedConfigId = context.readResticRepoConfigId()
        if (!savedConfigId.isNullOrEmpty()) {
            _otgDiscoveryState.value = OtgDiscoveryState.None
            return
        }

        val discovered = resticRepoLocator.discoverOtgRepositories()
        when {
            discovered.isEmpty() -> {
                _otgDiscoveryState.value = OtgDiscoveryState.None
            }

            discovered.size == 1 -> {
                val repo = discovered.first()
                val defaultPassword = context.readResticPassword() ?: "databackup_default"
                val valid = resticRepo.validateRepository(repo.path, defaultPassword)
                if (valid) {
                    // 默认密码可解：零交互静默登记
                    context.saveResticRepoPath(repo.path)
                    context.saveResticRepoConfigId(repo.configId)
                    context.saveResticPassword(defaultPassword)
                    val total = runCatching { rootService.calculateSize(repo.path) }.getOrDefault(0L)
                    _otgDiscoveryState.value = OtgDiscoveryState.Registered(
                        usedBytes = total,
                        totalBytes = total,
                        backupUsedBytes = total,
                    )
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