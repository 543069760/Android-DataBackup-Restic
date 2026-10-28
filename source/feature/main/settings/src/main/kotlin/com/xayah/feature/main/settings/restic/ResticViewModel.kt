package com.xayah.feature.main.settings.restic

import android.net.Uri
import android.content.Context
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import com.xayah.core.data.repository.ResticRepoLocator
import com.xayah.core.data.repository.ResticRepoLocator.DiscoveredRepo
import com.xayah.core.data.repository.ResticRepoLocator.ResolveResult
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoConfigId
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.datastore.saveResticPassword
import com.xayah.core.datastore.saveResticRepoConfigId
import com.xayah.core.datastore.saveResticRepoPath
import com.xayah.core.datastore.readResticOtgPassword
import com.xayah.core.datastore.readResticOtgRepoConfigId
import com.xayah.core.datastore.readResticOtgRepoPath
import com.xayah.core.datastore.saveResticOtgPassword
import com.xayah.core.datastore.saveResticOtgRepoConfigId
import com.xayah.core.datastore.saveResticOtgRepoPath
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.util.formatSize
import com.xayah.core.restic.ResticNative
import com.xayah.core.restic.ResticRepository
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.command.SELinux
import com.xayah.core.util.command.PreparationUtil
import com.xayah.feature.main.settings.R
import com.xayah.libpickyou.parcelables.DirChildrenParcelable
import com.xayah.libpickyou.parcelables.FileParcelable
import dagger.hilt.android.lifecycle.HiltViewModel
import com.xayah.core.util.toFriendlyMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import javax.inject.Inject

data object ResticUiState : UiState

sealed class ResticUiIntent : UiIntent

// OTG 分区信息：供初始化页渲染分区卡片
data class OtgPartition(
    val uuid: String,        // 卷叶子名，如 E7F9-FA61
    val path: String,        // 完整挂载路径 /mnt/media_rw/E7F9-FA61
    val availableBytes: Long,
    val totalBytes: Long,
    val fsType: String?      // 文件系统类型，如 vfat/exfat/ext4，取不到为 null
)

