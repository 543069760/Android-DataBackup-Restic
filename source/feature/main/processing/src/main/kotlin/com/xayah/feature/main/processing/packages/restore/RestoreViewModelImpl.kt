package com.xayah.feature.main.processing.packages.restore

import android.util.Log
import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.SavedStateHandle
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.model.OpType
import com.xayah.core.model.StorageMode
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.network.client.getCloud
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.packages.restore.ProcessingServiceProxyCloudImpl
import com.xayah.core.service.packages.restore.ProcessingServiceProxyLocalImpl
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.util.LogUtil
import com.xayah.core.util.decodeURL
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.processing.UpdateAppsWithFilter
import com.xayah.feature.main.processing.AbstractPackagesProcessingViewModel
import com.xayah.feature.main.processing.FinishSetup
import com.xayah.feature.main.processing.GetUsers
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
import javax.inject.Inject

@ExperimentalCoroutinesApi
@ExperimentalMaterial3Api
@HiltViewModel
class RestoreViewModelImpl @Inject constructor(
    @ApplicationContext private val mContext: Context,
    private val mRootService: RemoteRootService,
    mTaskRepo: TaskRepository,
    private val mPkgRepo: PackageRepository,
    private val mCloudRepo: CloudRepository,
    private val appsRepo: AppsRepo,
    mLocalService: ProcessingServiceProxyLocalImpl,
    mCloudService: ProcessingServiceProxyCloudImpl,
    private val args: SavedStateHandle,
) : AbstractPackagesProcessingViewModel(mContext, mRootService, mTaskRepo, mLocalService, mCloudService) {
    private var currentPackageNameFilter: String = ""
    override suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            is UpdateApps -> {
                val cloud: String
                val backupSaveDir: String
                if (uiState.value.cloudEntity == null) {
                    cloud = ""
                    backupSaveDir = "${mContext.localBackupSaveDir()}/restore/"
                } else {
                    cloud = ""  // 修复：云端恢复也使用空字符串，与数据库记录匹配
                    backupSaveDir = "${mContext.localBackupSaveDir()}/restore/"
                }
                val packages = mPkgRepo.queryActivated(OpType.RESTORE, cloud, backupSaveDir)
                LogUtil.log { "RestoreViewModelImpl.UpdateApps" to "Query activated apps, cloud: $cloud, backupDir: $backupSaveDir" }
                LogUtil.log { "RestoreViewModelImpl.UpdateApps" to "Queried apps count: ${packages.size}" }
                var bytes = 0.0
                packages.forEach {
                    bytes += it.storageStatsBytes
                }
                _packages.value = packages
                _packagesSize.value = bytes.formatSize()
            }

            is UpdateAppsWithFilter -> {
                currentPackageNameFilter = intent.packageNameFilter
                Log.d("RestoreViewModelImpl", "处理 UpdateAppsWithFilter: ${intent.packageNameFilter}")

                val cloud: String
                val backupSaveDir: String
                if (uiState.value.cloudEntity == null) {
                    cloud = ""
                    backupSaveDir = "${mContext.localBackupSaveDir()}/restore/"
                } else {
                    cloud = ""
                    backupSaveDir = "${mContext.localBackupSaveDir()}/restore/"
                }

                Log.d("RestoreViewModelImpl", "查询参数: cloud=$cloud, backupDir=$backupSaveDir")

                // 使用 mPkgRepo 而不是 appsRepo，并传入正确的参数
                val allActivatedApps = mPkgRepo.queryActivated(OpType.RESTORE, cloud, backupSaveDir)
                Log.d("RestoreViewModelImpl", "所有激活应用数: ${allActivatedApps.size}")
                allActivatedApps.forEach { app ->
                    Log.d("RestoreViewModelImpl", "激活应用: ${app.packageName}, cloud=${app.indexInfo.cloud}, backupDir=${app.indexInfo.backupDir}")
                }

                val packages = allActivatedApps.filter { it.packageName == intent.packageNameFilter }
                Log.d("RestoreViewModelImpl", "筛选结果: 找到 ${packages.size} 个应用")

                var bytes = 0.0
                packages.forEach {
                    bytes += it.displayStatsBytes
                }
                _packages.value = packages
                _packagesSize.value = bytes.formatSize()
                Log.d("RestoreViewModelImpl", "更新完成: packages=${packages.size}, size=${bytes.formatSize()}")
            }

            is SetCloudEntity -> {
                val name = args.get<String>(MainRoutes.ARG_ACCOUNT_NAME)?.decodeURL()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    emitState(state.copy(storageIndex = 1, storageType = StorageMode.Cloud, cloudEntity = mCloudRepo.queryByName(name)))
                } else {
                    emitState(state.copy(storageIndex = 0, storageType = StorageMode.Local, cloudEntity = null))
                }
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
                        emitEffect(IndexUiEffect.DismissSnackbar)
                        withMainContext {
                            intent.navController.popBackStack()
                            intent.navController.navigateSingle(MainRoutes.PackagesRestoreProcessing.route)
                            mLocalService.startRestore(currentPackageNameFilter)
                        }
                    }.onFailure {
                        emitEffect(IndexUiEffect.DismissSnackbar)
                        if (it.localizedMessage != null)
                            emitEffectOnIO(IndexUiEffect.ShowSnackbar(type = SnackbarType.Error, message = it.localizedMessage!!, duration = SnackbarDuration.Long))
                    }
                    _isTesting.value = false
                } else {
                    withMainContext {
                        intent.navController.popBackStack()
                        intent.navController.navigateSingle(MainRoutes.PackagesRestoreProcessing.route)
                        mLocalService.startRestore(currentPackageNameFilter)
                    }
                }
            }

            is GetUsers -> {
                val users = mRootService.getUsers().map { it.id }.toMutableSet()
                mPkgRepo.queryUserIds(OpType.RESTORE).forEach {
                    users.add(it)
                }
                val restoreUsers = mutableListOf(
                    DialogRadioItem(
                        enum = Any(),
                        title = mContext.getString(R.string.backup_user),
                    )
                )
                users.sorted().forEach {
                    restoreUsers.add(
                        DialogRadioItem(
                            enum = Any(),
                            title = it.toString(),
                        )
                    )
                }
                _restoreUsers.value = restoreUsers
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
    private val _restoreUsers: MutableStateFlow<List<DialogRadioItem<Any>>> = MutableStateFlow(listOf(DialogRadioItem(enum = Any(), title = mContext.getString(R.string.backup_user))))

    val accounts: StateFlow<List<DialogRadioItem<Any>>> = _accounts.stateInScope(listOf())
    val isTesting: StateFlow<Boolean> = _isTesting.stateInScope(false)
    val packages: StateFlow<List<PackageEntity>> = _packages.stateInScope(listOf())
    val packagesSize: StateFlow<String> = _packagesSize.stateInScope("")
    val restoreUsers: StateFlow<List<DialogRadioItem<Any>>> = _restoreUsers.stateInScope(listOf(DialogRadioItem(enum = Any(), title = mContext.getString(R.string.backup_user))))
}