package com.xayah.feature.main.settings.restic

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.saveResticRepoPath
import com.xayah.core.datastore.saveResticPassword
import com.xayah.core.restic.ResticRepository
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.command.SELinux
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject


data object ResticUiState : UiState

sealed class ResticUiIntent : UiIntent

@ExperimentalMaterial3Api
@HiltViewModel
class ResticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepo: ResticRepository,
    private val rootService: RemoteRootService
) : BaseViewModel<ResticUiState, ResticUiIntent, IndexUiEffect>(ResticUiState) {


    companion object {
        private const val TAG = "ResticViewModel"
    }

    override suspend fun onEvent(state: ResticUiState, intent: ResticUiIntent) {}

    // 在 ResticViewModel 中添加
    private val _repoPathState = MutableStateFlow<String?>(null)
    val repoPathState: StateFlow<String?> = _repoPathState.asStateFlow()

    // Restic version
    private val _resticVersionState = MutableStateFlow<String?>(null)
    val resticVersionState: StateFlow<String?> = _resticVersionState

    // Restic initialization status
    private val _resticInitializedState = MutableStateFlow(false)
    val resticInitializedState: StateFlow<Boolean> = _resticInitializedState

    // Restic repository path
    private val _resticRepoPathState = MutableStateFlow("")
    val resticRepoPathState: StateFlow<String> = _resticRepoPathState

    // Snapshot count
    private val _resticSnapshotCountState = MutableStateFlow(0)
    val resticSnapshotCountState: StateFlow<Int> = _resticSnapshotCountState

    init {
        viewModelScope.launch {
            checkResticStatus()
        }
    }

    suspend fun checkResticStatus() {
        launchOnIO {
            try {
                // Get Restic version
                val version = resticRepo.getVersion()
                _resticVersionState.value = version

                // 获取仓库路径
                val repoPath = getRepoPath()
                _repoPathState.value = repoPath

                // Check initialization status and snapshot count
                val defaultRepoPath = repoPath // 重命名变量
                val password = getResticPassword()

                val isInitialized = resticRepo.checkRepository(defaultRepoPath, password)
                _resticInitializedState.value = isInitialized
                _resticRepoPathState.value = if (isInitialized) defaultRepoPath else ""

                if (isInitialized) {
                    // Get snapshot count
                    val snapshots = resticRepo.listSnapshots(defaultRepoPath, password)
                    _resticSnapshotCountState.value = snapshots.size
                } else {
                    _resticSnapshotCountState.value = 0
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking status", e)
                _resticVersionState.value = null
                _resticInitializedState.value = false
                _resticRepoPathState.value = ""
                _resticSnapshotCountState.value = 0
            }
        }
    }

    // 初始化状态
    private val _initializationState = MutableStateFlow<InitializationState>(InitializationState.Idle)
    val initializationState: StateFlow<InitializationState> = _initializationState

    sealed class InitializationState {
        object Idle : InitializationState()
        object Checking : InitializationState()
        object Validating : InitializationState()
        object Initializing : InitializationState()
        data class ReadyToUse(val repoPath: String) : InitializationState()
        data class PasswordError(val repoPath: String) : InitializationState()
        data class Error(val message: String) : InitializationState()
    }

    // 修改后的初始化方法
    suspend fun initializeOrValidateRepository(selectedPath: String): Boolean {
        _initializationState.value = InitializationState.Checking

        val repoPath = File(selectedPath, "restic_repo").absolutePath
        val password = getResticPassword()

        return withContext(Dispatchers.IO) {
            try {
                // 检查仓库目录是否存在
                if (File(repoPath).exists()) {
                    _initializationState.value = InitializationState.Validating

                    // 仓库存在，验证密码
                    if (resticRepo.validateRepository(repoPath, password)) {
                        // 密码正确，直接使用
                        _resticInitializedState.value = true
                        _resticRepoPathState.value = repoPath
                        _initializationState.value = InitializationState.ReadyToUse(repoPath)

                        // 获取快照数量
                        val snapshots = resticRepo.listSnapshots(repoPath, password)
                        _resticSnapshotCountState.value = snapshots.size

                        // 保存到 DataStore
                        context.saveResticRepoPath(repoPath)
                        _repoPathState.value = repoPath
                        true
                    } else {
                        // 密码错误
                        _initializationState.value = InitializationState.PasswordError(repoPath)
                        false
                    }
                } else {
                    // 仓库不存在，进行初始化
                    _initializationState.value = InitializationState.Initializing

                    resticRepo.initRepository(repoPath, password) { success, message ->
                        // 在协程中处理回调
                        viewModelScope.launch {
                            if (success) {
                                _resticInitializedState.value = true
                                _resticRepoPathState.value = repoPath
                                _resticSnapshotCountState.value = 0
                                _initializationState.value = InitializationState.ReadyToUse(repoPath)

                                // 保存到 DataStore
                                context.saveResticRepoPath(repoPath)
                                _repoPathState.value = repoPath
                            } else {
                                _initializationState.value = InitializationState.Error(message)
                            }
                        }
                    }
                    true
                }
            } catch (e: Exception) {
                _initializationState.value = InitializationState.Error(e.message ?: "Unknown error")
                false
            }
        }
    }

    // 删除并重新初始化仓库
    suspend fun deleteAndReinitializeRepository(repoPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            // 删除现有仓库
            val deleteSuccess = resticRepo.deleteRepository(repoPath)
            if (deleteSuccess) {
                // 重新初始化并返回结果
                initializeRepository(repoPath, getPassword())
            } else {
                false
            }
        }
    }

    // 添加保存初始化状态的方法
    fun saveInitializationState(repoPath: String, password: String) {
        viewModelScope.launch {
            context.saveResticRepoPath(repoPath)
            context.saveResticPassword(password)
            // 更新状态
            _repoPathState.value = repoPath
            checkResticStatus()
        }
    }

    // 修改初始化方法，成功后保存状态
    suspend fun initializeRepository(repoPath: String, password: String): Boolean {
        return withContext(Dispatchers.IO) {
            var initSuccess = false
            try {
                // 1. 使用Root Service创建目录
                val dirCreated = rootService.mkdirs(repoPath)
                if (!dirCreated) {
                    Log.e(TAG, "Failed to create directory: $repoPath")
                    return@withContext false
                }

                // 2. 设置目录权限
                rootService.setAllPermissions(repoPath)

                // 3. 设置SELinux上下文（新增）
                SELinux.getContext(path = repoPath).also { result ->
                    val pathContext = if (result.isSuccess) result.outString else ""
                    SELinux.chcon(context = pathContext, path = repoPath)
                    val uidGid = context.applicationInfo.uid.toUInt()
                    SELinux.chown(uid = uidGid, gid = uidGid, path = repoPath)
                }

                // 4. 初始化Restic仓库
                val success = resticRepo.initRepository(repoPath, password) { success, message ->
                    initSuccess = success
                    if (success) {
                        viewModelScope.launch {
                            saveInitializationState(repoPath, password)
                        }
                    }
                }
                initSuccess
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing repository", e)
                false
            }
        }
    }

    fun clearInitializationState() {
        viewModelScope.launch {
            // 清除 DataStore 中的保存信息
            context.saveResticRepoPath("")
            context.saveResticPassword("")
            // 更新状态
            _repoPathState.value = ""
            _resticInitializedState.value = false
            _resticSnapshotCountState.value = 0
        }
    }

    suspend fun getRepoPathDisplay(): String {
        return getRepoPath()
    }

    fun initializeRestic(selectedPath: String) {
        launchOnIO {
            val repoPath = File(selectedPath, "restic_repo").absolutePath
            val password = getResticPassword()

            resticRepo.initRepository(repoPath, password) { success, message ->
                // 在协程中处理回调
                viewModelScope.launch {
                    _resticInitializedState.value = success
                    if (success) {
                        _resticRepoPathState.value = repoPath
                        _resticSnapshotCountState.value = 0
                        // 保存到 DataStore
                        context.saveResticRepoPath(repoPath)
                        _repoPathState.value = repoPath
                    }
                }
            }
        }
    }

    suspend fun getRepoPath(): String {
        // 从 DataStore 读取，如果为空则返回默认值
        return context.readResticRepoPath() ?: File(context.filesDir, "restic_repo").absolutePath
    }

    suspend fun getPassword(): String {
        // 从 DataStore 读取，如果为空则返回默认值
        return context.readResticPassword() ?: "databackup_default"
    }

    fun saveRepoPath(context: Context, path: String) {
        viewModelScope.launch {
            // 保存到 DataStore
            context.saveResticRepoPath(path)
        }
    }

    fun savePassword(context: Context, password: String) {
        viewModelScope.launch {
            // 保存到 DataStore
            context.saveResticPassword(password)
        }
    }

    private val _resticErrorState = MutableStateFlow<String?>(null)
    val resticErrorState: StateFlow<String?> = _resticErrorState

    private suspend fun getResticPassword(): String {
        return context.readResticPassword() ?: "databackup_default"
    }
}