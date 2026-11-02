package com.xayah.feature.setup.page.two

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.datastore.readBackupSavePathSaved
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.datastore.saveAppVersionName
import com.xayah.core.datastore.saveBackupSavePath
import com.xayah.core.datastore.saveResticPassword
import com.xayah.core.datastore.saveResticRepoConfigId
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data object IndexUiState : UiState

sealed class IndexUiIntent : UiIntent {
    data class ToMain(val context: Activity) : IndexUiIntent()
    data class Initialize(val password: String) : IndexUiIntent()
    data class OpenExisting(val repoPath: String, val password: String) : IndexUiIntent()

    /**
     * 用户在「密码不匹配」弹窗里确认删除旧仓库。
     *
     * 刻意不携带 password：删除与初始化是两个独立动作，删除后必须由用户重新输入密码再点初始化，
     * 绝不能拿弹窗时刻的（已知错误的）密码顺手建库。
     */
    data class DeleteRepository(val repoPath: String) : IndexUiIntent()
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

        /**
         * 仓库结构完好（config 存在）但打不开：几乎总是密码与原仓库不一致。
         * 与 [Error] 分开是因为 UI 要为它弹「删除并重新初始化 / 取消」对话框，
         * 普通 Error 只显示一张红卡片。
         */
        data class PasswordMismatch(val repoPath: String) : InitializationState()

