package com.xayah.feature.main.cloud.add

import android.content.Context
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.viewModelScope
import com.xayah.core.datastore.readAwsS3ResticInitialized
import com.xayah.core.datastore.readAwsS3ResticPassword
import com.xayah.core.datastore.readAwsS3ResticRepoPath
import com.xayah.core.datastore.saveAwsS3ResticInitialized
import com.xayah.core.datastore.saveAwsS3ResticPassword
import com.xayah.core.datastore.saveAwsS3ResticRepoPath
import com.xayah.core.model.CloudType
import com.xayah.core.model.database.AwsS3Extra
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.restic.ResticNative
import com.xayah.core.restic.ResticRepositoryAwsS3
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

data object AwsS3ResticUiState : UiState

sealed class AwsS3ResticUiIntent : UiIntent

@ExperimentalMaterial3Api
@HiltViewModel
class AwsS3ResticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepoAwsS3: ResticRepositoryAwsS3,
    private val rootService: RemoteRootService,
    private val resticNative: ResticNative
) : BaseViewModel<AwsS3ResticUiState, AwsS3ResticUiIntent, IndexUiEffect>(AwsS3ResticUiState) {

    companion object {
        private const val TAG = "AwsS3ResticViewModel"
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

    init {
        viewModelScope.launch {
            val savedPassword = context.readAwsS3ResticPassword() ?: ""
            _s3PasswordState.value = savedPassword

            val isInitialized = context.readAwsS3ResticInitialized()
            if (isInitialized) {
                val repoPath = context.readAwsS3ResticRepoPath() ?: ""
                _s3InitializationState.value = S3InitializationState.Success(repoPath)
            }
        }
    }

    /**
     * 从既有账户 CloudEntity 恢复 AWS S3 restic 初始化状态。
     * 规则：解析出的 resticPassword 非空即视为"之前已初始化过"。
     */
    fun restoreStateFromEntity(cloudEntity: CloudEntity) {
        if (cloudEntity.type != CloudType.AWSS3) return
        if (_s3InitializationState.value !is S3InitializationState.Idle) return

        val extra = cloudEntity.getExtraEntity<AwsS3Extra>() ?: return
        val savedPassword = extra.resticPassword
        if (savedPassword.isNotEmpty()) {
            _s3PasswordState.value = savedPassword
            _s3InitializationState.value = S3InitializationState.Success(cloudEntity.remote)
            Log.d(TAG, "已从账户恢复 AWS S3 Restic 初始化状态: ${cloudEntity.remote}")
        }
    }

    suspend fun initializeAwsS3Repository(
        cloudEntity: CloudEntity,
        remotePath: String,
        password: String
    ): Boolean {
        Log.d(TAG, "开始初始化 AWS S3 Restic 仓库: $remotePath")
        _s3InitializationState.value = S3InitializationState.Initializing
        return withContext(Dispatchers.IO) {
            try {
                val result = resticRepoAwsS3.initRepository(cloudEntity, remotePath, password)

                if (result.isSuccess) {
                    context.saveAwsS3ResticPassword(password)
                    context.saveAwsS3ResticInitialized(true)
                    context.saveAwsS3ResticRepoPath(remotePath)

                    _s3InitializationState.value = S3InitializationState.Success(remotePath)
                    Log.d(TAG, "AWS S3 Restic 仓库初始化成功")
                    true
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "初始化失败"
                    _s3InitializationState.value = S3InitializationState.Error(errorMsg)
                    Log.e(TAG, "AWS S3 Restic 仓库初始化失败: $errorMsg")
                    false
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "未知错误"
                _s3InitializationState.value = S3InitializationState.Error(errorMsg)
                Log.e(TAG, "AWS S3 Restic 仓库初始化异常", e)
                false
            }
        }
    }

    suspend fun checkAwsS3Repository(cloudEntity: CloudEntity, password: String): Boolean {
        if (_s3InitializationState.value !is S3InitializationState.Success) return false
        return resticRepoAwsS3.checkRepository(cloudEntity, password).isSuccess
    }

    fun saveAwsS3Password(password: String) {
        Log.d(TAG, "保存 AWS S3 Restic 密码")
        _s3PasswordState.value = password
        viewModelScope.launch {
            try {
                context.saveAwsS3ResticPassword(password)
                Log.d(TAG, "AWS S3 Restic 密码保存成功")
            } catch (e: Exception) {
                Log.e(TAG, "AWS S3 Restic 密码保存失败", e)
            }
        }
    }

    override suspend fun onEvent(state: AwsS3ResticUiState, intent: AwsS3ResticUiIntent) {
        // 暂时不需要处理特定的 UI 意图
    }
}