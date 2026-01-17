package com.xayah.feature.main.cloud.add

import android.content.Context
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.viewModelScope
import com.xayah.core.datastore.readS3ResticInitialized
import com.xayah.core.datastore.readS3ResticPassword
import com.xayah.core.datastore.readS3ResticRepoPath
import com.xayah.core.datastore.saveS3ResticInitialized
import com.xayah.core.datastore.saveS3ResticPassword
import com.xayah.core.datastore.saveS3ResticRepoPath
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.database.S3NetworkType
import com.xayah.core.model.database.S3Protocol
import com.xayah.core.restic.ResticNative
import com.xayah.core.restic.ResticRepository
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

data object S3ResticUiState : UiState

sealed class S3ResticUiIntent : UiIntent

@ExperimentalMaterial3Api
@HiltViewModel
class S3ResticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepo: ResticRepository,
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

    init {
        viewModelScope.launch {
            // 初始化时加载已保存的密码
            val savedPassword = context.readS3ResticPassword() ?: ""
            _s3PasswordState.value = savedPassword

            // 检查初始化状态
            val isInitialized = context.readS3ResticInitialized()
            if (isInitialized) {
                val repoPath = context.readS3ResticRepoPath() ?: ""
                _s3InitializationState.value = S3InitializationState.Success(repoPath)
            }
        }
    }

    /**
     * 初始化S3 Restic仓库
     * 根据官方文档要求构造完整的S3配置
     */
    suspend fun initializeS3Repository(
        s3Extra: S3Extra,
        remotePath: String,
        password: String
    ): Boolean {
        Log.d(TAG, "开始初始化S3 Restic仓库: $remotePath")
        _s3InitializationState.value = S3InitializationState.Initializing

        return withContext(Dispatchers.IO) {
            try {
                // 根据官方文档要求，ResticRepository.initS3Repository已经正确实现了：
                // 1. 设置AWS_ACCESS_KEY_ID和AWS_SECRET_ACCESS_KEY环境变量
                // 2. 构建正确的S3 URL格式：s3:https://endpoint/bucket/path 或 s3:s3.region.amazonaws.com/bucket/path
                // 3. 设置RESTIC_PASSWORD环境变量
                val result = resticRepo.initS3Repository(s3Extra, remotePath, password)

                if (result.isSuccess) {
                    // 保存配置到DataStore
                    context.saveS3ResticPassword(password)
                    context.saveS3ResticInitialized(true)
                    context.saveS3ResticRepoPath(remotePath)

                    _s3InitializationState.value = S3InitializationState.Success(remotePath)
                    Log.d(TAG, "S3 Restic仓库初始化成功")
                    true
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "初始化失败"
                    _s3InitializationState.value = S3InitializationState.Error(errorMsg)
                    Log.e(TAG, "S3 Restic仓库初始化失败: $errorMsg")
                    false
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "未知错误"
                _s3InitializationState.value = S3InitializationState.Error(errorMsg)
                Log.e(TAG, "S3 Restic仓库初始化异常", e)
                false
            }
        }
    }

    fun saveS3Password(password: String) {
        Log.d(TAG, "保存S3 Restic密码")
        _s3PasswordState.value = password
        viewModelScope.launch {
            try {
                context.saveS3ResticPassword(password)
                Log.d(TAG, "S3 Restic密码保存成功")
            } catch (e: Exception) {
                Log.e(TAG, "S3 Restic密码保存失败", e)
            }
        }
    }

    override suspend fun onEvent(state: S3ResticUiState, intent: S3ResticUiIntent) {
        // 暂时不需要处理特定的UI意图
    }
}
