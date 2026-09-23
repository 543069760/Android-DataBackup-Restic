package com.xayah.feature.main.processing.medium.backup

import android.content.Context
import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.ResticRepoLocator
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.datastore.readResticActiveIsOtg
import com.xayah.core.datastore.readResticOtgPassword
import com.xayah.core.datastore.readResticOtgRepoPath
import com.xayah.core.datastore.saveResticActiveIsOtg
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.datastore.saveCloudActivatedAccountName
import com.xayah.core.datastore.saveResticPassword
import com.xayah.core.datastore.saveResticRepoConfigId
import com.xayah.core.datastore.saveResticRepoPath
import com.xayah.core.datastore.saveResticOtgRepoConfigId
import com.xayah.core.datastore.saveResticOtgPassword
import com.xayah.core.datastore.saveResticOtgRepoPath
import com.xayah.core.model.CloudType
import com.xayah.core.model.OpType
import com.xayah.core.model.StorageMode
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.network.client.getCloud
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.restic.CloudResticBackend
import com.xayah.core.restic.ResticRepository
import com.xayah.core.service.medium.backup.ProcessingServiceProxyCloudImpl
import com.xayah.core.service.medium.backup.ProcessingServiceProxyLocalImpl
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.processing.AbstractMediumProcessingViewModel
import com.xayah.feature.main.processing.FinishSetup
import com.xayah.feature.main.processing.IndexUiState
import com.xayah.feature.main.processing.ProcessingUiIntent
import com.xayah.feature.main.processing.R
import com.xayah.feature.main.processing.SetCloudEntity
import com.xayah.feature.main.processing.UpdateFiles
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject

