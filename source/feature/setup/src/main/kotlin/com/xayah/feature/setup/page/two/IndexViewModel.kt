package com.xayah.feature.setup.page.two

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.datastore.readBackupSavePathSaved
import com.xayah.core.datastore.saveAppVersionName
import com.xayah.core.datastore.saveBackupSavePath
import com.xayah.core.datastore.saveResticPassword
import com.xayah.core.datastore.saveResticRepoPath
import com.xayah.core.restic.ResticRepository
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.ActivityUtil
import com.xayah.core.util.command.SELinux
import com.xayah.core.work.WorkManagerInitializer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data object IndexUiState : UiState

sealed class IndexUiIntent : UiIntent {
    data class ToMain(val context: Activity) : IndexUiIntent()
    data class Initialize(val password: String) : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepo: ResticRepository,
    private val rootService: RemoteRootService,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(IndexUiState) {

    sealed class InitializationState {
        data object Idle : InitializationState()
        data object Preparing : InitializationState()
        data object Initializing : InitializationState()
        data class ReadyToUse(val repoPath: String) : InitializationState()
        data class Error(val message: String) : InitializationState()
    }

    private val _initializationState = MutableStateFlow<InitializationState>(InitializationState.Idle)
    val initializationState = _initializationState.asStateFlow()

    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.ToMain -> {
                val activity = intent.context
                WorkManagerInitializer.fullInitialize(activity)
                activity.saveAppVersionName()
                activity.startActivity(Intent(activity, ActivityUtil.classMainActivity))
                activity.finish()
            }

            is IndexUiIntent.Initialize -> {
                setupBackupDirAndRepository(intent.password)
            }
        }
    }

    private suspend fun setupBackupDirAndRepository(password: String) {
        // 幂等：成功或进行中则不重复执行
        val current = _initializationState.value
        if (current is InitializationState.ReadyToUse ||
            current is InitializationState.Initializing ||
            current is InitializationState.Preparing
        ) return

        _initializationState.value = InitializationState.Preparing
        withContext(Dispatchers.IO) {
            try {
                // a) 传统恢复目录：仅创建 DEFAULT_PATH 并写入
                if (context.readBackupSavePathSaved().first().not()) {
                    createDirWithPermissions(ConstantUtil.DEFAULT_PATH)
                    context.saveBackupSavePath(ConstantUtil.DEFAULT_PATH)
                }

                // b) Restic 仓库：独立目录 /storage/emulated/0/DataBackupRustic/restic_repo
                val repoPath = File(ConstantUtil.DEFAULT_RUSTIC_REPO_ROOT, "restic_repo").absolutePath
                val pwd = password.ifBlank { DEFAULT_RESTIC_PASSWORD }

                _initializationState.value = InitializationState.Initializing
                createDirWithPermissions(repoPath)

                val initResult = resticRepo.initRepository(repoPath, pwd)
                if (initResult.isSuccess) {
                    context.saveResticRepoPath(repoPath)
                    context.saveResticPassword(pwd)
                    _initializationState.value = InitializationState.ReadyToUse(repoPath)
                } else {
                    val msg = initResult.exceptionOrNull()?.message ?: "Restic init failed"
                    Log.e(TAG, "Restic init failed: $msg")
                    _initializationState.value = InitializationState.Error(msg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "setup init error", e)
                _initializationState.value = InitializationState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun createDirWithPermissions(path: String) {
        rootService.mkdirs(path)
        rootService.setAllPermissions(path)
        SELinux.getContext(path = path).also { result ->
            val pathContext = if (result.isSuccess) result.outString else ""
            SELinux.chcon(context = pathContext, path = path)
            val uidGid = context.applicationInfo.uid.toUInt()
            SELinux.chown(uid = uidGid, gid = uidGid, path = path)
        }
    }

    companion object {
        const val DEFAULT_RESTIC_PASSWORD = "databackup_default"
        private const val TAG = "SetupIndexViewModel"
    }
}