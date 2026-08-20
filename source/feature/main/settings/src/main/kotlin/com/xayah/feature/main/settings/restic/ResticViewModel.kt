package com.xayah.feature.main.settings.restic

import android.net.Uri
import android.content.Context
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.datastore.saveResticPassword
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
    private val resticNative: ResticNative
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
            try {
                // 1. 获取版本号（通过 JNI：Rustic.getVersion → RootService）
                //    不再检查二进制文件是否存在/可执行
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

                // 4. 仓库校验 / 快照数（注意：checkRepository / listSnapshots 仍走 CLI，
                //    不影响上面 getVersion 的验证结论）
                val password = getResticPassword()
                val isInitialized = resticRepo.checkRepository(repoPath, password)
                _resticInitializedState.value = isInitialized

                if (!isInitialized) {
                    // 仓库不存在，清除已保存的路径
                    context.saveResticRepoPath("")
                    _resticRepoPathState.value = ""
                    _resticSnapshotCountState.value = 0
                } else {
                    _resticRepoPathState.value = repoPath
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

                        _initializationState.value = InitializationState.ReadyToUse(repoPath)
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

                // 处理结果
                if (initResult.isSuccess) {
                    // 只有成功了才保存状态
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
            _repoPathState.value = repoPath
            checkResticStatus()
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
            _repoPathState.value = ""
            _resticInitializedState.value = false
            _resticSnapshotCountState.value = 0
            _initializationState.value = InitializationState.Idle
        }
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
    }
}