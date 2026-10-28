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
import com.xayah.core.util.toFriendlyMessage
import com.xayah.core.util.command.SELinux
import com.xayah.core.work.WorkManagerInitializer
import com.xayah.feature.setup.R
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
    data class OpenExisting(val repoPath: String, val password: String) : IndexUiIntent()
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

            is IndexUiIntent.OpenExisting -> {
                openExistingRepository(intent.repoPath, intent.password)
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
                    val initError = initResult.exceptionOrNull()
                    val msg = initError?.toFriendlyMessage(context) ?: "Restic init failed"
                    // Logcat 保留完整原文，界面只拿友好文案
                    Log.e(TAG, "Restic init failed: ${initError?.message}")
                    _initializationState.value = InitializationState.Error(msg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "setup init error", e)
                _initializationState.value = InitializationState.Error(e.toFriendlyMessage(context))
            }
        }
    }

    /**
     * 目录语义统一：用户既可以选中仓库目录本身，也可以选中它的上一层（内含 restic_repo），
     * 两种选择方式一视同仁，由这里自动归一化成真正的仓库目录。
     */
    private suspend fun resolveRepoPath(selectedPath: String): String {
        if (selectedPath.isBlank()) return selectedPath
        if (isRepository(selectedPath)) {
            Log.i(TAG, "resolveRepoPath: 选中目录本身即仓库 -> $selectedPath")
            return selectedPath
        }
        val child = File(selectedPath, RESTIC_REPO_DIR_NAME).absolutePath
        return if (isRepository(child)) {
            Log.i(TAG, "resolveRepoPath: 选中目录下的 $RESTIC_REPO_DIR_NAME 才是仓库 -> $child")
            child
        } else {
            Log.w(TAG, "resolveRepoPath: 未识别到仓库，按原路径校验 -> $selectedPath")
            selectedPath
        }
    }

    /** 结构性判断某目录是否为 rustic 仓库（只看 config 是否存在，与密码无关）。 */
    private suspend fun isRepository(path: String): Boolean = runCatching { rootService.rusticRepositoryExists(path) }
        .onFailure { Log.w(TAG, "rusticRepositoryExists failed for $path: ${it.message}") }
        .getOrDefault(false)

    /**
     * 打开已有仓库模式：用户选中从旧设备完整复制过来的仓库目录（或它的上一层），此处只做只读校验（exists → validate → check），
     * 绝不会 createDirWithPermissions(repoPath)，也不会执行 initRepository，避免破坏已复制的仓库数据。
     */
    private suspend fun openExistingRepository(repoPath: String, password: String) {
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

                // b) Restic 仓库：对用户在目录选择器中选中的仓库目录做只读校验
                val pwd = password.ifBlank { DEFAULT_RESTIC_PASSWORD }

                _initializationState.value = InitializationState.Initializing

                // 已有仓库只做只读校验（exists → validate → check），绝不 init
                // 用户可选中仓库目录本身或其上一层，这里统一归一化
                val resolvedRepoPath = resolveRepoPath(repoPath)
                val verified = resticRepo.verifyRepository(resolvedRepoPath, pwd)
                if (verified) {
                    context.saveResticRepoPath(resolvedRepoPath)
                    context.saveResticPassword(pwd)
                    _initializationState.value = InitializationState.ReadyToUse(resolvedRepoPath)
                } else {
                    val msg = context.getString(R.string.restic_open_existing_failed)
                    Log.e(TAG, "Restic open existing repo failed: $resolvedRepoPath")
                    _initializationState.value = InitializationState.Error(msg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "open existing repo error", e)
                _initializationState.value = InitializationState.Error(e.toFriendlyMessage(context))
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
        /** 仓库父目录下的固定仓库子目录名，与 ResticRepoLocator.RESTIC_REPO_CHILD 一致。 */
        const val RESTIC_REPO_DIR_NAME = "restic_repo"
        private const val TAG = "SetupIndexViewModel"
    }
}