package com.xayah.feature.main.cloud.add

import android.content.Context
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.viewModelScope
import com.xayah.core.datastore.saveHuaweiObsResticInitialized
import com.xayah.core.datastore.saveHuaweiObsResticPassword
import com.xayah.core.datastore.saveHuaweiObsResticRepoPath
import com.xayah.core.model.CloudType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.HuaweiObsExtra
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.restic.ResticNative
import com.xayah.core.restic.ResticRepositoryHuaweiObs
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
import javax.inject.Inject

data object HuaweiObsResticUiState : UiState

sealed class HuaweiObsResticUiIntent : UiIntent

@ExperimentalMaterial3Api
@HiltViewModel
class HuaweiObsResticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepoHuaweiObs: ResticRepositoryHuaweiObs,
    private val rootService: RemoteRootService,
    private val resticNative: ResticNative
) : BaseViewModel<HuaweiObsResticUiState, HuaweiObsResticUiIntent, IndexUiEffect>(HuaweiObsResticUiState) {

    companion object {
        private const val TAG = "HuaweiObsResticViewModel"
    }

    private val _obsInitializationState = MutableStateFlow<ObsInitializationState>(ObsInitializationState.Idle)
    val obsInitializationState: StateFlow<ObsInitializationState> = _obsInitializationState.asStateFlow()

    private val _obsPasswordState = MutableStateFlow("")
    val obsPasswordState: StateFlow<String> = _obsPasswordState.asStateFlow()

    sealed class ObsInitializationState {
        object Idle : ObsInitializationState()
        object Initializing : ObsInitializationState()
        data class Success(val repoPath: String) : ObsInitializationState()
        data class Error(val message: String) : ObsInitializationState()
    }

    // 新增账户不再回填历史初始化密码：状态为全新开始，必须重新输入并初始化。
    // 编辑既有账户时由 restoreStateFromEntity() 从 CloudEntity 的 resticPassword 恢复。

    fun restoreStateFromEntity(cloudEntity: CloudEntity) {
        if (cloudEntity.type != CloudType.HUAWEIOBS) return
        if (_obsInitializationState.value !is ObsInitializationState.Idle) return

        val extra = cloudEntity.getExtraEntity<HuaweiObsExtra>() ?: return
        val savedPassword = extra.resticPassword
        if (savedPassword.isNotEmpty()) {
            _obsPasswordState.value = savedPassword
            _obsInitializationState.value = ObsInitializationState.Success(cloudEntity.remote)
            Log.d(TAG, "已从账户恢复华为云 OBS Restic 初始化状态: ${cloudEntity.remote}")
        }
    }

    suspend fun initializeHuaweiObsRepository(
        cloudEntity: CloudEntity,
        remotePath: String,
        password: String
    ): Boolean {
        Log.d(TAG, "开始初始化华为云 OBS Restic 仓库: $remotePath")
        _obsInitializationState.value = ObsInitializationState.Initializing
        return withContext(Dispatchers.IO) {
            try {
                val result = resticRepoHuaweiObs.initRepository(cloudEntity, remotePath, password)

                if (result.isSuccess) {
                    context.saveHuaweiObsResticPassword(password)
                    context.saveHuaweiObsResticInitialized(true)
                    context.saveHuaweiObsResticRepoPath(remotePath)

                    _obsInitializationState.value = ObsInitializationState.Success(remotePath)
                    Log.d(TAG, "华为云 OBS Restic 仓库初始化成功")
                    true
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "初始化失败"
                    _obsInitializationState.value = ObsInitializationState.Error(errorMsg)
                    Log.e(TAG, "华为云 OBS Restic 仓库初始化失败: $errorMsg")
                    false
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "未知错误"
                _obsInitializationState.value = ObsInitializationState.Error(errorMsg)
                Log.e(TAG, "华为云 OBS Restic 仓库初始化异常", e)
                false
            }
        }
    }

    suspend fun checkHuaweiObsRepository(cloudEntity: CloudEntity, password: String): Boolean {
        if (_obsInitializationState.value !is ObsInitializationState.Success) return false
        return resticRepoHuaweiObs.checkRepository(cloudEntity, password).isSuccess
    }

    fun saveHuaweiObsPassword(password: String) {
        Log.d(TAG, "保存华为云 OBS Restic 密码")
        _obsPasswordState.value = password
        viewModelScope.launch {
            try {
                context.saveHuaweiObsResticPassword(password)
                Log.d(TAG, "华为云 OBS Restic 密码保存成功")
            } catch (e: Exception) {
                Log.e(TAG, "华为云 OBS Restic 密码保存失败", e)
            }
        }
    }

    override suspend fun onEvent(state: HuaweiObsResticUiState, intent: HuaweiObsResticUiIntent) {
    }
}
