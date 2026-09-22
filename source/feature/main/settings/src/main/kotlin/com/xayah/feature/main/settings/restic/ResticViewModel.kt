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
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.restic.ResticNative
import com.xayah.core.restic.ResticRepository
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.command.SELinux
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
    }

    /**
     * 核心逻辑：状态检查
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

                // 3. 获取并同步仓库路径
                val repoPath = getRepoPath()
                _repoPathState.value = repoPath

                // 3.5 换新机 bootstrap：DataStore 里没有 config_id（从未登记过）时，
                //     尝试用 root 自动发现 OTG 仓库并零交互登记。
                //     仅当 bootstrap 已接管（进入 NeedsPassword/SelectRepository/成功登记）时提前返回，
                //     否则继续走常规校验。
                if (context.readResticRepoConfigId() == null) {
                    if (bootstrapFromOtgIfEmpty()) {
                        return@withContext
                    }
                }

                // 4. 仓库校验 / 快照数
                val password = getResticPassword()
                val isInitialized = resticRepo.checkRepository(repoPath, password)
                _resticInitializedState.value = isInitialized

                Log.w(TAG, "checkResticStatus: repoPath=$repoPath, checkOk=$isInitialized")

                if (!isInitialized) {
                    // OTG 前缀走“保留 + 离线态/重对齐”，内部存储保持原清空逻辑
                    if (repoPath.startsWith(OTG_PREFIX)) {
                        handleOtgStatus(repoPath)
                    } else {
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
     * OTG 已登记设备：checkRepository 失败时按 config_id 身份扫描重对齐。
     * - Matched（一致）：直接放行；Matched（不一致）：更新 DataStore 路径后放行。
     * - NotFound：没插盘/无此仓库 —— 保留旧配置，不清空，置为离线态。
     * - Ambiguous：多盘命中 —— 交给用户选择。
     */
    private suspend fun handleOtgStatus(repoPath: String) {
        val savedConfigId = context.readResticRepoConfigId()
        when (val r = resticRepoLocator.resolveCurrentResticRepoPath(repoPath, savedConfigId)) {
            is ResolveResult.Matched -> {
                if (r.changed) {
                    context.saveResticRepoPath(r.path)
                    _repoPathState.value = r.path
                }
                // 路径已对齐，重新校验一次
                val password = getResticPassword()
                val ok = resticRepo.checkRepository(r.path, password)
                if (ok) {
                    _resticInitializedState.value = true
                    _resticRepoPathState.value = r.path
                    _resticErrorState.value = null
                    _initializationState.value = InitializationState.ReadyToUse(r.path)
                    val snapshots = resticRepo.listSnapshots(r.path, password)
                    _resticSnapshotCountState.value = snapshots.size
                } else {
                    _resticRepoPathState.value = r.path
                    _resticSnapshotCountState.value = 0
                    _resticErrorState.value = context.getString(com.xayah.core.data.R.string.pre_backup_repository_check_failed)
                    _initializationState.value = InitializationState.PasswordError(r.path)
                }
            }
            is ResolveResult.NotFound -> {
                // 没插盘/换了空盘：保留旧配置，绝不清空，置离线态
                Log.w(TAG, "handleOtgStatus: 未扫到目标 OTG 仓库，保留配置并置离线: $repoPath")
                _resticRepoPathState.value = repoPath
                _resticSnapshotCountState.value = 0
                _resticErrorState.value = context.getString(com.xayah.core.data.R.string.restic_otg_offline)
                _initializationState.value = InitializationState.Offline(repoPath)
            }
            is ResolveResult.Ambiguous -> {
                Log.w(TAG, "handleOtgStatus: 多个 OTG 仓库命中，需用户选择")
                _resticErrorState.value = context.getString(com.xayah.core.data.R.string.restic_otg_ambiguous)
                _initializationState.value = InitializationState.SelectRepository(
                    r.candidates.map { DiscoveredRepo(path = it, configId = savedConfigId ?: "") }
                )
            }
            is ResolveResult.Passthrough -> {
                // 理论上不会走到（前缀已判过），保守置离线
                _initializationState.value = InitializationState.Offline(repoPath)
            }
        }
    }

    // ================= 换新机自动 bootstrap =================

    /**
     * 换新机/全新安装（DataStore 无 config_id）时的自动发现登记。
     * 由 checkResticStatus 在 config_id == null 时调用。
     *
     * @return true 表示 bootstrap 已接管本次流程（登记成功 / 已置 NeedsPassword / SelectRepository / 未发现），
     *         调用方应提前返回，不再走常规校验；false 表示无需 bootstrap（非首次或异常），继续常规流程。
     */
    private suspend fun bootstrapFromOtgIfEmpty(): Boolean {
        // 双保险：仅在 DataStore 为空时执行
        if (context.readResticRepoConfigId() != null) return false

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
                _initializationState.value = InitializationState.SelectRepository(discovered)
                true
            }
        }
    }

    /**
     * 尝试用默认密码零交互登记单个候选；默认密码不可解则弹密码框。
     */
    private suspend fun tryRegisterDiscovered(repo: DiscoveredRepo) {
        val defaultPassword = "databackup_default"
        _initializationState.value = InitializationState.Validating
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
            _initializationState.value = InitializationState.NeedsPassword(repo.path, repo.configId)
        }
    }

    /**
     * 用户在 NeedsPassword 状态下输入密码后校验并登记。
     */
    fun submitOtgPassword(password: String) {
        viewModelScope.launch {
            val state = _initializationState.value
            if (state !is InitializationState.NeedsPassword) return@launch
            _initializationState.value = InitializationState.Validating
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
                    _resticErrorState.value = context.getString(com.xayah.core.data.R.string.pre_backup_repository_check_failed)
                    _initializationState.value = InitializationState.NeedsPassword(state.repoPath, state.configId)
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
            _initializationState.value = InitializationState.Checking
            withContext(Dispatchers.IO) {
                val discovered = try {
                    resticRepoLocator.discoverOtgRepositories()
                } catch (e: Exception) {
                    Log.e(TAG, "scanOtgRepositories: 发现失败", e)
                    emptyList()
                }
                when {
                    discovered.isEmpty() -> {
                        val savedPath = context.readResticRepoPath() ?: ""
                        _resticErrorState.value = context.getString(com.xayah.core.data.R.string.restic_otg_offline)
                        _initializationState.value = InitializationState.Offline(savedPath)
                    }
                    discovered.size == 1 -> tryRegisterDiscovered(discovered.first())
                    else -> _initializationState.value = InitializationState.SelectRepository(discovered)
                }
            }
        }
    }

    /**
     * 取消 bootstrap，回到 Idle。
     */
    fun cancelOtgBootstrap() {
        _initializationState.value = InitializationState.Idle
        _resticErrorState.value = null
    }

    /**
     * bootstrap 校验通过后统一持久化：路径 + config_id + 密码，并刷新状态。
     */
    private suspend fun persistBootstrapResult(repoPath: String, configId: String, password: String) {
        context.saveResticRepoPath(repoPath)
        context.saveResticRepoConfigId(configId)
        context.saveResticPassword(password)
        _repoPathState.value = repoPath
        _resticRepoPathState.value = repoPath
        _resticInitializedState.value = true
        _resticErrorState.value = null
        _initializationState.value = InitializationState.ReadyToUse(repoPath)
        val snapshots = try {
            resticRepo.listSnapshots(repoPath, password)
        } catch (e: Exception) {
            emptyList()
        }
        _resticSnapshotCountState.value = snapshots.size
        Log.d(TAG, "persistBootstrapResult: 已登记 OTG 仓库身份 configId=$configId path=$repoPath")
    }

    // --- 核心逻辑：初始化与验证 ---
    suspend fun initializeOrValidateRepository(selectedPath: String): Boolean {
        _initializationState.value = InitializationState.Checking
        val repoPath = File(selectedPath, "restic_repo").absolutePath
        val password = getResticPassword()

        return withContext(Dispatchers.IO) {
            try {
                if (File(repoPath).exists()) {
                    _initializationState.value = InitializationState.Validating
                    // 验证密码逻辑
                    if (resticRepo.validateRepository(repoPath, password)) {
                        _resticInitializedState.value = true
                        _resticRepoPathState.value = repoPath
                        _repoPathState.value = repoPath

                        val snapshots = resticRepo.listSnapshots(repoPath, password)
                        _resticSnapshotCountState.value = snapshots.size

                        context.saveResticRepoPath(repoPath)
                        context.saveResticPassword(password)
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
                        _resticInitializedState.value = true
                        _resticRepoPathState.value = repoPath
                        _resticSnapshotCountState.value = 0
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
            context.saveResticRepoPath(repoPath)
            context.saveResticPassword(password)
            // 创建成功后同样登记 config_id
            saveRepoConfigId(repoPath)
            _repoPathState.value = repoPath
            checkResticStatus()
        }
    }

    /**
     * 读取并保存指定仓库的 config_id 身份锚点。
     * 建库/校验成功、OTG 处于挂载状态时调用，此刻读 config_id 必然成功。
     */
    private suspend fun saveRepoConfigId(repoPath: String) {
        try {
            val id = rootService.rusticRepositoryConfigId(repoPath)
            if (!id.isNullOrEmpty()) {
                context.saveResticRepoConfigId(id)
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

    /**
     * 进入“重新初始化”模式：仅切换 UI 显示，不清空已持久化的仓库路径/密码。
     */
    fun enterReinitializeMode() {
        _isReinitializing.value = true
        _initializationState.value = InitializationState.Idle
    }

    /**
     * 退出“重新初始化”模式（用户中途返回时重置标志），不改动任何持久化数据。
     */
    fun exitReinitializeMode() {
        _isReinitializing.value = false
    }

    suspend fun deleteAndReinitializeRepository(repoPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            if (resticRepo.deleteRepository(repoPath)) {
                initializeRepository(repoPath, getResticPassword())
            } else false
        }
    }

    suspend fun getRepoPath(): String {
        return context.readResticRepoPath() ?: File(context.filesDir, "restic_repo").absolutePath
    }

    private suspend fun getResticPassword(): String {
        return context.readResticPassword() ?: "databackup_default"
    }

    override suspend fun onEvent(state: ResticUiState, intent: ResticUiIntent) {}

    companion object {
        private const val TAG = "ResticViewModel"
        private const val OTG_PREFIX = "/mnt/media_rw/"
    }
}