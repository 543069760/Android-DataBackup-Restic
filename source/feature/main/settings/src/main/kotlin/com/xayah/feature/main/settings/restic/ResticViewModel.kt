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
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

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

    sealed class DownloadState {
        object Idle : DownloadState()
        object Downloading : DownloadState()
        data class Success(val path: String) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

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
            if (resticNative.isDownloadNeeded(context)) {
                _downloadState.value = DownloadState.Idle
            }
        }
    }

    // --- 核心逻辑：状态检查 ---
    suspend fun checkResticStatus() {
        launchOnIO {
            try {
                val binaryPath = resticNative.getResticBinaryPath(context)
                val binaryFile = File(binaryPath)

                Log.d(TAG, "DEBUG: 检查二进制文件 -> 路径: $binaryPath")

                if (!binaryFile.exists()) {
                    Log.e(TAG, "DEBUG: 失败原因 -> 文件不存在")
                    _resticVersionState.value = null
                    return@launchOnIO
                }

                // 即使存在，Root Shell 执行也需要 +x 权限
                if (!binaryFile.canExecute()) {
                    Log.w(TAG, "DEBUG: 文件不可执行，尝试修复权限...")
                    binaryFile.setExecutable(true, false)
                }

                // 1. 获取版本 (通过 libsu 执行)
                val version = resticRepo.getVersion()
                Log.d(TAG, "DEBUG: 尝试获取版本结果: $version")
                _resticVersionState.value = version

                // 2. 获取并同步仓库路径
                val repoPath = getRepoPath()
                _repoPathState.value = repoPath

                // 3. 检查仓库初始化状态与快照
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
    // 在 ResticViewModel.kt 中添加
    fun getFullPathFromUri(uri: Uri): String? {
        val path = uri.path ?: return null
        return when {
            // 处理外部存储 (sdcard): content://.../tree/primary:Documents
            path.contains("primary:") -> {
                "/storage/emulated/0/${path.split("primary:")[1]}"
            }
            // 处理其他挂载点 (如外部 SD 卡): content://.../tree/1234-ABCD:Backup
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

    /**
     * 获取当前保存的密码
     */
    suspend fun getPassword(): String = getResticPassword()

    /**
     * 保存密码并重新检查仓库状态
     */
    fun savePassword(password: String) {
        viewModelScope.launch {
            context.saveResticPassword(password)
            checkResticStatus()
        }
    }

    /**
     * 保存仓库路径并更新 UI 状态
     */
    fun saveRepoPath(path: String) {
        val sanitizedPath = path.trim() // 防止末尾空格导致 shell 命令失效
        viewModelScope.launch {
            // 先更新 DataStore
            context.saveResticRepoPath(sanitizedPath)
            _repoPathState.value = sanitizedPath

            // 切换到 IO 线程执行 Shell
            withContext(Dispatchers.IO) {
                // libsu 的命令执行
                Shell.cmd(
                    "mkdir -p '$sanitizedPath'",
                    "chmod 777 '$sanitizedPath'",
                    "restorecon -R '$sanitizedPath'"
                ).exec()
            }

            // 最后同步状态
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