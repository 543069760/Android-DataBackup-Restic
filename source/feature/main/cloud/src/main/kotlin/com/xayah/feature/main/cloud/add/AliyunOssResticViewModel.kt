package com.xayah.feature.main.cloud.add

import android.content.Context
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.viewModelScope
import com.xayah.core.datastore.readAliyunOssResticInitialized
import com.xayah.core.datastore.readAliyunOssResticPassword
import com.xayah.core.datastore.readAliyunOssResticRepoPath
import com.xayah.core.datastore.saveAliyunOssResticInitialized
import com.xayah.core.datastore.saveAliyunOssResticPassword
import com.xayah.core.datastore.saveAliyunOssResticRepoPath
import com.xayah.core.model.CloudType
import com.xayah.core.model.database.AliyunOssExtra
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.restic.ResticNative
import com.xayah.core.restic.ResticRepositoryAliyunOss
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

data object AliyunOssResticUiState : UiState

sealed class AliyunOssResticUiIntent : UiIntent

@ExperimentalMaterial3Api
@HiltViewModel
class AliyunOssResticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepoAliyunOss: ResticRepositoryAliyunOss,
    private val rootService: RemoteRootService,
    private val resticNative: ResticNative
) : BaseViewModel<AliyunOssResticUiState, AliyunOssResticUiIntent, IndexUiEffect>(AliyunOssResticUiState) {

    companion object {
        private const val TAG = "AliyunOssResticViewModel"
    }

    private val _ossInitializationState = MutableStateFlow<OssInitializationState>(OssInitializationState.Idle)
    val ossInitializationState: StateFlow<OssInitializationState> = _ossInitializationState.asStateFlow()

    private val _ossPasswordState = MutableStateFlow("")
    val ossPasswordState: StateFlow<String> = _ossPasswordState.asStateFlow()

    sealed class OssInitializationState {
        object Idle : OssInitializationState()
        object Initializing : OssInitializationState()
        data class Success(val repoPath: String) : OssInitializationState()
        data class Error(val message: String) : OssInitializationState()
    }

    init {
        viewModelScope.launch {
            val savedPassword = context.readAliyunOssResticPassword() ?: ""
            _ossPasswordState.value = savedPassword

            val isInitialized = context.readAliyunOssResticInitialized()
            if (isInitialized) {
                val repoPath = context.readAliyunOssResticRepoPath() ?: ""
                _ossInitializationState.value = OssInitializationState.Success(repoPath)
            }
        }
    }

    fun restoreStateFromEntity(cloudEntity: CloudEntity) {
        if (cloudEntity.type != CloudType.ALIYUNOSS) return
        if (_ossInitializationState.value !is OssInitializationState.Idle) return

        val extra = cloudEntity.getExtraEntity<AliyunOssExtra>() ?: return
        val savedPassword = extra.resticPassword
        if (savedPassword.isNotEmpty()) {
            _ossPasswordState.value = savedPassword
            _ossInitializationState.value = OssInitializationState.Success(cloudEntity.remote)
            Log.d(TAG, "已从账户恢复阿里云 OSS Restic 初始化状态: ${cloudEntity.remote}")
        }
    }

    suspend fun initializeAliyunOssRepository(
        cloudEntity: CloudEntity,
        remotePath: String,
        password: String
    ): Boolean {
        Log.d(TAG, "开始初始化阿里云 OSS Restic 仓库: $remotePath")
        _ossInitializationState.value = OssInitializationState.Initializing
        return withContext(Dispatchers.IO) {
            try {
                val result = resticRepoAliyunOss.initRepository(cloudEntity, remotePath, password)

                if (result.isSuccess) {
                    context.saveAliyunOssResticPassword(password)
                    context.saveAliyunOssResticInitialized(true)
                    context.saveAliyunOssResticRepoPath(remotePath)

                    _ossInitializationState.value = OssInitializationState.Success(remotePath)
                    Log.d(TAG, "阿里云 OSS Restic 仓库初始化成功")
                    true
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "初始化失败"
                    _ossInitializationState.value = OssInitializationState.Error(errorMsg)
                    Log.e(TAG, "阿里云 OSS Restic 仓库初始化失败: $errorMsg")
                    false
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "未知错误"
                _ossInitializationState.value = OssInitializationState.Error(errorMsg)
                Log.e(TAG, "阿里云 OSS Restic 仓库初始化异常", e)
                false
            }
        }
    }

    suspend fun checkAliyunOssRepository(cloudEntity: CloudEntity, password: String): Boolean {
        if (_ossInitializationState.value !is OssInitializationState.Success) return false
        return resticRepoAliyunOss.checkRepository(cloudEntity, password).isSuccess
    }

    fun saveAliyunOssPassword(password: String) {
        Log.d(TAG, "保存阿里云 OSS Restic 密码")
        _ossPasswordState.value = password
        viewModelScope.launch {
            try {
                context.saveAliyunOssResticPassword(password)
                Log.d(TAG, "阿里云 OSS Restic 密码保存成功")
            } catch (e: Exception) {
                Log.e(TAG, "阿里云 OSS Restic 密码保存失败", e)
            }
        }
    }

    override suspend fun onEvent(state: AliyunOssResticUiState, intent: AliyunOssResticUiIntent) {
    }
}
