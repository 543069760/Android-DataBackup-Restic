package com.xayah.feature.main.processing.packages.backup

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.datastore.readFtpResticPassword
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.datastore.readS3ResticPassword
import com.xayah.core.datastore.readWebdavResticPassword
import com.xayah.core.datastore.saveCloudActivatedAccountName
import com.xayah.core.model.CloudType
import com.xayah.core.model.OpType
import com.xayah.core.model.StorageMode
import com.xayah.core.model.database.FTPExtra
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.database.SFTPExtra
import com.xayah.core.model.database.WebDAVExtra
import com.xayah.core.model.util.formatSize
import com.xayah.core.network.client.getCloud
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.restic.ResticRepository
import com.xayah.core.restic.ResticRepositoryCos
import com.xayah.core.restic.ResticRepositoryFtp
import com.xayah.core.restic.ResticRepositorySftp
import com.xayah.core.restic.ResticRepositoryWebdav
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
    private val resticRepoCos: ResticRepositoryCos,
    private val resticRepoFtp: ResticRepositoryFtp,
    private val resticRepoWebdav: ResticRepositoryWebdav,
    private val resticRepoSftp: ResticRepositorySftp,
) : AbstractPackagesProcessingViewModel(mContext, mRootService, mTaskRepo, mLocalService, mCloudService) {
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
                        // 与"新增账户点击继续"复用同一批 checkXxxRepository（返回 Result<Unit>，用 isSuccess）。
                        val entity = state.cloudEntity!!
                        // 密码解析：优先取账户 extra 里的 resticPassword，为空回退到对应 datastore，
                        // 解析方式与服务层 BackupServiceCloudImpl.backupWithResticToXxx 保持一致。
                        val extraPassword = when (entity.type) {
                            CloudType.FTP -> entity.getExtraEntity<FTPExtra>()?.resticPassword
                            CloudType.WEBDAV -> entity.getExtraEntity<WebDAVExtra>()?.resticPassword
                            CloudType.SFTP -> entity.getExtraEntity<SFTPExtra>()?.resticPassword
                            else -> entity.getExtraEntity<S3Extra>()?.resticPassword
                        } ?: ""
                        val password = extraPassword.ifEmpty {
                            when (entity.type) {
                                CloudType.FTP -> mContext.readFtpResticPassword() ?: ""
                                CloudType.WEBDAV -> mContext.readWebdavResticPassword() ?: ""
                                CloudType.SFTP -> ""
                                else -> mContext.readS3ResticPassword() ?: ""
                            }
                        }
                        val repoOk = when (entity.type) {
                            CloudType.FTP -> resticRepoFtp.checkFtpRepository(entity, password).isSuccess
                            CloudType.WEBDAV -> resticRepoWebdav.checkWebdavRepository(entity, password).isSuccess
                            CloudType.SFTP -> resticRepoSftp.checkSftpRepository(entity, password).isSuccess
                            CloudType.S3 -> resticRepoCos.checkCosRepository(entity, password).isSuccess
                            else -> true
                        }
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

    val accounts: StateFlow<List<DialogRadioItem<Any>>> = _accounts.stateInScope(listOf())
    val isTesting: StateFlow<Boolean> = _isTesting.stateInScope(false)
    val packages: StateFlow<List<PackageEntity>> = _packages.stateInScope(listOf())
    val packagesSize: StateFlow<String> = _packagesSize.stateInScope("")
}