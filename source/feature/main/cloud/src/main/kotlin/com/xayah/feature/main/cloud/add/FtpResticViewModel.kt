package com.xayah.feature.main.cloud.add

import android.content.Context
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.viewModelScope
import com.xayah.core.datastore.readFtpResticInitialized
import com.xayah.core.datastore.readFtpResticPassword
import com.xayah.core.datastore.readFtpResticRepoPath
import com.xayah.core.datastore.saveFtpResticInitialized
import com.xayah.core.datastore.saveFtpResticPassword
import com.xayah.core.datastore.saveFtpResticRepoPath
import com.xayah.core.model.CloudType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.FTPExtra
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.restic.ResticNative
import com.xayah.core.restic.ResticRepositoryFtp
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xayah.core.util.GsonUtil
import javax.inject.Inject

data object FtpResticUiState : UiState

sealed class FtpResticUiIntent : UiIntent

@ExperimentalMaterial3Api
@HiltViewModel
class FtpResticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepoFtp: ResticRepositoryFtp,
    private val rootService: RemoteRootService,
    private val resticNative: ResticNative
) : BaseViewModel<FtpResticUiState, FtpResticUiIntent, IndexUiEffect>(FtpResticUiState) {

    companion object {
        private const val TAG = "FtpResticViewModel"
    }

    private val _ftpInitializationState = MutableStateFlow<FtpInitializationState>(FtpInitializationState.Idle)
    val ftpInitializationState: StateFlow<FtpInitializationState> = _ftpInitializationState.asStateFlow()

    private val _ftpPasswordState = MutableStateFlow("")
    val ftpPasswordState: StateFlow<String> = _ftpPasswordState.asStateFlow()

    sealed class FtpInitializationState {
        object Idle : FtpInitializationState()
        object Initializing : FtpInitializationState()
        data class Success(val repoPath: String) : FtpInitializationState()
        data class Error(val message: String) : FtpInitializationState()
    }

    init {
        viewModelScope.launch {
            // 初始化时加载已保存的密码
            val savedPassword = context.readFtpResticPassword() ?: ""
            _ftpPasswordState.value = savedPassword

            // 检查初始化状态
            val isInitialized = context.readFtpResticInitialized()
            if (isInitialized) {
                val repoPath = context.readFtpResticRepoPath() ?: ""
                _ftpInitializationState.value = FtpInitializationState.Success(repoPath)
            }
        }
    }

    /**
     * 从既有账户 CloudEntity 恢复 FTP restic 初始化状态。
     * 导入配置时只写回 CloudEntity（含 extra 里的 resticPassword），
     * 不会写全局 DataStore，故这里改从实体反查恢复，行为对齐 SFTP。
     *
     * 规则：解析出的 resticPassword 非空即视为"之前已初始化过"，
     * 把密码回填 UI 状态，并把初始化状态置为 Success(remote)。
     */
    fun restoreStateFromEntity(cloudEntity: CloudEntity) {
        if (cloudEntity.type != CloudType.FTP) return
        if (_ftpInitializationState.value !is FtpInitializationState.Idle) return

        val ftpExtra = cloudEntity.getExtraEntity<FTPExtra>() ?: return
        val savedPassword = ftpExtra.resticPassword
        if (savedPassword.isNotEmpty()) {
            _ftpPasswordState.value = savedPassword
            _ftpInitializationState.value = FtpInitializationState.Success(cloudEntity.remote)
            Log.d(TAG, "已从账户恢复 FTP Restic 初始化状态: ${cloudEntity.remote}")
        }
    }

    /**
     * 初始化 FTP Restic 仓库。
     * 与 S3 不同：ResticRepositoryFtp.initFtpRepository 收 CloudEntity（host/user/pass 在实体上，
     * port 在 FTPExtra），因此这里用 UI 字段拼出一个临时 CloudEntity 传入。
     * extra 用 kotlinx.serialization 序列化 FTPExtra，保证 initFtpRepository 内部
     * ResticShared.json.decodeFromString<FTPExtra> 能正确解析。
     */
    suspend fun initializeFtpRepository(
        name: String,
        host: String,
        port: Int,
        username: String,
        pass: String,
        remotePath: String,
        password: String
    ): Boolean {
        Log.d(TAG, "开始初始化FTP Restic仓库: $remotePath")
        _ftpInitializationState.value = FtpInitializationState.Initializing

        return withContext(Dispatchers.IO) {
            try {
                // 用 UI 字段构造临时 CloudEntity（remote 与 remotePath 保持一致，
                // 避免 init 建库路径与后续 backup/list 路径不匹配）
                val ftpExtra = FTPExtra(port = port, resticPassword = password)
                val cloudEntity = CloudEntity(
                    name = name,
                    type = CloudType.FTP,
                    host = host,
                    user = username,
                    pass = pass,
                    remote = remotePath,
                    extra = GsonUtil().toJson(ftpExtra),
                    activated = false,
                )

                // 迁移到进程内 JNI：由 ResticRepositoryFtp.initFtpRepository 构建
                // opendal:ftp 后端 options（endpoint/root/user/password），
                // restic 仓库密码单独传入，经 AIDL → RemoteRootServiceImpl → Rustic 在 root 进程完成 init。
                val result = resticRepoFtp.initFtpRepository(cloudEntity, remotePath, password)

                if (result.isSuccess) {
                    // 保存配置到DataStore
                    context.saveFtpResticPassword(password)
                    context.saveFtpResticInitialized(true)
                    context.saveFtpResticRepoPath(remotePath)

                    _ftpInitializationState.value = FtpInitializationState.Success(remotePath)
                    Log.d(TAG, "FTP Restic仓库初始化成功")
                    true
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "初始化失败"
                    _ftpInitializationState.value = FtpInitializationState.Error(errorMsg)
                    Log.e(TAG, "FTP Restic仓库初始化失败: $errorMsg")
                    false
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "未知错误"
                _ftpInitializationState.value = FtpInitializationState.Error(errorMsg)
                Log.e(TAG, "FTP Restic仓库初始化异常", e)
                false
            }
        }
    }

    fun saveFtpPassword(password: String) {
        Log.d(TAG, "保存FTP Restic密码")
        _ftpPasswordState.value = password
        viewModelScope.launch {
            try {
                context.saveFtpResticPassword(password)
                Log.d(TAG, "FTP Restic密码保存成功")
            } catch (e: Exception) {
                Log.e(TAG, "FTP Restic密码保存失败", e)
            }
        }
    }

    override suspend fun onEvent(state: FtpResticUiState, intent: FtpResticUiIntent) {
        // 暂时不需要处理特定的UI意图
    }
}