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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
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
    // 修改点 1: 初始值设为 None，防止进入页面立即弹窗
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.None)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    // 修改点 2: 统一定义 DownloadState，增加 None 状态 (删除了类中后面重复的定义)
    sealed class DownloadState {
        object None : DownloadState()      // 默认静默状态，不触发 UI
        object Idle : DownloadState()      // 确认需要下载时设为此状态，触发 UI 弹窗
        object Downloading : DownloadState()
        data class Success(val path: String) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private val _downloadUrlState = MutableStateFlow<String>("")
    val downloadUrlState: StateFlow<String> = _downloadUrlState.asStateFlow()

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
            // 首先执行状态检查，确保拿到版本号（如果有的话）
            checkResticStatus()

            val version = _resticVersionState.value

            if (version == null) {
                // 确实没有版本，根据标记判断是否需要弹出下载
                if (resticNative.isDownloadNeeded(context)) {
                    _downloadState.value = DownloadState.Idle
                }
            } else {
                // 修改点 3: 已有版本的情况下，设为 None 保持静默
                // 这样 UI 既不会弹出输入框，也不会弹出“下载成功”的提示
                _downloadState.value = DownloadState.None
            }
        }
    }

    /**
     * 核心逻辑：状态检查
     * 合并为一个支持 withContext 的挂起函数，确保 init 块时序正确
     */
    suspend fun checkResticStatus() {
        withContext(Dispatchers.IO) {
            try {
                val binaryPath = resticNative.getResticBinaryPath(context)
                val binaryFile = File(binaryPath)

                Log.d(TAG, "DEBUG: 检查二进制文件 -> 路径: $binaryPath")

                // 1. 检查文件是否存在
                if (!binaryFile.exists()) {
                    Log.e(TAG, "DEBUG: 失败原因 -> 文件不存在")
                    _resticVersionState.value = null
                    return@withContext
                }

                // 2. 修复执行权限
                if (!binaryFile.canExecute()) {
                    Log.w(TAG, "DEBUG: 文件不可执行，尝试修复权限...")
                    binaryFile.setExecutable(true, false)
                }

                // 3. 获取版本号 (通过 libsu 执行)
                val version = resticRepo.getVersion()
                Log.d(TAG, "DEBUG: 尝试获取版本结果: $version")
                _resticVersionState.value = version

                // 4. 如果版本获取成功，清除下载标志位
                if (version != null) {
                    resticNative.clearDownloadFlag(context)
                } else {
                    return@withContext
                }

                // 5. 获取并同步仓库路径
                val repoPath = getRepoPath()
                _repoPathState.value = repoPath

                // 6. 检查仓库初始化状态与快照
                val password = getResticPassword()
                val isInitialized = resticRepo.checkRepository(repoPath, password)

                _resticInitializedState.value = isInitialized
                _resticRepoPathState.value = if (isInitialized) repoPath else ""

                if (isInitialized) {
                    val snapshots = resticRepo.listSnapshots(repoPath, password)
                    _resticSnapshotCountState.value = snapshots.size
                } else {
                    _resticSnapshotCountState.value = 0
                }

            } catch (e: Exception) {
                Log.e(TAG, "DEBUG: checkResticStatus 异常", e)
                _resticVersionState.value = null
                _resticInitializedState.value = false
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

    // --- 下载管理 ---
    suspend fun downloadResticBinary(url: String): Boolean {
        _downloadState.value = DownloadState.Downloading
        return withContext(Dispatchers.IO) {
            try {
                val targetFile = File(resticNative.getResticBinaryPath(context))
                if (targetFile.exists()) targetFile.delete()

                downloadFile(url, targetFile)

                // 设置所有组可执行，libsu 的 Shell 才能运行它
                targetFile.setReadable(true, false)
                targetFile.setExecutable(true, false)

                resticNative.clearDownloadFlag(context)

                // 这里保持 Success，因为这是真正的下载动作完成
                _downloadState.value = DownloadState.Success(targetFile.absolutePath)

                delay(300)
                checkResticStatus()
                true
            } catch (e: Exception) {
                Log.e(TAG, "下载Restic失败", e)
                _downloadState.value = DownloadState.Error(e.message ?: "下载失败")
                false
            }
        }
    }

    private fun downloadFile(url: String, targetFile: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        connection.connect()

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("HTTP错误: ${connection.responseCode}")
        }

        connection.inputStream.use { input ->
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
        }
    }

    fun getFullPathFromUri(uri: Uri): String? {
        val path = uri.path ?: return null
        return when {
            path.contains("primary:") -> {
                "/storage/emulated/0/${path.split("primary:")[1]}"
            }
            path.contains(":") -> {
                val parts = path.split(":")
                val diskId = parts[0].split("/").last()
                val relativePath = parts[1]
                "/storage/$diskId/$relativePath"
            }
            else -> null
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

    fun setDownloadUrl(url: String) { _downloadUrlState.value = url }

    override suspend fun onEvent(state: ResticUiState, intent: ResticUiIntent) {}

    companion object {
        private const val TAG = "ResticViewModel"
    }
}