@ExperimentalCoroutinesApi
@ExperimentalMaterial3Api
@HiltViewModel
class BackupViewModelImpl @Inject constructor(
    @ApplicationContext private val mContext: Context,
    mRootService: RemoteRootService,
    mTaskRepo: TaskRepository,
    private val mMediaRepo: MediaRepository,
    private val mCloudRepo: CloudRepository,
    mLocalService: ProcessingServiceProxyLocalImpl,
    mCloudService: ProcessingServiceProxyCloudImpl,
    private val resticRepo: ResticRepository,
    private val mDirectoryRepo: DirectoryRepository,
    private val resticRepoLocator: ResticRepoLocator,
    private val resticBackends: Map<CloudType, @JvmSuppressWildcards CloudResticBackend>,
) : AbstractMediumProcessingViewModel(mContext, mRootService, mTaskRepo, mLocalService, mCloudService) {

    private fun backend(entity: CloudEntity) = resticBackends.getValue(entity.type)
    // 本任务是否针对 OTG 仓库（由 OTG 备份入口的导航参数赋值；默认本地/云端）
    @Volatile
    var mIsOtgTask: Boolean = false
    override suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            is UpdateFiles -> {
                val medium = mMediaRepo.queryActivated(OpType.BACKUP)
                var bytes = 0.0
                medium.forEach {
                    bytes += it.displayStatsBytes
                }
                _medium.value = medium
                _mediumSize.value = bytes.formatSize()

                // OTG 段显隐判据（与首页 detectOtgRepository 情形 A、应用备份页保持一致）：
                // 已登记 OTG 仓库(路径键非空) + 对该精确路径 verifyRepository 校验通过 + 目标盘当前实时在场。
                // 不依赖 config_id（native 恒返回全 0）与 discoverOtgRepositories（只扫扁平层级，对嵌套仓库无效）。
                val otgPath = mContext.readResticOtgRepoPath()
                val ok = if (otgPath.isNullOrEmpty()) {
                    false
                } else {
                    val otgPwd = mContext.readResticOtgPassword() ?: "databackup_default"
                    runCatching { resticRepo.verifyRepository(otgPath, otgPwd) }.getOrDefault(false)
                }
                val hasDisk = runCatching { mDirectoryRepo.hasLiveExternalStorage() }.getOrDefault(false)
                _hasOtg.value = !otgPath.isNullOrEmpty() && ok && hasDisk
                Log.d("BackupOtg", "hasOtg(medium): otgPath=$otgPath checkOk=$ok hasDisk=$hasDisk")
            }

            is SetCloudEntity -> {
                mContext.saveCloudActivatedAccountName(intent.name)
                emitState(state.copy(cloudEntity = mCloudRepo.queryByName(intent.name)))
            }

            is FinishSetup -> {
                if (state.storageType == StorageMode.Cloud) {
                    _isTesting.value = true
                    emitEffect(IndexUiEffect.DismissSnackbar)
                    emitEffectOnIO(
                        IndexUiEffect.ShowSnackbar(
                            type = SnackbarType.Loading,
                            message = mCloudRepo.getString(R.string.processing),
                            duration = SnackbarDuration.Indefinite,
                        )
                    )
                    runCatching {
                        val entity = state.cloudEntity!!
                        val (client, _) = mCloudRepo.getClient(entity.name)
                        client.testConnection()

                        // 备份前仓库可用性前置检查（fail-fast）：
                        // 解析 restic 密码（优先 extra，回退 datastore），检查仓库是否可达/可打开
                        val backend = backend(entity)
                        val password = backend.resolveResticPassword(entity)
                        val ok = backend.checkRepository(entity, password).isSuccess
                        if (!ok) {
                            emitEffect(IndexUiEffect.DismissSnackbar)
                            emitEffectOnIO(
                                IndexUiEffect.ShowSnackbar(
                                    type = SnackbarType.Error,
                                    message = mCloudRepo.getString(com.xayah.core.data.R.string.pre_backup_repository_check_failed),
                                    duration = SnackbarDuration.Long,
                                )
                            )
                            return@runCatching
                        }

                        emitEffect(IndexUiEffect.DismissSnackbar)
                        withMainContext {
                            intent.navController.popBackStack()
                            intent.navController.navigateSingle(MainRoutes.MediumBackupProcessing.route)
                        }
                    }.onFailure {
                        emitEffect(IndexUiEffect.DismissSnackbar)
                        if (it.localizedMessage != null)
                            emitEffectOnIO(IndexUiEffect.ShowSnackbar(type = SnackbarType.Error, message = it.localizedMessage!!, duration = SnackbarDuration.Long))
                    }
                    _isTesting.value = false
                } else {
                    // ===== 本地/OTG 备份前仓库可用性前置检查（fail-fast）=====
                    _isTesting.value = true
                    // 判据统一到用户在 Setup 页选中的段，不再依赖 mIsOtgTask 导航参数
                    val isOtg = state.storageType == StorageMode.Otg
                    mIsOtgTask = isOtg
                    runCatching {
                        // 与服务层一致地解析仓库路径与密码：
                        // OTG 任务读 OTG 独立键，本地任务读本地键。默认回退保持原样。
                        val repoPath = if (isOtg) {
                            mContext.readResticOtgRepoPath()
                                ?: File(mContext.filesDir, "restic_repo").absolutePath
                        } else {
                            mContext.readResticRepoPath()
                                ?: File(mContext.filesDir, "restic_repo").absolutePath
                        }
                        val password = if (isOtg) {
                            mContext.readResticOtgPassword() ?: "databackup_default"
                        } else {
                            mContext.readResticPassword() ?: "databackup_default"
                        }

                        val ok = resticRepo.verifyRepository(repoPath, password)
                        if (!ok) {
                            // 前置检查失败：复位任务级标记，避免污染下次本地备份
                            mContext.saveResticActiveIsOtg(false)
                            emitEffect(IndexUiEffect.DismissSnackbar)
                            emitEffectOnIO(
                                IndexUiEffect.ShowSnackbar(
                                    type = SnackbarType.Error,
                                    message = mCloudRepo.getString(com.xayah.core.data.R.string.pre_backup_repository_check_failed),
                                    duration = SnackbarDuration.Long,
                                )
                            )
                            return@runCatching
                        }

                        // 前置检查通过、即将进入处理页/启动服务：置位任务级标记，
                        // 供服务层读取侧（方案 B 第 2 步）按 isOtg 分流读键。
                        mContext.saveResticActiveIsOtg(isOtg)

                        emitEffect(IndexUiEffect.DismissSnackbar)
                        withMainContext {
                            intent.navController.popBackStack()
                            intent.navController.navigateSingle(MainRoutes.MediumBackupProcessing.route)
                        }
                    }.onFailure {
                        // 异常路径同样复位，避免标记泄漏
                        mContext.saveResticActiveIsOtg(false)
                        emitEffect(IndexUiEffect.DismissSnackbar)
                        if (it.localizedMessage != null)
                            emitEffectOnIO(IndexUiEffect.ShowSnackbar(type = SnackbarType.Error, message = it.localizedMessage!!, duration = SnackbarDuration.Long))
                    }
                    _isTesting.value = false
                }
            }

            else -> {

            }
        }
    }

    fun bootstrapOtg() = launchOnIO {
        // 情形 A（已登记）：与 FinishSetup / 首页 detectOtgRepository 情形 A 判据一致——
        // 读 OTG 独立路径键 + 对该精确路径 verifyRepository 校验。
        // 通过则说明当前 OTG 仓库可用，直接静默返回，不弹任何提示。
        // 不依赖 discoverOtgRepositories（只扫扁平层级，对嵌套仓库如 .../23565/restic_repo 恒空，会误报）。
        val registeredPath = mContext.readResticOtgRepoPath()
        if (!registeredPath.isNullOrEmpty()) {
            val otgPwd = mContext.readResticOtgPassword() ?: "databackup_default"
            val ok = runCatching { resticRepo.verifyRepository(registeredPath, otgPwd) }.getOrDefault(false)
            Log.d("BackupOtg", "bootstrapOtg(medium): registeredPath=$registeredPath checkOk=$ok")
            if (ok) return@launchOnIO
            // 已登记但当前不可用（未插盘/仓库被删/密码错）→ 引导去设置页处理
            emitEffectOnIO(
                IndexUiEffect.ShowSnackbar(
                    type = SnackbarType.Error,
                    message = mContext.getString(R.string.restic_otg_go_settings),
                    duration = SnackbarDuration.Long,
                )
            )
            return@launchOnIO
        }

        // 情形 B（未登记，换新机首次插盘）：走 discoverOtgRepositories 静默 bootstrap。
        val discovered = resticRepoLocator.discoverOtgRepositories()
        when {
            discovered.isEmpty() -> {
                emitEffectOnIO(
                    IndexUiEffect.ShowSnackbar(
                        type = SnackbarType.Error,
                        message = mContext.getString(R.string.restic_otg_not_found),
                        duration = SnackbarDuration.Long,
                    )
                )
            }

            discovered.size == 1 -> {
                val repo = discovered.first()
                val defaultPwd = mContext.readResticOtgPassword() ?: "databackup_default"
                if (resticRepo.validateRepository(repo.path, defaultPwd)) {
                    mContext.saveResticOtgRepoPath(repo.path)
                    mContext.saveResticOtgRepoConfigId(repo.configId)
                    mContext.saveResticOtgPassword(defaultPwd)
                } else {
                    emitEffectOnIO(
                        IndexUiEffect.ShowSnackbar(
                            type = SnackbarType.Error,
                            message = mContext.getString(R.string.restic_otg_go_settings),
                            duration = SnackbarDuration.Long,
                        )
                    )
                }
            }

            else -> {
                emitEffectOnIO(
                    IndexUiEffect.ShowSnackbar(
                        type = SnackbarType.Error,
                        message = mContext.getString(R.string.restic_otg_go_settings),
                        duration = SnackbarDuration.Long,
                    )
                )
            }
        }
    }

    private val _accounts: Flow<List<DialogRadioItem<Any>>> = mCloudRepo.clouds.map { entities ->
        entities.map {
            DialogRadioItem(
                enum = Any(),
                title = it.name,
                desc = it.user,
            )
        }
    }.flowOnIO()
    private val _isTesting: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val _medium: MutableStateFlow<List<MediaEntity>> = MutableStateFlow(listOf())
    private val _mediumSize: MutableStateFlow<String> = MutableStateFlow("")
    private val _hasOtg: MutableStateFlow<Boolean> = MutableStateFlow(false)

    val accounts: StateFlow<List<DialogRadioItem<Any>>> = _accounts.stateInScope(listOf())
    val isTesting: StateFlow<Boolean> = _isTesting.stateInScope(false)
    val medium: StateFlow<List<MediaEntity>> = _medium.stateInScope(listOf())
    val mediumSize: StateFlow<String> = _mediumSize.stateInScope("")
    val hasOtgState: StateFlow<Boolean> = _hasOtg.stateInScope(false)
}