package com.xayah.feature.main.cloud.add

import android.content.Context
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.viewModelScope
import com.xayah.core.datastore.readWebdavResticInitialized
import com.xayah.core.datastore.readWebdavResticPassword
import com.xayah.core.datastore.readWebdavResticRepoPath
import com.xayah.core.datastore.saveWebdavResticInitialized
import com.xayah.core.datastore.saveWebdavResticPassword
import com.xayah.core.datastore.saveWebdavResticRepoPath
import com.xayah.core.model.CloudType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.WebDAVExtra
import com.xayah.core.model.database.WebDAVProtocol
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.restic.ResticNative
import com.xayah.core.restic.ResticRepositoryWebdav
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

data object WebdavResticUiState : UiState

sealed class WebdavResticUiIntent : UiIntent

@ExperimentalMaterial3Api
@HiltViewModel
class WebdavResticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepoWebdav: ResticRepositoryWebdav,
    private val rootService: RemoteRootService,
    private val resticNative: ResticNative
) : BaseViewModel<WebdavResticUiState, WebdavResticUiIntent, IndexUiEffect>(WebdavResticUiState) {

    companion object {
        private const val TAG = "WebdavResticViewModel"
    }

    private val _webdavInitializationState = MutableStateFlow<WebdavInitializationState>(WebdavInitializationState.Idle)
    val webdavInitializationState: StateFlow<WebdavInitializationState> = _webdavInitializationState.asStateFlow()

    private val _webdavPasswordState = MutableStateFlow("")
    val webdavPasswordState: StateFlow<String> = _webdavPasswordState.asStateFlow()

    sealed class WebdavInitializationState {
        object Idle : WebdavInitializationState()
        object Initializing : WebdavInitializationState()
        data class Success(val repoPath: String) : WebdavInitializationState()
        data class Error(val message: String) : WebdavInitializationState()
    }

    init {
        viewModelScope.launch {
            // 初始化时加载已保存的密码
            val savedPassword = context.readWebdavResticPassword() ?: ""
            _webdavPasswordState.value = savedPassword

            // 检查初始化状态
            val isInitialized = context.readWebdavResticInitialized()
            if (isInitialized) {
                val repoPath = context.readWebdavResticRepoPath() ?: ""
                _webdavInitializationState.value = WebdavInitializationState.Success(repoPath)
            }
        }
    }

    /**
     * 从既有账户 CloudEntity 恢复 WebDAV restic 初始化状态。
     * 导入配置时只写回 CloudEntity（含 extra 里的 resticPassword），
     * 不会写全局 DataStore，故这里改从实体反查恢复，行为对齐 SFTP。
     *
     * 规则：解析出的 resticPassword 非空即视为"之前已初始化过"，
     * 把密码回填 UI 状态，并把初始化状态置为 Success(remote)。
     */
    fun restoreStateFromEntity(cloudEntity: CloudEntity) {
        if (cloudEntity.type != CloudType.WEBDAV) return
        if (_webdavInitializationState.value !is WebdavInitializationState.Idle) return

        val webdavExtra = cloudEntity.getExtraEntity<WebDAVExtra>() ?: return
        val savedPassword = webdavExtra.resticPassword
        if (savedPassword.isNotEmpty()) {
            _webdavPasswordState.value = savedPassword
            _webdavInitializationState.value = WebdavInitializationState.Success(cloudEntity.remote)
            Log.d(TAG, "已从账户恢复 WebDAV Restic 初始化状态: ${cloudEntity.remote}")
        }
    }

    /**
     * 初始化 WebDAV Restic 仓库。
     * 与 S3 不同：ResticRepositoryWebdav.initWebdavRepository 收 CloudEntity（host/user/pass 在实体上，
     * insecure/protocol 在 WebDAVExtra），因此这里用 UI 字段拼出一个临时 CloudEntity 传入。
     * host 必须是"协议前缀 + 纯主机"拼好的完整 URL；extra 用 GsonUtil().toJson 序列化 WebDAVExtra。
     */
    suspend fun initializeWebdavRepository(
        name: String,
        host: String,
        username: String,
        pass: String,
        insecure: Boolean,
        protocol: WebDAVProtocol,
        remotePath: String,
        password: String
    ): Boolean {
        Log.d(TAG, "开始初始化WebDAV Restic仓库: $remotePath")
        _webdavInitializationState.value = WebdavInitializationState.Initializing

        return withContext(Dispatchers.IO) {
            try {
                // 用 UI 字段构造临时 CloudEntity（remote 与 remotePath 保持一致，
                // 避免 init 建库路径与后续 backup/list 路径不匹配）
                val webdavExtra = WebDAVExtra(
                    insecure = insecure,
                    protocol = protocol,
                    resticPassword = password,
                )
                val cloudEntity = CloudEntity(
                    name = name,
                    type = CloudType.WEBDAV,
                    host = host,
                    user = username,
                    pass = pass,
                    remote = remotePath,
                    extra = GsonUtil().toJson(webdavExtra),
                    activated = false,
                )

                // 迁移到进程内 JNI：由 ResticRepositoryWebdav.initWebdavRepository 构建
                // opendal:webdav 后端 options（endpoint/root/username/password），
                // restic 仓库密码单独传入，经 AIDL → RemoteRootServiceImpl → Rustic 在 root 进程完成 init。
                val result = resticRepoWebdav.initWebdavRepository(cloudEntity, remotePath, password)

                if (result.isSuccess) {
                    // 保存配置到DataStore
                    context.saveWebdavResticPassword(password)
                    context.saveWebdavResticInitialized(true)
                    context.saveWebdavResticRepoPath(remotePath)

                    _webdavInitializationState.value = WebdavInitializationState.Success(remotePath)
                    Log.d(TAG, "WebDAV Restic仓库初始化成功")
                    true
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "初始化失败"
                    _webdavInitializationState.value = WebdavInitializationState.Error(errorMsg)
                    Log.e(TAG, "WebDAV Restic仓库初始化失败: $errorMsg")
                    false
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "未知错误"
                _webdavInitializationState.value = WebdavInitializationState.Error(errorMsg)
                Log.e(TAG, "WebDAV Restic仓库初始化异常", e)
                false
            }
        }
    }

    fun saveWebdavPassword(password: String) {
        Log.d(TAG, "保存WebDAV Restic密码")
        _webdavPasswordState.value = password
        viewModelScope.launch {
            try {
                context.saveWebdavResticPassword(password)
                Log.d(TAG, "WebDAV Restic密码保存成功")
            } catch (e: Exception) {
                Log.e(TAG, "WebDAV Restic密码保存失败", e)
            }
        }
    }

    override suspend fun onEvent(state: WebdavResticUiState, intent: WebdavResticUiIntent) {
        // 暂时不需要处理特定的UI意图
    }
}