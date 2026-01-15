package com.xayah.feature.main.settings.rclone

import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.xayah.core.rclone.RcloneNative
import com.xayah.core.rclone.RcloneRepository
import com.xayah.core.rclone.RcloneLogger
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.datastore.readRclonePort
import com.xayah.core.database.dao.CloudDao
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject

data object RcloneUiState : UiState

sealed class RcloneUiIntent : UiIntent

@HiltViewModel
class RcloneViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rcloneRepo: RcloneRepository,
    private val rootService: RemoteRootService,
    private val rcloneNative: RcloneNative,
    private val cloudDao: CloudDao
) : BaseViewModel<RcloneUiState, RcloneUiIntent, IndexUiEffect>(RcloneUiState) {

    companion object {
        private const val TAG = "RcloneViewModel"
    }

    // --- 版本检测状态流 ---
    private val _rcloneVersionState = MutableStateFlow<String?>(null)
    val rcloneVersionState: StateFlow<String?> = _rcloneVersionState.asStateFlow()

    // --- 下载状态管理 ---
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.None)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    sealed class DownloadState {
        object None : DownloadState()
        object Idle : DownloadState()
        object Downloading : DownloadState()
        data class Success(val path: String) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private val _downloadUrlState = MutableStateFlow<String>("")
    val downloadUrlState: StateFlow<String> = _downloadUrlState.asStateFlow()

    // --- 服务器状态管理 ---
    private val _serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    sealed class ServerState {
        object Stopped : ServerState()
        object Starting : ServerState()
        data class Running(val addr: String) : ServerState()
        object Stopping : ServerState()
        data class Error(val message: String) : ServerState()
    }

    private val _selectedRemoteState = MutableStateFlow<String?>(null)
    val selectedRemoteState: StateFlow<String?> = _selectedRemoteState.asStateFlow()

    init {
        viewModelScope.launch {
            // 首先执行状态检查，确保拿到版本号（如果有的话）
            checkRcloneStatus()

            val version = _rcloneVersionState.value

            if (version == null) {
                // 确实没有版本，根据标记判断是否需要弹出下载
                if (rcloneNative.isDownloadNeeded(context)) {
                    _downloadState.value = DownloadState.Idle
                }
            } else {
                // 已有版本的情况下，设为 None 保持静默
                _downloadState.value = DownloadState.None
            }
        }
    }

    /**
     * 核心状态检查方法
     */
    suspend fun checkRcloneStatus() {
        withContext(Dispatchers.IO) {
            try {
                val binaryPath = rcloneNative.getRcloneBinaryPath(context)
                val binaryFile = java.io.File(binaryPath)

                Log.d(TAG, "检查 rclone 二进制文件: $binaryPath")

                if (!binaryFile.exists()) {
                    Log.e(TAG, "rclone 二进制文件不存在")
                    _rcloneVersionState.value = null
                    return@withContext
                }

                if (!binaryFile.canExecute()) {
                    Log.w(TAG, "修复 rclone 二进制文件权限")
                    binaryFile.setExecutable(true, false)
                }

                val version = rcloneRepo.getVersion()
                Log.d(TAG, "rclone 版本: $version")
                _rcloneVersionState.value = version

                if (version != null) {
                    rcloneNative.clearDownloadFlag(context)
                    checkServerStatus()
                } else {
                    return@withContext
                }

            } catch (e: Exception) {
                Log.e(TAG, "检查 rclone 状态异常", e)
                _rcloneVersionState.value = null
                _serverState.value = ServerState.Stopped
            }
        }
    }

    /**
     * 检查服务器状态
     */
    suspend fun checkServerStatus() {
        withContext(Dispatchers.IO) {
            try {
                val isRunning = rcloneRepo.checkServerStatus()
                if (isRunning) {
                    val addr = rcloneRepo.getServerAddress()
                    val port = context.readRclonePort().first()
                    _serverState.value = ServerState.Running(addr ?: "localhost:$port")
                } else {
                    _serverState.value = ServerState.Stopped
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查服务器状态异常", e)
                _serverState.value = ServerState.Error(e.message ?: "未知错误")
            }
        }
    }

    /**
     * 启动 Rclone 服务器
     */
    suspend fun startRcloneServer(remote: String, path: String = "") {
        _serverState.value = ServerState.Starting
        withContext(Dispatchers.IO) {
            try {
                // 添加端口可用性检测
                val port = context.readRclonePort().first()
                val isPortAvailable = checkPortAvailable(port)

                if (!isPortAvailable) {
                    _serverState.value = ServerState.Error("端口 $port 已被占用")
                    emitEffect(IndexUiEffect.ShowSnackbar("端口 $port 已被占用"))
                    return@withContext
                }

                val result = rcloneRepo.startRcloneServer(remote, path)
                if (result.isSuccess) {
                    delay(1000)
                    checkServerStatus()
                } else {
                    _serverState.value = ServerState.Error("启动失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动服务器异常", e)
                _serverState.value = ServerState.Error(e.message ?: "启动失败")
            }
        }
    }

    /**
     * 停止 Rclone 服务器
     */
    suspend fun stopRcloneServer() {
        _serverState.value = ServerState.Stopping
        withContext(Dispatchers.IO) {
            try {
                val result = rcloneRepo.stopRcloneServer() // 更新方法名
                if (result.isSuccess) {
                    _serverState.value = ServerState.Stopped
                } else {
                    _serverState.value = ServerState.Error("停止失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "停止服务器异常", e)
                _serverState.value = ServerState.Error(e.message ?: "停止失败")
            }
        }
    }

    /**
     * 启动 Rclone 服务器（带端口检测）
     */
    suspend fun startRcloneServerWithCheck(): Boolean {
        return try {
            Log.d(TAG, "开始启动 Rclone 服务器")

            // 1. 停止现有服务
            Log.d(TAG, "停止现有服务")
            stopRcloneServer()

            // 2. 获取云配置
            Log.d(TAG, "获取云配置")
            val cloudEntities = cloudDao.queryActivated()
            if (cloudEntities.isEmpty()) {
                Log.e(TAG, "没有找到激活的云配置")
                emitEffect(IndexUiEffect.ShowSnackbar("请先配置云存储账户"))
                return false
            }
            val selectedRemote = cloudEntities.first().name
            Log.d(TAG, "使用远程配置: $selectedRemote")

            // 3. 检查配置文件（移动到这里）
            val configFile = File(context.filesDir, "rclone/rclone.conf")
            if (!configFile.exists()) {
                emitEffect(IndexUiEffect.ShowSnackbar("rclone.conf 配置文件不存在"))
                return false
            }
            Log.d(TAG, "配置文件内容: ${configFile.readText()}")

            // 4. 检测端口可用性
            Log.d(TAG, "检测端口可用性")
            val port = context.readRclonePort().first()
            val isPortAvailable = checkPortAvailable(port)
            Log.d(TAG, "端口 $port 可用性: $isPortAvailable")

            if (!isPortAvailable) {
                emitEffect(IndexUiEffect.ShowSnackbar("端口 $port 已被占用"))
                return false
            }

            // 5. 启动服务
            Log.d(TAG, "启动 Rclone 服务")
            val result = rcloneRepo.startRcloneServer(
                remote = selectedRemote,
                path = ""
            )
            Log.d(TAG, "启动结果: ${result.isSuccess}")

            if (result.isSuccess) {
                emitEffect(IndexUiEffect.ShowSnackbar("Rclone 服务启动成功"))
                true
            } else {
                emitEffect(IndexUiEffect.ShowSnackbar("Rclone 服务启动失败"))
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动服务异常", e)
            emitEffect(IndexUiEffect.ShowSnackbar("启动失败: ${e.message}"))
            false
        }
    }

    /**
     * 检测端口是否可用
     */
    private suspend fun checkPortAvailable(port: String): Boolean {
        return try {
            val portNum = port.toInt()
            val result = Shell.cmd("netstat -tlnp | grep :$port").exec()
            !result.isSuccess || result.out.isEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 下载 rclone 二进制文件
     */
    suspend fun downloadRcloneBinary(url: String): Boolean {
        _downloadState.value = DownloadState.Downloading
        return withContext(Dispatchers.IO) {
            try {
                val targetFile = java.io.File(rcloneNative.getRcloneBinaryPath(context))
                if (targetFile.exists()) targetFile.delete()

                downloadFile(url, targetFile)

                targetFile.setReadable(true, false)
                targetFile.setExecutable(true, false)

                rcloneNative.clearDownloadFlag(context)
                _downloadState.value = DownloadState.Success(targetFile.absolutePath)

                delay(300)
                checkRcloneStatus()
                true
            } catch (e: Exception) {
                Log.e(TAG, "下载 rclone 失败", e)
                _downloadState.value = DownloadState.Error(e.message ?: "下载失败")
                false
            }
        }
    }

    private fun downloadFile(url: String, targetFile: java.io.File) {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        connection.connect()

        if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
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

    /**
     * 设置选中的远程存储
     */
    fun setSelectedRemote(remote: String) {
        _selectedRemoteState.value = remote
    }

    fun setDownloadUrl(url: String) {
        _downloadUrlState.value = url
    }

    override suspend fun onEvent(state: RcloneUiState, intent: RcloneUiIntent) {}
}