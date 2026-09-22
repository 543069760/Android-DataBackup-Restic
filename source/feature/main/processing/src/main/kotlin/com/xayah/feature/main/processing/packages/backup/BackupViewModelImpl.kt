package com.xayah.feature.main.processing.packages.backup

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.ResticRepoLocator
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.datastore.saveCloudActivatedAccountName
import com.xayah.core.datastore.saveResticPassword
import com.xayah.core.datastore.saveResticRepoConfigId
import com.xayah.core.datastore.saveResticRepoPath
import com.xayah.core.model.CloudType
import com.xayah.core.model.OpType
import com.xayah.core.model.StorageMode
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.network.client.getCloud
import com.xayah.core.restic.CloudResticBackend
import com.xayah.core.restic.ResticRepository
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.packages.backup.ProcessingServiceProxyCloudImpl
import com.xayah.core.service.packages.backup.ProcessingServiceProxyLocalImpl
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.processing.AbstractPackagesProcessingViewModel
import com.xayah.feature.main.processing.FinishSetup
import com.xayah.feature.main.processing.IndexUiState
import com.xayah.feature.main.processing.ProcessingUiIntent
import com.xayah.feature.main.processing.R
import com.xayah.feature.main.processing.SetCloudEntity
import com.xayah.feature.main.processing.UpdateApps
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
    private val mPkgRepo: PackageRepository,
    private val mCloudRepo: CloudRepository,
    mLocalService: ProcessingServiceProxyLocalImpl,
    mCloudService: ProcessingServiceProxyCloudImpl,
    private val resticRepo: ResticRepository,
    private val mDirectoryRepo: DirectoryRepository,
    private val resticRepoLocator: ResticRepoLocator,
    private val resticBackends: Map<CloudType, @JvmSuppressWildcards CloudResticBackend>,
) : AbstractPackagesProcessingViewModel(mContext, mRootService, mTaskRepo, mLocalService, mCloudService) {

    private fun backend(entity: CloudEntity) = resticBackends.getValue(entity.type)

    override suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            is UpdateApps -> {
                val packages = mPkgRepo.queryActivated(OpType.BACKUP)
                var bytes = 0.0
                packages.forEach {
                    bytes += it.storageStatsBytes
                }
                _packages.value = packages
                _packagesSize.value = bytes.formatSize()
                _hasOtg.value = resticRepoLocator.discoverOtgRepositories().isNotEmpty()
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
                        val (client, _) = mCloudRepo.getClient(state.cloudEntity!!.name)
                        client.testConnection()

                        // ===== 备份前仓库可用性前置检查（fail-fast） =====
                        // 通过注册表按 CloudType 取对应后端，密码解析与仓库检查统一走 CloudResticBackend 多态调用。
                        val entity = state.cloudEntity!!
                        val backend = backend(entity)
                        val password = backend.resolveResticPassword(entity)
                        val repoOk = backend.checkRepository(entity, password).isSuccess
                        if (!repoOk) {
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
                        // ===== 检查通过，进入处理页 =====
                        emitEffect(IndexUiEffect.DismissSnackbar)
                        withMainContext {
                            intent.navController.popBackStack()
                            intent.navController.navigateSingle(MainRoutes.PackagesBackupProcessing.route)
                        }
                    }.onFailure {
                        emitEffect(IndexUiEffect.DismissSnackbar)
                        if (it.localizedMessage != null)
                            emitEffectOnIO(IndexUiEffect.ShowSnackbar(type = SnackbarType.Error, message = it.localizedMessage!!, duration = SnackbarDuration.Long))
                    }
                    _isTesting.value = false
                } else {
                    _isTesting.value = true
                    runCatching {
                        // ===== 本地仓库前置检查（fail-fast）=====
                        val repoPath = mContext.readResticRepoPath()
                            ?: File(mContext.filesDir, "restic_repo").absolutePath
                        val password = mContext.readResticPassword() ?: "databackup_default"
                        val ok = resticRepo.verifyRepository(repoPath, password)
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
                        // ===== 检查通过，进入处理页 =====
                        emitEffect(IndexUiEffect.DismissSnackbar)
                        withMainContext {
                            intent.navController.popBackStack()
                            intent.navController.navigateSingle(MainRoutes.PackagesBackupProcessing.route)
                        }
                    }.onFailure {
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

    /**
     * 首次选择 OTG 段时触发：发现 OTG restic 仓库并（默认密码可解时）静默登记。
     * 走 discoverOtgRepositories()（只读 config、不需密码）而非 resolveCurrentResticRepoPath()
     * （后者首次无 savedConfigId 会 NotFound）。首页/Setup 不弹密码框/多盘选择框，
     * 自定义密码或多盘一律引导去设置页 ResticViewModel 承载交互式 bootstrap。
     */
    fun bootstrapOtg() = launchOnIO {
        val discovered = resticRepoLocator.discoverOtgRepositories()
        when {
            discovered.isEmpty() -> {
                // 没插盘 / 空盘 / 盘里无仓库 —— 提示去设置页初始化
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
                val defaultPwd = mContext.readResticPassword() ?: "databackup_default"
                if (resticRepo.validateRepository(repo.path, defaultPwd)) {
                    // 默认密码可解 → 零交互静默登记
                    mContext.saveResticRepoPath(repo.path)
                    mContext.saveResticRepoConfigId(repo.configId)
                    mContext.saveResticPassword(defaultPwd)
                } else {
                    // 自定义密码 → 引导去设置页 bootstrap
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
                // 多盘 → 引导去设置页选择
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
    private val _packages: MutableStateFlow<List<PackageEntity>> = MutableStateFlow(listOf())
    private val _packagesSize: MutableStateFlow<String> = MutableStateFlow("")
    private val _hasOtg: MutableStateFlow<Boolean> = MutableStateFlow(false)

    val accounts: StateFlow<List<DialogRadioItem<Any>>> = _accounts.stateInScope(listOf())
    val isTesting: StateFlow<Boolean> = _isTesting.stateInScope(false)
    val packages: StateFlow<List<PackageEntity>> = _packages.stateInScope(listOf())
    val packagesSize: StateFlow<String> = _packages.let { _ -> _packagesSize }.stateInScope("")
    val hasOtgState: StateFlow<Boolean> = _hasOtg.stateInScope(false)
}