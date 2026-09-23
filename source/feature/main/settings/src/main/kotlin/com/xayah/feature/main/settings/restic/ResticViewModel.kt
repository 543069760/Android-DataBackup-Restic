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
import com.xayah.core.restic.ResticNative
import com.xayah.core.restic.ResticRepository
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.command.SELinux
import com.xayah.core.util.command.PreparationUtil
import dagger.hilt.android.lifecycle.HiltViewModel
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
    suspend fun initializeOrValidateRepository(selectedPath: String): Boolean {
        _initializationState.value = InitializationState.Checking
        val repoPath = File(selectedPath, "restic_repo").absolutePath
        val password = readPasswordFor(repoPath)   // OTG 读 OTG 密码，本地读本地密码
        val otg = isOtgPath(repoPath)

        return withContext(Dispatchers.IO) {
            try {
                if (File(repoPath).exists()) {
                    _initializationState.value = InitializationState.Validating
                    // 验证密码逻辑
                    if (resticRepo.validateRepository(repoPath, password)) {
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

                        _initializationState.value = InitializationState.ReadyToUse(repoPath)
                        _isReinitializing.value = false
                        true
                    } else {
                        _initializationState.value = InitializationState.PasswordError(repoPath)
                        false
                    }
                } else {
                    // 仓库不存在，执行创建与初始化
                    _initializationState.value = InitializationState.Initializing
                    val initSuccess = initializeRepository(repoPath, password)

                    if (initSuccess) {
                        if (otg) {
                            _otgInitializedState.value = true
                            _otgRepoPathState.value = repoPath
                            _otgSnapshotCountState.value = 0
                        } else {
                            _resticInitializedState.value = true
                            _resticRepoPathState.value = repoPath
                            _resticSnapshotCountState.value = 0
                        }
                        _initializationState.value = InitializationState.ReadyToUse(repoPath)
                        _isReinitializing.value = false
                    } else {
                        _initializationState.value = InitializationState.Error("Initialization failed")
                    }
                    initSuccess
                }
            } catch (e: Exception) {
                _initializationState.value = InitializationState.Error(e.message ?: "Unknown error")
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
            if (!id.isNullOrEmpty()) {
                if (isOtgPath(repoPath)) context.saveResticOtgRepoConfigId(id)
                else context.saveResticRepoConfigId(id)
                Log.d(TAG, "saveRepoConfigId: 已保存 config_id=$id for $repoPath")
            } else {
                Log.w(TAG, "saveRepoConfigId: config_id 为空，跳过保存: $repoPath")
            }
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
    }
}