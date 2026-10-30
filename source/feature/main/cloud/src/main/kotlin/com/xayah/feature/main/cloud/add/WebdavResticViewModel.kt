package com.xayah.feature.main.cloud.add

import android.content.Context
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.xayah.core.util.toFriendlyMessage
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

    // 不在 ViewModel 创建时从全局 DataStore 回填初始化状态：DataStore 的 restic 配置按云类型
    // 全局只存一份，同类型建多个账户时会互相串（第二个账户显示第一个账户的初始化目录）。
    // 统一走账户级：编辑既有账户时由 restoreStateFromEntity() 从 CloudEntity 恢复。

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
                    // 账户级存储：restic 密码与仓库路径由 CloudEntity 承载（extra.resticPassword / remote），
                    // 不再写全局 DataStore，避免同类型多账户互相覆盖。
                    _webdavInitializationState.value = WebdavInitializationState.Success(remotePath)
                    Log.d(TAG, "WebDAV Restic仓库初始化成功")
                    true
                } else {
                    val errorMsg = result.exceptionOrNull()?.toFriendlyMessage(context) ?: "初始化失败"
                    _webdavInitializationState.value = WebdavInitializationState.Error(errorMsg)
                    Log.e(TAG, "WebDAV Restic仓库初始化失败: ${result.exceptionOrNull()?.message}")
                    false
                }
            } catch (e: Exception) {
                val errorMsg = e.toFriendlyMessage(context)
                _webdavInitializationState.value = WebdavInitializationState.Error(errorMsg)
                Log.e(TAG, "WebDAV Restic仓库初始化异常", e)
                false
            }
        }
    }

    suspend fun checkWebdavRepository(cloudEntity: CloudEntity, password: String): Boolean {
        Log.d(TAG, "checkWebdavRepository 开始 remote=${cloudEntity.remote} state=${_webdavInitializationState.value}")
        if (_webdavInitializationState.value !is WebdavInitializationState.Success) {
            Log.d(TAG, "checkWebdavRepository 被拦截：初始化状态非 Success，直接返回 false")
            return false
        }
        val result = resticRepoWebdav.checkWebdavRepository(cloudEntity, password)
        Log.d(TAG, "checkWebdavRepository 结果 isSuccess=${result.isSuccess} error=${result.exceptionOrNull()?.message}")
        return result.isSuccess
    }

    fun saveWebdavPassword(password: String) {
        Log.d(TAG, "保存WebDAV Restic密码（仅内存，随账户 CloudEntity 持久化）")
        _webdavPasswordState.value = password
    }

    override suspend fun onEvent(state: WebdavResticUiState, intent: WebdavResticUiIntent) {
        // 暂时不需要处理特定的UI意图
    }
}