package com.xayah.feature.main.cloud.add

import android.content.Context
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.model.CloudType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.SMBExtra
import com.xayah.core.restic.ResticRepositorySmb
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.GsonUtil
import com.xayah.core.network.util.getExtraEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import com.xayah.core.util.toFriendlyMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

data object SmbResticUiState : UiState

sealed class SmbResticUiIntent : UiIntent

@ExperimentalMaterial3Api
@HiltViewModel
class SmbResticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepoSmb: ResticRepositorySmb,
    private val rootService: RemoteRootService,
) : BaseViewModel<SmbResticUiState, SmbResticUiIntent, IndexUiEffect>(SmbResticUiState) {

    companion object {
        private const val TAG = "SmbResticViewModel"
    }

    private val _smbInitializationState = MutableStateFlow<SmbInitializationState>(SmbInitializationState.Idle)
    val smbInitializationState: StateFlow<SmbInitializationState> = _smbInitializationState.asStateFlow()

    private val _smbPasswordState = MutableStateFlow("")
    val smbPasswordState: StateFlow<String> = _smbPasswordState.asStateFlow()

    sealed class SmbInitializationState {
        object Idle : SmbInitializationState()
        object Initializing : SmbInitializationState()
        data class Success(val repoPath: String) : SmbInitializationState()
        data class Error(val message: String) : SmbInitializationState()
    }

    /**
     * 从既有账户 CloudEntity 恢复 SMB restic 初始化状态。
     * 对照 FTP/SFTP 在 init{} 块里从 DataStore 恢复的做法；
     * SMB 按账户级设计，密码不写全局 datastore，而是存在该账户
     * CloudEntity.extra 的 SMBExtra.resticPassword，故这里改从实体反查恢复。
     *
     * 规则：解析出的 resticPassword 非空即视为"之前已初始化过"，
     * 把密码回填 UI 状态，并把初始化状态置为 Success(remotePath)。
     */
    fun restoreStateFromEntity(cloudEntity: CloudEntity) {
        if (cloudEntity.type != CloudType.SMB) return
        if (_smbInitializationState.value !is SmbInitializationState.Idle) return

        val smbExtra = cloudEntity.getExtraEntity<SMBExtra>() ?: return
        val savedPassword = smbExtra.resticPassword
        if (savedPassword.isNotEmpty()) {
            _smbPasswordState.value = savedPassword
            _smbInitializationState.value = SmbInitializationState.Success(cloudEntity.remote)
            Log.d(TAG, "已从账户恢复 SMB Restic 初始化状态: ${cloudEntity.remote}")
        }
    }

    /**
     * 初始化 SMB Restic 仓库。
     * 对照 FtpResticViewModel.initializeFtpRepository：用 UI 字段拼出临时 CloudEntity，
     * extra 用 GsonUtil 序列化 SMBExtra（含 resticPassword），再交给 ResticRepositorySmb。
     * 区别：SMB 的 restic 密码按账户存入 SMBExtra.resticPassword，不写任何全局 datastore；
     * ResticRepositorySmb 内部经 RcloneServe 起 serve 拿 rest: URL 再 init。
     */
    suspend fun initializeSmbRepository(
        name: String,
        host: String,
        port: Int,
        username: String,
        pass: String,
        domain: String,
        remotePath: String,
        password: String
    ): Boolean {
        Log.d(TAG, "开始初始化SMB Restic仓库: $remotePath")
        _smbInitializationState.value = SmbInitializationState.Initializing

        return withContext(Dispatchers.IO) {
            try {
                // share 从 remote 路径第一段自动提取
                val share = remotePath.trim('/').takeWhile { it != '/' }.let { if (it.isEmpty()) "" else it }
                // 用 UI 字段构造临时 CloudEntity（remote 与 remotePath 保持一致，
                // 避免 init 建库路径与后续 backup/list 路径不匹配）
                val smbExtra = SMBExtra(
                    port = port,
                    share = share,
                    domain = domain,
                    resticPassword = password,
                )
                val cloudEntity = CloudEntity(
                    name = name,
                    type = CloudType.SMB,
                    host = host,
                    user = username,
                    pass = pass,
                    remote = remotePath,
                    extra = GsonUtil().toJson(smbExtra),
                    activated = false,
                )

                // ResticRepositorySmb 内部经 RcloneServe 起 serve restic 拿 rest: URL，
                // restic 仓库密码单独传入，在 root 进程完成 init。
                val result = resticRepoSmb.initSmbRepository(cloudEntity, remotePath, password)

                if (result.isSuccess) {
                    // SMB 密码走账户级（SMBExtra.resticPassword），仅回写 UI 状态，不写全局 datastore
                    _smbPasswordState.value = password
                    _smbInitializationState.value = SmbInitializationState.Success(remotePath)
                    Log.d(TAG, "SMB Restic仓库初始化成功")
                    true
                } else {
                    val errorMsg = result.exceptionOrNull()?.toFriendlyMessage(context) ?: "初始化失败"
                    _smbInitializationState.value = SmbInitializationState.Error(errorMsg)
                    Log.e(TAG, "SMB Restic仓库初始化失败: ${result.exceptionOrNull()?.message}")
                    false
                }
            } catch (e: Exception) {
                val errorMsg = e.toFriendlyMessage(context)
                _smbInitializationState.value = SmbInitializationState.Error(errorMsg)
                Log.e(TAG, "SMB Restic仓库初始化异常", e)
                false
            }
        }
    }

    suspend fun checkSmbRepository(cloudEntity: CloudEntity, password: String): Boolean {
        if (_smbInitializationState.value !is SmbInitializationState.Success) return false
        return resticRepoSmb.checkSmbRepository(cloudEntity, password).isSuccess
    }

    fun saveSmbPassword(password: String) {
        Log.d(TAG, "保存SMB Restic密码")
        _smbPasswordState.value = password
    }

    override suspend fun onEvent(state: SmbResticUiState, intent: SmbResticUiIntent) {
        // 暂时不需要处理特定的UI意图
    }
}
