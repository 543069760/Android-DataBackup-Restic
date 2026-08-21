package com.xayah.feature.main.cloud.add

import android.content.Context
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.model.CloudType
import com.xayah.core.model.SFTPAuthMode
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.SFTPExtra
import com.xayah.core.restic.ResticRepositorySftp
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.GsonUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

data object SftpResticUiState : UiState

sealed class SftpResticUiIntent : UiIntent

@ExperimentalMaterial3Api
@HiltViewModel
class SftpResticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepoSftp: ResticRepositorySftp,
    private val rootService: RemoteRootService,
) : BaseViewModel<SftpResticUiState, SftpResticUiIntent, IndexUiEffect>(SftpResticUiState) {

    companion object {
        private const val TAG = "SftpResticViewModel"
    }

    private val _sftpInitializationState = MutableStateFlow<SftpInitializationState>(SftpInitializationState.Idle)
    val sftpInitializationState: StateFlow<SftpInitializationState> = _sftpInitializationState.asStateFlow()

    private val _sftpPasswordState = MutableStateFlow("")
    val sftpPasswordState: StateFlow<String> = _sftpPasswordState.asStateFlow()

    sealed class SftpInitializationState {
        object Idle : SftpInitializationState()
        object Initializing : SftpInitializationState()
        data class Success(val repoPath: String) : SftpInitializationState()
        data class Error(val message: String) : SftpInitializationState()
    }

    /**
     * 初始化 SFTP Restic 仓库。
     * 对照 FtpResticViewModel.initializeFtpRepository：用 UI 字段拼出临时 CloudEntity，
     * extra 用 GsonUtil 序列化 SFTPExtra（含 resticPassword），再交给 ResticRepositorySftp。
     * 区别：SFTP 的 restic 密码按账户存入 SFTPExtra.resticPassword，不写任何全局 datastore；
     * ResticRepositorySftp 内部会经 RcloneServe 起 serve 拿 rest: URL 再 init。
     */
    suspend fun initializeSftpRepository(
        name: String,
        host: String,
        port: Int,
        username: String,
        pass: String,
        remotePath: String,
        password: String,
        mode: SFTPAuthMode,
        privateKey: String,
    ): Boolean {
        Log.d(TAG, "开始初始化SFTP Restic仓库: $remotePath")
        _sftpInitializationState.value = SftpInitializationState.Initializing

        return withContext(Dispatchers.IO) {
            try {
                // 用 UI 字段构造临时 CloudEntity（remote 与 remotePath 保持一致，
                // 避免 init 建库路径与后续 backup/list 路径不匹配）
                val sftpExtra = SFTPExtra(
                    port = port,
                    privateKey = privateKey,
                    mode = mode,
                    resticPassword = password,
                )
                val cloudEntity = CloudEntity(
                    name = name,
                    type = CloudType.SFTP,
                    host = host,
                    user = username,
                    pass = pass,
                    remote = remotePath,
                    extra = GsonUtil().toJson(sftpExtra),
                    activated = false,
                )

                // ResticRepositorySftp 内部经 RcloneServe 起 serve restic 拿 rest: URL，
                // restic 仓库密码单独传入，在 root 进程完成 init。
                val result = resticRepoSftp.initSftpRepository(cloudEntity, remotePath, password)

                if (result.isSuccess) {
                    // SFTP 密码走账户级（SFTPExtra.resticPassword），仅回写 UI 状态，不写全局 datastore
                    _sftpPasswordState.value = password
                    _sftpInitializationState.value = SftpInitializationState.Success(remotePath)
                    Log.d(TAG, "SFTP Restic仓库初始化成功")
                    true
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "初始化失败"
                    _sftpInitializationState.value = SftpInitializationState.Error(errorMsg)
                    Log.e(TAG, "SFTP Restic仓库初始化失败: $errorMsg")
                    false
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "未知错误"
                _sftpInitializationState.value = SftpInitializationState.Error(errorMsg)
                Log.e(TAG, "SFTP Restic仓库初始化异常", e)
                false
            }
        }
    }

    override suspend fun onEvent(state: SftpResticUiState, intent: SftpResticUiIntent) {
        // 暂时不需要处理特定的UI意图
    }
}