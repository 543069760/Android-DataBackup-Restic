package com.xayah.feature.main.cloud.add

import android.content.Context
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.database.S3Protocol
import com.xayah.core.model.CloudType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.restic.ResticNative
import com.xayah.core.restic.ResticRepositoryCos
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
import javax.inject.Inject

data object S3ResticUiState : UiState

sealed class S3ResticUiIntent : UiIntent

@ExperimentalMaterial3Api
@HiltViewModel
class S3ResticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepoCos: ResticRepositoryCos,
    private val rootService: RemoteRootService,
    private val resticNative: ResticNative
) : BaseViewModel<S3ResticUiState, S3ResticUiIntent, IndexUiEffect>(S3ResticUiState) {

    companion object {
        private const val TAG = "S3ResticViewModel"
    }

    private val _s3InitializationState = MutableStateFlow<S3InitializationState>(S3InitializationState.Idle)
    val s3InitializationState: StateFlow<S3InitializationState> = _s3InitializationState.asStateFlow()

    private val _s3PasswordState = MutableStateFlow("")
    val s3PasswordState: StateFlow<String> = _s3PasswordState.asStateFlow()

    sealed class S3InitializationState {
        object Idle : S3InitializationState()
        object Initializing : S3InitializationState()
        data class Success(val repoPath: String) : S3InitializationState()
        data class Error(val message: String) : S3InitializationState()
    }

    // 不在 ViewModel 创建时从全局 DataStore 回填初始化状态：DataStore 的 restic 配置按云类型
    // 全局只存一份，同类型建多个账户时会互相串（第二个账户显示第一个账户的初始化目录）。
    // 统一走账户级：编辑既有账户时由 restoreStateFromEntity() 从 CloudEntity 恢复。

    /**
     * 从既有账户 CloudEntity 恢复 S3 restic 初始化状态。
     * 导入配置时只写回 CloudEntity（含 extra 里的 resticPassword），
     * 不会写全局 DataStore，故这里改从实体反查恢复，行为对齐 SFTP。
     *
     * 规则：解析出的 resticPassword 非空即视为"之前已初始化过"，
     * 把密码回填 UI 状态，并把初始化状态置为 Success(remote)。
     */
    fun restoreStateFromEntity(cloudEntity: CloudEntity) {
        if (cloudEntity.type != CloudType.S3) return
        if (_s3InitializationState.value !is S3InitializationState.Idle) return

        val s3Extra = cloudEntity.getExtraEntity<S3Extra>() ?: return
        val savedPassword = s3Extra.resticPassword
        if (savedPassword.isNotEmpty()) {
            _s3PasswordState.value = savedPassword
            _s3InitializationState.value = S3InitializationState.Success(cloudEntity.remote)
            Log.d(TAG, "已从账户恢复 S3 Restic 初始化状态: ${cloudEntity.remote}")
        }
    }

    /**
     * 初始化S3 Restic仓库
     * 根据官方文档要求构造完整的S3配置
     */
    suspend fun initializeS3Repository(
        cloudEntity: CloudEntity,
        remotePath: String,
        password: String
    ): Boolean {
        Log.d(TAG, "开始初始化S3 Restic仓库: $remotePath")
        _s3InitializationState.value = S3InitializationState.Initializing
        return withContext(Dispatchers.IO) {
            try {
                val result = resticRepoCos.initRepository(cloudEntity, remotePath, password)

                if (result.isSuccess) {
                    // 账户级存储：restic 密码与仓库路径由 CloudEntity 承载（extra.resticPassword / remote），
                    // 不再写全局 DataStore，避免同类型多账户互相覆盖。
                    _s3InitializationState.value = S3InitializationState.Success(remotePath)
                    Log.d(TAG, "S3 Restic仓库初始化成功")
                    true
                } else {
                    val errorMsg = result.exceptionOrNull()?.toFriendlyMessage(context) ?: "初始化失败"
                    _s3InitializationState.value = S3InitializationState.Error(errorMsg)
                    Log.e(TAG, "S3 Restic仓库初始化失败: ${result.exceptionOrNull()?.message}")
                    false
                }
            } catch (e: Exception) {
                val errorMsg = e.toFriendlyMessage(context)
                _s3InitializationState.value = S3InitializationState.Error(errorMsg)
                Log.e(TAG, "S3 Restic仓库初始化异常", e)
                false
            }
        }
    }

    suspend fun checkS3Repository(cloudEntity: CloudEntity, password: String): Boolean {
        if (_s3InitializationState.value !is S3InitializationState.Success) return false
        return resticRepoCos.checkCosRepository(cloudEntity, password).isSuccess
    }

    fun saveS3Password(password: String) {
        Log.d(TAG, "保存S3 Restic密码（仅内存，随账户 CloudEntity 持久化）")
        _s3PasswordState.value = password
    }

    override suspend fun onEvent(state: S3ResticUiState, intent: S3ResticUiIntent) {
        // 暂时不需要处理特定的UI意图
    }
}