        /**
         * 旧仓库已被删除（用户确认过两层弹窗）。
         *
         * 删除后停在 Idle 会让 UI 看起来"什么都没发生"，用户很可能再点一次初始化，
         * 于是又用刚才那个不匹配的密码走一遍探测 → 又是密码错误。这个状态是为了把
         * "删除已完成、接下来需要你自己输入密码再初始化"明确摆到界面上。
         */
        data class RepositoryDeleted(val repoPath: String) : InitializationState()
    }

    /**
     * 进行中互斥。
     * 不能再用 `initializationState` 兼任互斥锁：一旦某条异常路径漏了收尾（例如 native 抛的是
     * Error 而非 Exception），状态会永久停在 Preparing/Initializing，UI 一直转圈且之后每次点击
     * 都被状态守卫挡回去，只能杀掉应用。这里用独立标志互斥，状态只负责展示。
     */
    private val inFlight = AtomicBoolean(false)

    private fun beginInitialization(): Boolean = inFlight.compareAndSet(false, true)

    private fun endInitialization() {
        inFlight.set(false)
        // 兜底收尾：任何异常逃逸后都不允许把中间态留在 UI 上
        when (_initializationState.value) {
            is InitializationState.Preparing, is InitializationState.Initializing ->
                _initializationState.value =
                    InitializationState.Error(context.getString(R.string.setup_repo_unknown_failure))
            else -> {}
        }
    }

    /** 统一取友好文案：native 异常原文可能带类名前缀，取不到资源时退回 message。 */
    private fun friendlyMessageOf(e: Throwable): String =
        runCatching { e.toFriendlyMessage(context) }.getOrElse { e.message ?: e.javaClass.simpleName }

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

            is IndexUiIntent.DeleteRepository -> {
                deleteRepository(intent.repoPath)
            }
        }
    }

    private suspend fun setupBackupDirAndRepository(password: String) {
        // 已可用则不再重复执行；"进行中"交给 inFlight 判定
        if (_initializationState.value is InitializationState.ReadyToUse) return

        // 纵深防御：目标目录若已经是仓库，绝不能 init。
        // rustic_core 的 init 对已存在 config 的仓库只会返回 Configuration 错误（"Config file already exists"），
        // 用户看到的是一条底层英文报错，以为仓库被弄坏了；这里直接按"打开已有仓库"处理，
        // 语义也更准确：压缩档位只在建库时写进 config，已有仓库无论如何都不会因新推荐值改变。
        val existing = probeExistingRepository()
        if (existing != null) {
            Log.i(TAG, "setup: repository already exists at $existing, open instead of init")
            openExistingRepository(existing, password)
            return
        }

        if (!beginInitialization()) return
        _initializationState.value = InitializationState.Preparing
        try {
            withContext(Dispatchers.IO) {
                // a) 传统恢复目录：仅创建 DEFAULT_PATH 并写入
                if (context.readBackupSavePathSaved().first().not()) {
                    createDirWithPermissions(ConstantUtil.DEFAULT_PATH)
                    context.saveBackupSavePath(ConstantUtil.DEFAULT_PATH)
                }

                // b) Restic 仓库：独立目录 /storage/emulated/0/DataBackupRustic/restic_repo
                val repoPath = File(ConstantUtil.DEFAULT_RUSTIC_REPO_ROOT, RESTIC_REPO_DIR_NAME).absolutePath
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
                    // Logcat 保留完整原文，界面只拿友好文案
                    Log.e(TAG, "Restic init failed: ${initError?.message}")
                    _initializationState.value = InitializationState.Error(friendlyMessageOf(initError ?: Exception("Restic init failed")))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 必须捕 Throwable：native 侧抛 UnsatisfiedLinkError / NoSuchMethodError 时若只捕 Exception，
            // 协程会直接终止，状态永久停在 Initializing，用户看到的就是"一直转圈且点不动"
            Log.e(TAG, "setup init error", e)
            _initializationState.value = InitializationState.Error(friendlyMessageOf(e))
        } finally {
            endInitialization()
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
     * 探测「磁盘上已经存在的仓库」，null 表示还没有。
     *
     * 只读：只看 config 是否存在（与密码无关），既不创建目录也不 init，可安全地在点击时调用。
     * 判定顺序对应真实链路：
     *   1. datastore 记录的当前仓库（可能在 OTG，拔盘时视为不存在，不会误判）
     *   2. 新建模式的固定目标目录 DEFAULT_RUSTIC_REPO_ROOT/restic_repo
     * 两者都命中时优先已记录路径：用户此前选定的位置比默认位置更权威。
     */
    suspend fun probeExistingRepository(): String? = withContext(Dispatchers.IO) {
        val saved = context.readResticRepoPath()
        val defaultPath = File(ConstantUtil.DEFAULT_RUSTIC_REPO_ROOT, RESTIC_REPO_DIR_NAME).absolutePath
        listOfNotNull(saved, defaultPath).distinct().firstOrNull { isRepository(it) }
    }

    /**
     * 打开已有仓库模式：用户选中从旧设备完整复制过来的仓库目录（或它的上一层），此处只做只读校验（exists → validate → check），
     * 绝不会 createDirWithPermissions(repoPath)，也不会执行 initRepository，避免破坏已复制的仓库数据。
     */
    private suspend fun openExistingRepository(repoPath: String, password: String) {
        // 已可用则不再重复执行；"进行中"交给 inFlight 判定
        if (_initializationState.value is InitializationState.ReadyToUse) return
        if (!beginInitialization()) return
        _initializationState.value = InitializationState.Preparing
        try {
            withContext(Dispatchers.IO) {
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
                if (!isRepository(resolvedRepoPath)) {
                    Log.e(TAG, "open existing: not a repository -> $resolvedRepoPath")
                    _initializationState.value =
                        InitializationState.Error(context.getString(R.string.restic_open_existing_failed))
                    return@withContext
                }

                // 密码校验单独一步：verifyRepository 会把"密码错"和"仓库损坏"都折叠成 false，
                // 而 UI 只有区分出密码错才该弹「删除并重新初始化 / 取消」，否则就是误导用户删库。
                // 外层套超时：root 服务未连接或 native 卡住时，宁可报"校验超时"也不能让按钮一直转圈。
                val validated = withTimeoutOrNull(REPO_AUTH_TIMEOUT_MS) {
                    resticRepo.validateRepository(resolvedRepoPath, pwd)
                }
                if (validated == null) {
                    Log.e(TAG, "open existing: validate timed out -> $resolvedRepoPath")
                    _initializationState.value =
                        InitializationState.Error(context.getString(R.string.setup_repo_auth_timeout))
                    return@withContext
                }
                if (!validated) {
                    Log.e(TAG, "open existing: validate failed, password likely mismatched -> $resolvedRepoPath")
                    _initializationState.value = InitializationState.PasswordMismatch(resolvedRepoPath)
                    return@withContext
                }

                // 这里刻意不再跑 checkRepository：原实现用 verifyRepository(=exists+validate+check)，
                // check 会遍历整个仓库的全部 index，几十 GB 的仓库要跑几分钟，用户看到的就是"一直转圈"。
                // 设置页打开仓库也只做 validate，validate 通过即可证明 key 正确、仓库可用；
                // 完整性检查属于维护工具，不该卡在引导流程里。
                context.saveResticRepoPath(resolvedRepoPath)
                context.saveResticPassword(pwd)
                _initializationState.value = InitializationState.ReadyToUse(resolvedRepoPath)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "open existing repo error", e)
            _initializationState.value = InitializationState.Error(friendlyMessageOf(e))
        } finally {
            endInitialization()
        }
    }

    /**
     * 删除旧仓库。
     *
     * 只在「密码不匹配」弹窗里被用户明确确认后调用，因此这里的删除是预期的破坏性操作；
     * 仍然做两道防护：路径必须是绝对路径且当前确实是仓库，避免把 datastore 里的脏路径当成待删目录。
     *
     * 删除即结束：不重建目录、不 init、不写回密码。
     * 原实现把「删除 + 用当前输入框里的密码重建」揉在一个动作里，而触发这段代码的前提恰恰是
     * 那个密码校验失败，于是用户点完删除就被自动用错密码建了个新库，还以为旧数据还在。
     */
    private suspend fun deleteRepository(repoPath: String) {
        if (repoPath.isBlank() || !repoPath.startsWith("/")) {
            Log.e(TAG, "delete repo: refuse non-absolute path -> $repoPath")
            _initializationState.value =
                InitializationState.Error(context.getString(R.string.setup_repo_delete_failed))
            return
        }
        if (!beginInitialization()) return
        _initializationState.value = InitializationState.Preparing
        try {
            withContext(Dispatchers.IO) {
                _initializationState.value = InitializationState.Initializing

                val resolvedRepoPath = resolveRepoPath(repoPath)
                if (!isRepository(resolvedRepoPath)) {
                    Log.e(TAG, "delete repo: not a repository -> $resolvedRepoPath")
                    _initializationState.value =
                        InitializationState.Error(context.getString(R.string.setup_repo_delete_failed))
                    return@withContext
                }

                if (!resticRepo.deleteRepository(resolvedRepoPath)) {
                    Log.e(TAG, "delete repo: delete failed -> $resolvedRepoPath")
                    _initializationState.value =
                        InitializationState.Error(context.getString(R.string.setup_repo_delete_failed))
                    return@withContext
                }

                // 仓库没了，datastore 里指向它的配置也必须一起清掉：
                // 否则 probeExistingRepository / 其它页面仍会认为它存在，并拿旧密码去打开一个不存在的目录。
                context.saveResticRepoPath("")
                context.saveResticPassword("")
                context.saveResticRepoConfigId("")

                Log.i(TAG, "delete repo: deleted -> $resolvedRepoPath, waiting for user to re-initialize")
                // 停在"已删除"，而不是顺手 init：用户需要重新输入密码并显式点初始化
                _initializationState.value = InitializationState.RepositoryDeleted(resolvedRepoPath)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "delete repo error", e)
            _initializationState.value = InitializationState.Error(friendlyMessageOf(e))
        } finally {
            endInitialization()
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
        /** 打开已有仓库时的密码校验超时：root/binder 未就绪或 native 卡住时不能无限转圈。 */
        private const val REPO_AUTH_TIMEOUT_MS = 30_000L
        private const val TAG = "SetupIndexViewModel"
    }
}