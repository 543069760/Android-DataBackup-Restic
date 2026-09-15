package com.xayah.feature.main.processing.medium.backup

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
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
import com.xayah.core.model.database.MediaEntity
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
    private val resticRepoCos: ResticRepositoryCos,
    private val resticRepoFtp: ResticRepositoryFtp,
    private val resticRepoWebdav: ResticRepositoryWebdav,
    private val resticRepoSftp: ResticRepositorySftp,
) : AbstractMediumProcessingViewModel(mContext, mRootService, mTaskRepo, mLocalService, mCloudService) {
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
                        // 解析 restic 密码（优先 extra，回退 datastore），按类型分派检查仓库是否可达/可打开
                        val password = when (entity.type) {
                            CloudType.S3 -> entity.getExtraEntity<S3Extra>()?.resticPassword?.takeIf { it.isNotEmpty() }
                                ?: mContext.readS3ResticPassword() ?: ""
                            CloudType.FTP -> entity.getExtraEntity<FTPExtra>()?.resticPassword?.takeIf { it.isNotEmpty() }
                                ?: mContext.readFtpResticPassword() ?: ""
                            CloudType.WEBDAV -> entity.getExtraEntity<WebDAVExtra>()?.resticPassword?.takeIf { it.isNotEmpty() }
                                ?: mContext.readWebdavResticPassword() ?: ""
                            CloudType.SFTP -> entity.getExtraEntity<SFTPExtra>()?.resticPassword ?: ""
                            else -> ""
                        }
                        val ok = when (entity.type) {
                            CloudType.S3 -> resticRepoCos.checkCosRepository(entity, password).isSuccess
                            CloudType.FTP -> resticRepoFtp.checkFtpRepository(entity, password).isSuccess
                            CloudType.WEBDAV -> resticRepoWebdav.checkWebdavRepository(entity, password).isSuccess
                            CloudType.SFTP -> resticRepoSftp.checkSftpRepository(entity, password).isSuccess
                            else -> true
                        }
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
                    // ===== 本地备份前仓库可用性前置检查（fail-fast）=====
                    _isTesting.value = true
                    runCatching {
                        // 与服务层一致地解析本地 restic 仓库路径与密码
                        val repoPath = mContext.readResticRepoPath()
                            ?: File(mContext.filesDir, "restic_repo").absolutePath
                        val password = mContext.readResticPassword() ?: "databackup_default"

                        val ok = resticRepo.checkRepository(repoPath, password)
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
    private val _medium: MutableStateFlow<List<MediaEntity>> = MutableStateFlow(listOf())
    private val _mediumSize: MutableStateFlow<String> = MutableStateFlow("")

    val accounts: StateFlow<List<DialogRadioItem<Any>>> = _accounts.stateInScope(listOf())
    val isTesting: StateFlow<Boolean> = _isTesting.stateInScope(false)
    val medium: StateFlow<List<MediaEntity>> = _medium.stateInScope(listOf())
    val mediumSize: StateFlow<String> = _mediumSize.stateInScope("")
}