@ExperimentalMaterial3Api
@HiltViewModel
class ResticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepo: ResticRepository,
    private val rootService: RemoteRootService,
    private val resticNative: ResticNative,
    private val resticRepoLocator: ResticRepoLocator
) : BaseViewModel<ResticUiState, ResticUiIntent, IndexUiEffect>(ResticUiState) {

    // --- 状态流管理 ---
    private val _repoPathState = MutableStateFlow<String?>(null)
    val repoPathState: StateFlow<String?> = _repoPathState.asStateFlow()

    private val _resticVersionState = MutableStateFlow<String?>(null)
    val resticVersionState: StateFlow<String?> = _resticVersionState.asStateFlow()

    private val _resticInitializedState = MutableStateFlow(false)
    val resticInitializedState: StateFlow<Boolean> = _resticInitializedState.asStateFlow()

    private val _resticRepoPathState = MutableStateFlow("")
    val resticRepoPathState: StateFlow<String> = _resticRepoPathState.asStateFlow()

    private val _resticSnapshotCountState = MutableStateFlow(0)
    val resticSnapshotCountState: StateFlow<Int> = _resticSnapshotCountState.asStateFlow()

    private val _initializationState = MutableStateFlow<InitializationState>(InitializationState.Idle)
    val initializationState: StateFlow<InitializationState> = _initializationState.asStateFlow()

    // 纯 UI 状态：是否处于“重新初始化”模式（不触碰持久化数据）
    private val _isReinitializing = MutableStateFlow(false)
    val isReinitializing: StateFlow<Boolean> = _isReinitializing.asStateFlow()

    private val _resticErrorState = MutableStateFlow<String?>(null)
    val resticErrorState: StateFlow<String?> = _resticErrorState.asStateFlow()

    // --- OTG 独立状态流（与本地/云端互不干扰，供设置页 OTG 项使用）---
    private val _otgInitializedState = MutableStateFlow(false)
    val otgInitializedState: StateFlow<Boolean> = _otgInitializedState.asStateFlow()

    private val _otgRepoPathState = MutableStateFlow("")
    val otgRepoPathState: StateFlow<String> = _otgRepoPathState.asStateFlow()

    private val _otgSnapshotCountState = MutableStateFlow(0)
    val otgSnapshotCountState: StateFlow<Int> = _otgSnapshotCountState.asStateFlow()

    // OTG 专属初始化状态与错误提示（与本地 _initializationState/_resticErrorState 隔离，
    // 避免 OTG 刷新/bootstrap 覆盖本地/云端的初始化状态与 error 提示，导致设置页状态串台）
    private val _otgInitializationState = MutableStateFlow<InitializationState>(InitializationState.Idle)
    val otgInitializationState: StateFlow<InitializationState> = _otgInitializationState.asStateFlow()

    private val _otgErrorState = MutableStateFlow<String?>(null)
    val otgErrorState: StateFlow<String?> = _otgErrorState.asStateFlow()

    // OTG 当前挂载路径（第一个 /mnt/media_rw/<UUID> 挂载点，无盘为 null）
    // 由 otgMountEvents 订阅实时刷新，设置页 OTG 分组用它判定插拔与显示 UUID
    private val _otgMountPathState = MutableStateFlow<String?>(null)
    val otgMountPathState: StateFlow<String?> = _otgMountPathState.asStateFlow()

    sealed class InitializationState {
        object Idle : InitializationState()
        object Checking : InitializationState()
        object Validating : InitializationState()
        object Initializing : InitializationState()
        data class ReadyToUse(val repoPath: String) : InitializationState()
        data class PasswordError(val repoPath: String) : InitializationState()
        data class Error(val message: String) : InitializationState()
        // OTG bootstrap 相关
        data class Offline(val repoPath: String) : InitializationState()
        data class NeedsPassword(val repoPath: String, val configId: String) : InitializationState()
        data class SelectRepository(val candidates: List<DiscoveredRepo>) : InitializationState()
    }

    init {
        viewModelScope.launch {
            checkResticStatus()
        }

        // 订阅 OTG 挂载/拔插/换盘事件，事件一到即刷新挂载路径与 OTG 独立状态。
        // otgMountEvents() 进入即 trySend(Unit) 首发，故此订阅本身完成 OTG 首刷，
        // 无需在设置页再写 delay 轮询。仅读写 OTG 键，绝不触碰本地/云端配置。
        launchOnIO {
            resticRepoLocator.otgMountEvents()
                .flowOnIO()
                .collect {
                    runCatching {
                        // 实时取第一个挂载点，换盘后 UUID 随之更新
                        _otgMountPathState.value = PreparationUtil.listExternalStorage().out
                            .firstOrNull { it.isNotBlank() }
                        // 刷新 OTG 独立状态（读 OTG 键）
                        refreshOtgStatus()
                    }.onFailure {
                        Log.e(TAG, "otgMountEvents collect 异常", it)
                    }
                }
        }
    }

    /**
     * 核心逻辑：状态检查（仅本地/内部存储 + 云端；OTG 由 refreshOtgStatus 独立处理）
     * 合并为一个支持 withContext 的挂起函数，确保 init 块时序正确
     *
     * 迁移说明：getVersion 已改走 JNI（librustic.so，经 RootService），
     * 不再依赖 restic 二进制文件，因此移除“二进制文件存在/可执行”的前置门控，
     * 否则删掉二进制后永远走不到 JNI 的 getVersion、UI 会被锁死。
     */
    suspend fun checkResticStatus() {
        withContext(Dispatchers.IO) {
            // 临时探针：验证 root 进程 libgojni.so 加载 + rcloneRPC 打通
            try {
                val out = rootService.rcloneRpc("core/version", "{}")
                Log.i("RcloneProbe", "core/version OK: $out")
            } catch (e: Exception) {
                Log.e("RcloneProbe", "core/version FAILED", e)
            }
            try {
                // 1. 获取版本号（通过 JNI：Rustic.getVersion → RootService）
                val version = resticRepo.getVersion()
                Log.d(TAG, "DEBUG: 尝试获取版本结果: $version")
                _resticVersionState.value = version

                // 2. 版本获取失败则直接返回（UI 显示未检测到）
                if (version == null) {
                    _resticInitializedState.value = false
                    _resticRepoPathState.value = ""
                    return@withContext
                }

                // 3. 获取并同步仓库路径（仅本地键）
                val repoPath = getRepoPath()
                _repoPathState.value = repoPath

                // 说明：OTG 的发现/重对齐/状态回显已完全独立到 refreshOtgStatus()，
                //      本函数只负责本地/内部存储，绝不读写任何 OTG 键，避免覆盖本地配置。

                // 4. 仓库校验 / 快照数
                val password = getResticPassword()
                val isInitialized = resticRepo.checkRepository(repoPath, password)
                _resticInitializedState.value = isInitialized

                Log.w(TAG, "checkResticStatus: repoPath=$repoPath, checkOk=$isInitialized")

                if (!isInitialized) {
                    val exists = rootService.rusticRepositoryExists(repoPath)
                    if (!exists) {
                        // 内部存储：仓库确实不存在/未初始化，清除已保存的路径
                        Log.w(TAG, "checkResticStatus: 仓库不存在/未初始化，清空 repoPath: $repoPath")
                        context.saveResticRepoPath("")
                        _resticRepoPathState.value = ""
                        _resticSnapshotCountState.value = 0
                        _resticErrorState.value = null
                        _initializationState.value = InitializationState.Idle
                    } else {
                        // 仓库存在但校验失败 —— 密码错误，不能清空已保存的路径
                        Log.w(TAG, "checkResticStatus: 仓库存在但校验失败（密码错误），保留 repoPath: $repoPath")
                        _resticRepoPathState.value = repoPath
                        _resticSnapshotCountState.value = 0
                        _resticErrorState.value = context.getString(com.xayah.core.data.R.string.pre_backup_repository_check_failed)
                        _initializationState.value = InitializationState.PasswordError(repoPath)
                    }
                } else {
                    _resticRepoPathState.value = repoPath
                    _resticErrorState.value = null
                    _initializationState.value = InitializationState.ReadyToUse(repoPath)
                    val snapshots = resticRepo.listSnapshots(repoPath, password)
                    _resticSnapshotCountState.value = snapshots.size
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkResticStatus 异常", e)
                _resticVersionState.value = null
                _resticInitializedState.value = false
                _resticRepoPathState.value = ""
            }
        }
    }

    /**
     * OTG 独立状态刷新：读写全部走 OTG 独立键，绝不触碰本地键。
     * 由设置页 OTG 项进入时（LaunchedEffect）或 otgMountEvents 订阅触发。
     * 逻辑与 checkResticStatus 对称，但作用于 OTG 键与 OTG 状态流：
     * - 无 config_id（从未登记）→ bootstrapFromOtgIfEmpty 自动发现登记（走 OTG 键）
     * - 有路径但校验失败 → handleOtgStatus 重对齐（走 OTG 键）
     */
    suspend fun refreshOtgStatus() {
        withContext(Dispatchers.IO) {
            try {
                val version = resticRepo.getVersion()
                if (version == null) {
                    _otgInitializedState.value = false
                    _otgRepoPathState.value = ""
                    _otgSnapshotCountState.value = 0
                    return@withContext
                }

                // 换新机 bootstrap：OTG 从未登记（OTG config_id 为空）时自动发现登记
                if (context.readResticOtgRepoConfigId() == null) {
                    if (bootstrapFromOtgIfEmpty()) {
                        return@withContext
                    }
                }

                val repoPath = context.readResticOtgRepoPath()
                if (repoPath.isNullOrEmpty()) {
                    // 未登记 OTG 仓库
                    _otgInitializedState.value = false
                    _otgRepoPathState.value = ""
                    _otgSnapshotCountState.value = 0
                    return@withContext
                }
                _otgRepoPathState.value = repoPath

                val password = context.readResticOtgPassword() ?: "databackup_default"
                val isInitialized = resticRepo.checkRepository(repoPath, password)
                _otgInitializedState.value = isInitialized

                Log.w(TAG, "refreshOtgStatus: repoPath=$repoPath, checkOk=$isInitialized")

                if (!isInitialized) {
                    // OTG 校验失败：按 config_id 身份扫描重对齐（保留旧配置，绝不清空）
                    handleOtgStatus(repoPath)
                } else {
                    _otgRepoPathState.value = repoPath
                    _otgErrorState.value = null
                    _otgInitializationState.value = InitializationState.ReadyToUse(repoPath)
                    val snapshots = resticRepo.listSnapshots(repoPath, password)
                    _otgSnapshotCountState.value = snapshots.size
                }
            } catch (e: Exception) {
                Log.e(TAG, "refreshOtgStatus 异常", e)
                _otgInitializedState.value = false
            }
        }
    }

    /**
     * OTG 已登记设备：checkRepository 失败时按 config_id 身份扫描重对齐。
     * - Matched（一致）：直接放行；Matched（不一致）：更新 OTG DataStore 路径后放行。
     * - NotFound：没插盘/无此仓库 —— 保留旧配置，不清空，置为离线态。
     * - Ambiguous：多盘命中 —— 交给用户选择。
     * 全程只读写 OTG 键与 OTG 状态流。
     */
    private suspend fun handleOtgStatus(repoPath: String) {
        val savedConfigId = context.readResticOtgRepoConfigId()
        when (val r = resticRepoLocator.resolveCurrentResticRepoPath(repoPath, savedConfigId)) {
            is ResolveResult.Matched -> {
                if (r.changed) {
                    context.saveResticOtgRepoPath(r.path)
                }
                // 路径已对齐，重新校验一次
                val password = context.readResticOtgPassword() ?: "databackup_default"
                val ok = resticRepo.checkRepository(r.path, password)
                if (ok) {
                    _otgInitializedState.value = true
                    _otgRepoPathState.value = r.path
                    _otgErrorState.value = null
                    _otgInitializationState.value = InitializationState.ReadyToUse(r.path)
                    val snapshots = resticRepo.listSnapshots(r.path, password)
                    _otgSnapshotCountState.value = snapshots.size
                } else {
                    _otgRepoPathState.value = r.path
                    _otgSnapshotCountState.value = 0
                    _otgErrorState.value = context.getString(com.xayah.core.data.R.string.pre_backup_repository_check_failed)
                    _otgInitializationState.value = InitializationState.PasswordError(r.path)
                }
            }
            is ResolveResult.NotFound -> {
                // 没插盘/换了空盘：保留旧配置，绝不清空，置离线态
                Log.w(TAG, "handleOtgStatus: 未扫到目标 OTG 仓库，保留配置并置离线: $repoPath")
                _otgRepoPathState.value = repoPath
                _otgSnapshotCountState.value = 0
                _otgErrorState.value = context.getString(com.xayah.core.data.R.string.restic_otg_offline)
                _otgInitializationState.value = InitializationState.Offline(repoPath)
            }
            is ResolveResult.Ambiguous -> {
                Log.w(TAG, "handleOtgStatus: 多个 OTG 仓库命中，需用户选择")
                _otgErrorState.value = context.getString(com.xayah.core.data.R.string.restic_otg_ambiguous)
                _otgInitializationState.value = InitializationState.SelectRepository(
                    r.candidates.map { DiscoveredRepo(path = it, configId = savedConfigId ?: "") }
                )
            }
            is ResolveResult.Passthrough -> {
                // 理论上不会走到（前缀已判过），保守置离线
                _otgInitializationState.value = InitializationState.Offline(repoPath)
            }
        }
    }

    // ================= 换新机自动 bootstrap（OTG 专属，全走 OTG 键）=================

    /**
     * 换新机/全新安装（OTG DataStore 无 config_id）时的自动发现登记。
     * 由 refreshOtgStatus 在 OTG config_id == null 时调用。
     *
     * @return true 表示 bootstrap 已接管本次流程（登记成功 / 已置 NeedsPassword / SelectRepository / 未发现），
     *         调用方应提前返回，不再走常规校验；false 表示无需 bootstrap（非首次或异常），继续常规流程。
     */
    private suspend fun bootstrapFromOtgIfEmpty(): Boolean {
        // 双保险：仅在 OTG DataStore 为空时执行
        if (context.readResticOtgRepoConfigId() != null) return false

        val discovered = try {
            resticRepoLocator.discoverOtgRepositories()
        } catch (e: Exception) {
            Log.e(TAG, "bootstrapFromOtgIfEmpty: 发现失败", e)
            return false
        }

        return when {
            discovered.isEmpty() -> {
                // 没插盘/空盘：不改动任何持久化数据，交给常规流程显示未检测到
                Log.d(TAG, "bootstrapFromOtgIfEmpty: 未发现 OTG 仓库")
                false
            }
            discovered.size == 1 -> {
                tryRegisterDiscovered(discovered.first())
                true
            }
            else -> {
                // 多盘：交给用户选择
                Log.d(TAG, "bootstrapFromOtgIfEmpty: 发现多个 OTG 仓库，需用户选择")
                _otgInitializationState.value = InitializationState.SelectRepository(discovered)
                true
            }
        }
    }

    /**
     * 尝试用默认密码零交互登记单个候选；默认密码不可解则弹密码框。
     */
    private suspend fun tryRegisterDiscovered(repo: DiscoveredRepo) {
        val defaultPassword = "databackup_default"
        _otgInitializationState.value = InitializationState.Validating
        val valid = try {
            resticRepo.validateRepository(repo.path, defaultPassword)
        } catch (e: Exception) {
            Log.e(TAG, "tryRegisterDiscovered: 默认密码校验异常", e)
            false
        }
        if (valid) {
            // 默认密码可解：零交互完成登记
            persistBootstrapResult(repo.path, repo.configId, defaultPassword)
        } else {
            // 用了自定义密码：弹密码框，记住待登记的候选
            Log.d(TAG, "tryRegisterDiscovered: 默认密码不可解，需用户输入密码: ${repo.path}")
            _otgInitializationState.value = InitializationState.NeedsPassword(repo.path, repo.configId)
        }
    }

    /**
     * 用户在 NeedsPassword 状态下输入密码后校验并登记。
     */
    fun submitOtgPassword(password: String) {
        viewModelScope.launch {
            val state = _otgInitializationState.value
            if (state !is InitializationState.NeedsPassword) return@launch
            _otgInitializationState.value = InitializationState.Validating
            withContext(Dispatchers.IO) {
                val valid = try {
                    resticRepo.validateRepository(state.repoPath, password)
                } catch (e: Exception) {
                    Log.e(TAG, "submitOtgPassword: 校验异常", e)
                    false
                }
                if (valid) {
                    persistBootstrapResult(state.repoPath, state.configId, password)
                } else {
                    _otgErrorState.value = context.getString(com.xayah.core.data.R.string.pre_backup_repository_check_failed)
                    _otgInitializationState.value = InitializationState.NeedsPassword(state.repoPath, state.configId)
                }
            }
        }
    }

    /**
     * 用户在 SelectRepository 状态下选中某个候选后，继续走密码校验/登记。
     */
    fun selectOtgRepository(repo: DiscoveredRepo) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                tryRegisterDiscovered(repo)
            }
        }
    }

    /**
     * 手动扫描入口（离线态/设置页“扫描并连接 OTG 仓库”按钮触发）。
     * 不加“DataStore 为空”门控，允许用户主动切换到另一个 OTG 仓库（换库重登记）。
     */
    fun scanOtgRepositories() {
        viewModelScope.launch {
            _otgInitializationState.value = InitializationState.Checking
            withContext(Dispatchers.IO) {
                val discovered = try {
                    resticRepoLocator.discoverOtgRepositories()
                } catch (e: Exception) {
                    Log.e(TAG, "scanOtgRepositories: 发现失败", e)
                    emptyList()
                }
                when {
                    discovered.isEmpty() -> {
                        val savedPath = context.readResticOtgRepoPath() ?: ""
                        _otgErrorState.value = context.getString(com.xayah.core.data.R.string.restic_otg_offline)
                        _otgInitializationState.value = InitializationState.Offline(savedPath)
                    }
                    discovered.size == 1 -> tryRegisterDiscovered(discovered.first())
                    else -> _otgInitializationState.value = InitializationState.SelectRepository(discovered)
                }
            }
        }
    }

    /**
     * 取消 bootstrap，回到 Idle。
     */
    fun cancelOtgBootstrap() {
        _otgInitializationState.value = InitializationState.Idle
        _otgErrorState.value = null
    }

    /**
     * bootstrap 校验通过后统一持久化：OTG 路径 + OTG config_id + OTG 密码，并刷新 OTG 状态。
     */
    private suspend fun persistBootstrapResult(repoPath: String, configId: String, password: String) {
        context.saveResticOtgRepoPath(repoPath)
        context.saveResticOtgRepoConfigId(configId)
        context.saveResticOtgPassword(password)
        _otgRepoPathState.value = repoPath
        _otgInitializedState.value = true
        _otgErrorState.value = null
        _otgInitializationState.value = InitializationState.ReadyToUse(repoPath)
        val snapshots = try {
            resticRepo.listSnapshots(repoPath, password)
        } catch (e: Exception) {
            emptyList()
        }
        _otgSnapshotCountState.value = snapshots.size
        Log.d(TAG, "persistBootstrapResult: 已登记 OTG 仓库身份 configId=$configId path=$repoPath")
    }

    // --- 核心逻辑：初始化与验证 ---
    /** 归一化后的仓库路径：path=真正的仓库路径，isRepository=该路径已经是 rustic 仓库。 */
    private data class ResolvedRepoPath(val path: String, val isRepository: Boolean)

    /**
     * 目录语义统一（与首次初始化引导一致）：用户既可以选中仓库目录本身，也可以选中它的上一层（内含 restic_repo）。
     * 结构性判定走 native rusticRepositoryExists（读 config_id，与密码无关），不用 File.exists()，
     * 否则「直接选中 restic_repo 本身」会被拼成 .../restic_repo/restic_repo，
     * 在旧仓库内部静默新建一个空仓库（报成功但恢复不出任何数据）。
     */
    private suspend fun resolveRepoPathForInit(selectedPath: String): ResolvedRepoPath {
        val isRepository: suspend (String) -> Boolean = { path ->
            runCatching { rootService.rusticRepositoryExists(path) }
                .onFailure { Log.w(TAG, "rusticRepositoryExists failed for $path: ${it.message}") }
                .getOrDefault(false)
        }
        val trimmed = selectedPath.trimEnd('/')
        if (trimmed.isNotBlank() && isRepository(trimmed)) {
            Log.i(TAG, "resolveRepoPathForInit: 选中目录本身即仓库 -> $trimmed")
            return ResolvedRepoPath(trimmed, isRepository = true)
        }
        val child = File(trimmed, RESTIC_REPO_DIR_NAME).absolutePath
        if (isRepository(child)) {
            Log.i(TAG, "resolveRepoPathForInit: 选中目录下的 $RESTIC_REPO_DIR_NAME 才是仓库 -> $child")
            return ResolvedRepoPath(child, isRepository = true)
        }
        Log.i(TAG, "resolveRepoPathForInit: 未识别到仓库，按新仓库处理 -> $child")
        return ResolvedRepoPath(child, isRepository = false)
    }

    /**
     * 全新仓库建库预检：返回 null 表示通过，返回非空即为用户可见的错误原因。
     * 只做只读检查 + 一次性可写探针，绝不改动已有数据；任何一步不通过都不执行 rustic init。
     */
    private suspend fun preCheckNewRepository(repoPath: String): String? {
        // a) 目录已存在且非空（半复制/残留文件）→ 阻断，避免 init 失败或产生不一致仓库
        if (rootService.exists(repoPath)) {
            val children = rootService.listFilePaths(repoPath)
            if (children.isNotEmpty()) {
                Log.w(TAG, "preCheckNewRepository: 目标目录非空且不是仓库 -> $repoPath, children=${children.size}")
                return context.getString(R.string.restic_init_dir_not_empty, repoPath)
            }
        } else if (!rootService.mkdirs(repoPath)) {
            Log.w(TAG, "preCheckNewRepository: 目录创建失败 -> $repoPath")
            return context.getString(R.string.restic_init_not_writable, repoPath)
        }

        // b) 可写自检：写入探针文件后立即删除，防止 OTG/权限异常导致 init 中途失败
        val probe = File(repoPath, ".restic_write_probe").absolutePath
        val writable = rootService.writeText("probe", probe) && rootService.exists(probe)
        rootService.deleteRecursively(probe)
        if (!writable) {
            Log.w(TAG, "preCheckNewRepository: 可写自检失败 -> $repoPath")
            return context.getString(R.string.restic_init_not_writable, repoPath)
        }

        // c) 剩余空间（readStatFs 失败时 availableBytes 为 0，跳过检查避免误伤）
        val stat = rootService.readStatFs(repoPath)
        if (stat.availableBytes > 0 && stat.availableBytes < MIN_FREE_SPACE_BYTES) {
            Log.w(TAG, "preCheckNewRepository: 可用空间不足 -> ${stat.availableBytes} < $MIN_FREE_SPACE_BYTES")
            return context.getString(R.string.restic_init_low_space, stat.availableBytes.toDouble().formatSize())
        }
        Log.i(TAG, "preCheckNewRepository: 预检通过 -> $repoPath, 可用 ${stat.availableBytes.toDouble().formatSize()}")
        return null
    }

    /**
     * 已有仓库登记：validate 通过后写入各持久化状态流、拉取快照数、锚定 config_id。
     * 全只读，不触碰仓库内容。
     */
    private suspend fun registerExistingRepository(
        repoPath: String,
        password: String,
        otg: Boolean,
        emit: (InitializationState) -> Unit,
    ): Boolean {
        if (!resticRepo.validateRepository(repoPath, password)) {
            emit(InitializationState.PasswordError(repoPath))
            return false
        }
        // 持久化状态流按仓库类型分流，避免 OTG 初始化误置本地状态
        if (otg) {
            _otgInitializedState.value = true
            _otgRepoPathState.value = repoPath
        } else {
            _resticInitializedState.value = true
            _resticRepoPathState.value = repoPath
            _repoPathState.value = repoPath
        }

        val snapshots = resticRepo.listSnapshots(repoPath, password)
        if (otg) _otgSnapshotCountState.value = snapshots.size
        else _resticSnapshotCountState.value = snapshots.size

        persistRepoPath(repoPath)
        persistPassword(repoPath, password)
        // 登记 config_id 身份锚点（后续拔插/换盘靠它重对齐）
        saveRepoConfigId(repoPath)

        emit(InitializationState.ReadyToUse(repoPath))
        _isReinitializing.value = false
        return true
    }

    suspend fun initializeOrValidateRepository(selectedPath: String): Boolean {
        _initializationState.value = InitializationState.Checking

        return withContext(Dispatchers.IO) {
            try {
                // 0. 归一化仓库路径（选中目录本身或其父目录都认），避免建出嵌套空仓库
                val resolved = resolveRepoPathForInit(selectedPath)
                val repoPath = resolved.path
                val password = readPasswordFor(repoPath)   // OTG 读 OTG 密码，本地读本地密码
                val otg = isOtgPath(repoPath)
                // 状态按仓库类型分流：本地走原状态流，OTG 额外同步到 OTG 状态流（保持老行为 + 让 OTG 页能收到进度）
                val emit: (InitializationState) -> Unit = { state ->
                    _initializationState.value = state
                    if (otg) _otgInitializationState.value = state
                }

                if (resolved.isRepository) {
                    emit(InitializationState.Validating)
                    // 已有仓库：只做校验，绝不 init
                    registerExistingRepository(repoPath, password, otg, emit)
                } else {
                    // 全新仓库：先做建库预检，任何一项不通过都不执行 rustic init
                    val preCheckError = preCheckNewRepository(repoPath)
                    if (preCheckError != null) {
                        emit(InitializationState.Error(preCheckError))
                        false
                    } else {
                        emit(InitializationState.Initializing)
                        val initSuccess = initializeRepository(repoPath, password)

                        if (initSuccess) {
                            // 建库后复检（新仓库是空仓库，check 开销极小）：拦住“看着成功但不可用”的仓库
                            if (!resticRepo.verifyRepository(repoPath, password)) {
                                Log.e(TAG, "initializeOrValidateRepository: 仓库创建成功但复检未通过 -> $repoPath")
                                emit(InitializationState.Error(context.getString(R.string.restic_init_post_check_failed)))
                                return@withContext false
                            }
                            if (otg) {
                                _otgInitializedState.value = true
                                _otgRepoPathState.value = repoPath
                                _otgSnapshotCountState.value = 0
                            } else {
                                _resticInitializedState.value = true
                                _resticRepoPathState.value = repoPath
                                _resticSnapshotCountState.value = 0
                            }
                            emit(InitializationState.ReadyToUse(repoPath))
                            _isReinitializing.value = false
                        } else {
                            emit(InitializationState.Error("Initialization failed"))
                        }
                        initSuccess
                    }
                }
            } catch (e: Exception) {
                _initializationState.value = InitializationState.Error(e.toFriendlyMessage(context))
                false
            }
        }
    }

    suspend fun initializeRepository(repoPath: String, password: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 使用 Root Service 创建目录并设置权限
                rootService.mkdirs(repoPath)
                rootService.setAllPermissions(repoPath)

                // 2. SELinux 上下文修复
                SELinux.getContext(path = repoPath).also { result ->
                    val pathContext = if (result.isSuccess) result.outString else ""
                    SELinux.chcon(context = pathContext, path = repoPath)
                    val uidGid = context.applicationInfo.uid.toUInt()
                    SELinux.chown(uid = uidGid, gid = uidGid, path = repoPath)
                }

                // 3. 调用重构后的协程版 initRepository
                val initResult = resticRepo.initRepository(repoPath, password)

                if (initResult.isSuccess) {
                    saveInitializationState(repoPath, password)
                    true
                } else {
                    val errorLog = initResult.exceptionOrNull()?.message
                    Log.e(TAG, "Restic init failed: $errorLog")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing repository", e)
                false
            }
        }
    }

    // --- 辅助方法 ---
    fun saveInitializationState(repoPath: String, password: String) {
        viewModelScope.launch {
            // 写入分流：OTG 走独立键，本地/云端走原键
            persistRepoPath(repoPath)
            persistPassword(repoPath, password)
            // 创建成功后同样登记 config_id（同样按仓库类型分流）
            saveRepoConfigId(repoPath)
            _repoPathState.value = repoPath
            // 刷新分流：OTG 只刷新 OTG 状态，避免覆盖本地状态
            if (isOtgPath(repoPath)) refreshOtgStatus()
            else checkResticStatus()
        }
    }

    /**
     * 读取并保存指定仓库的 config_id 身份锚点。
     * 建库/校验成功、OTG 处于挂载状态时调用，此刻读 config_id 必然成功。
     * 按仓库类型分流：OTG 写 OTG config_id，本地写本地 config_id。
     */
    private suspend fun saveRepoConfigId(repoPath: String) {
        try {
            val id = rootService.rusticRepositoryConfigId(repoPath)
            // 原始返回值：null/空=native 读取失败；全 0=native 返回退化值；正常=随机十六进制
            Log.d(TAG, "saveRepoConfigId: repoPath=$repoPath rawConfigId=$id")

            // 防污染：空或全 0 都不是合法身份锚点，拒绝登记，避免后续 OTG 重对齐用全 0 误判
            if (id.isNullOrEmpty() || id.all { it == '0' }) {
                Log.w(TAG, "saveRepoConfigId: 非法 config_id（空或全 0），拒绝登记 repoPath=$repoPath")
                return
            }

            context.saveResticRepoConfigId(id)
            Log.d(TAG, "saveRepoConfigId: 已保存 config_id=$id for $repoPath")
        } catch (e: Exception) {
            Log.e(TAG, "saveRepoConfigId: 读取 config_id 失败", e)
        }
    }

    suspend fun getPassword(): String = getResticPassword()

    fun savePassword(password: String) {
        viewModelScope.launch {
            context.saveResticPassword(password)
            checkResticStatus()
        }
    }

    fun saveRepoPath(path: String) {
        val sanitizedPath = path.trim()
        viewModelScope.launch {
            context.saveResticRepoPath(sanitizedPath)
            _repoPathState.value = sanitizedPath
            withContext(Dispatchers.IO) {
                Shell.cmd(
                    "mkdir -p '$sanitizedPath'",
                    "chmod 777 '$sanitizedPath'",
                    "restorecon -R '$sanitizedPath'"
                ).exec()
            }
            checkResticStatus()
        }
    }

    // 本地专用：清空本地键与本地状态（不触碰 OTG）
    fun clearInitializationState() {
        viewModelScope.launch {
            context.saveResticRepoPath("")
            context.saveResticPassword("")
            context.saveResticRepoConfigId("")
            _repoPathState.value = ""
            _resticInitializedState.value = false
            _resticSnapshotCountState.value = 0
            _initializationState.value = InitializationState.Idle
        }
    }

    // OTG 专用：清空 OTG 键与 OTG 状态（不触碰本地）
    fun clearOtgInitializationState() {
        viewModelScope.launch {
            context.saveResticOtgRepoPath("")
            context.saveResticOtgPassword("")
            context.saveResticOtgRepoConfigId("")
            _otgRepoPathState.value = ""
            _otgInitializedState.value = false
            _otgSnapshotCountState.value = 0
            _otgInitializationState.value = InitializationState.Idle
        }
    }

    /**
     * 进入"重新初始化"模式：仅切换 UI 显示，不清空已持久化的仓库路径/密码。
     */
    fun enterReinitializeMode() {
        _isReinitializing.value = true
        _initializationState.value = InitializationState.Idle
    }

    /**
     * 退出"重新初始化"模式（用户中途返回时重置标志），不改动任何持久化数据。
     */
    fun exitReinitializeMode() {
        _isReinitializing.value = false
    }

    suspend fun deleteAndReinitializeRepository(repoPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            if (resticRepo.deleteRepository(repoPath)) {
                // 密码分流：OTG 用 OTG 密码，本地用本地密码
                initializeRepository(repoPath, readPasswordFor(repoPath))
            } else false
        }
    }

    suspend fun getRepoPath(): String {
        return context.readResticRepoPath() ?: File(context.filesDir, "restic_repo").absolutePath
    }

    private suspend fun getResticPassword(): String {
        return context.readResticPassword() ?: "databackup_default"
    }

    /**
     * TODO#2：解析 OTG 仓库父目录。
     * - 用户已选到 /mnt/media_rw/<UUID>：原样返回。
     * - 用户只选到 /mnt/media_rw 根目录：自动补第一个挂载点 UUID。
     * 返回 null 表示无挂载点（未插盘）。
     */
    suspend fun resolveOtgRepoParent(selectedPath: String): String? {
        val normalized = selectedPath.trimEnd('/')
        if (normalized.startsWith(OTG_PREFIX) && normalized != OTG_PREFIX.trimEnd('/')) {
            return normalized
        }
        return runCatching {
            PreparationUtil.listExternalStorage().out.firstOrNull { it.isNotBlank() }?.trimEnd('/')
        }.getOrNull()
    }

    /**
     * 供 libpickyou 的 mkdirsBackend 使用：用主工程 core RemoteRootService
     * （mount-master 全局命名空间，能看到 /mnt/media_rw/<UUID> 挂载）创建目录，
     * 绕过 libpickyou 自身 RootService binder 进程看不到 OTG 挂载导致的创建失败。
     * mkdirsBackend 传入 parent 与 child 两段，此处拼成完整路径交给 core mkdirs。
     */
    suspend fun mkdirsOtg(parent: String, child: String): Boolean = withContext(Dispatchers.IO) {
        rootService.mkdirs("$parent/$child")
    }

    /**
     * 列出当前所有已挂载的 OTG 分区（口径与首页/备份一致：mount 过滤 /mnt/media_rw/<UUID>），
     * 每个分区带可用/总容量与文件系统类型，供初始化页用卡片展示、辅助用户选分区。
     * 只列真实挂载卷，不会把 /mnt/media_rw 下未挂载的残留目录列进来。
     */
    suspend fun listOtgPartitions(): List<OtgPartition> = withContext(Dispatchers.IO) {
        val paths = runCatching {
            PreparationUtil.listExternalStorage().out.filter { it.isNotBlank() }
        }.getOrElse { emptyList() }

        paths.map { raw ->
            val path = raw.trimEnd('/')
            val stat = runCatching { rootService.readStatFs(path) }.getOrNull()
            val fsType = runCatching {
                PreparationUtil.getExternalStorageRealType(path).out
                    .firstOrNull { it.isNotBlank() && it.lowercase() != "fuseblk" }
                    ?: PreparationUtil.getExternalStorageType(path).out.firstOrNull { it.isNotBlank() }
            }.getOrNull()
            OtgPartition(
                uuid = path.substringAfterLast('/'),
                path = path,
                availableBytes = stat?.availableBytes ?: 0L,
                totalBytes = stat?.totalBytes ?: 0L,
                fsType = fsType
            )
        }
    }

    /**
     * 供 libpickyou 的 traverseBackend 使用：用主工程 core RemoteRootService（mount-master 全局命名空间，
     * 能看到 vold 挂在 /mnt/media_rw/<UUID> 的 OTG 盘）列目录，绕过 libpickyou 自身 RootService binder
     * 进程看不到 OTG 挂载导致的空白问题。
     * 注意：listFilePaths 返回全路径，FileParcelable.name 需要叶子名，故 substringAfterLast('/')。
     */
    suspend fun listOtgChildren(pathString: String): DirChildrenParcelable = withContext(Dispatchers.IO) {
        val dirs = rootService.listFilePaths(pathString, listFiles = false, listDirs = true)
            .map { FileParcelable(it.substringAfterLast('/'), 0L) }
        val files = rootService.listFilePaths(pathString, listFiles = true, listDirs = false)
            .map { FileParcelable(it.substringAfterLast('/'), 0L) }
        DirChildrenParcelable(files = files, directories = dirs)
    }

    // --- OTG / 本地 读写分流辅助 ---
    // 按仓库路径前缀判断是否 OTG 仓库
    private fun isOtgPath(path: String?): Boolean =
        path?.startsWith(OTG_PREFIX) == true

    // 统一写：OTG 走独立键，本地/云端走原键
    private suspend fun persistRepoPath(repoPath: String) {
        if (isOtgPath(repoPath)) context.saveResticOtgRepoPath(repoPath)
        else context.saveResticRepoPath(repoPath)
    }

    private suspend fun persistPassword(repoPath: String, password: String) {
        if (isOtgPath(repoPath)) context.saveResticOtgPassword(password)
        else context.saveResticPassword(password)
    }

    // 统一读密码：OTG 读 OTG 键，其余读本地键；均缺省 databackup_default
    private suspend fun readPasswordFor(repoPath: String): String =
        (if (isOtgPath(repoPath)) context.readResticOtgPassword() else context.readResticPassword())
            ?: "databackup_default"

    override suspend fun onEvent(state: ResticUiState, intent: ResticUiIntent) {}

    companion object {
        private const val TAG = "ResticViewModel"
        private const val OTG_PREFIX = "/mnt/media_rw/"
        /** 仓库目录名（必须与首次初始化引导、仓库定位器保持一致）。 */
        private const val RESTIC_REPO_DIR_NAME = "restic_repo"
        /** 建库前要求的最小可用空间（低于此值直接提示，避免建到一半失败）。 */
        private const val MIN_FREE_SPACE_BYTES = 200L * 1024 * 1024

        /** 与首次初始化引导保持一致的默认仓库密码（无任何自定义密码时使用）。 */
        const val DEFAULT_RESTIC_PASSWORD = "databackup_default"
    }
}