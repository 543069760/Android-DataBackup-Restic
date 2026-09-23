package com.xayah.feature.main.restore

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.navigation.NavHostController
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.ResticRepoLocator
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.datastore.readLastRestoreTime
import com.xayah.core.datastore.saveCloudActivatedAccountName
import com.xayah.core.datastore.readResticOtgRepoPath
import com.xayah.core.model.OpType
import com.xayah.core.model.StorageMode
import com.xayah.core.model.Target
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.encodeURL
import com.xayah.core.util.encodedURLWithSpace
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.navigateSingle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class IndexUiState(
    val storageIndex: Int,
    val storageType: StorageMode,
    val cloudEntity: CloudEntity?,
    val packages: List<PackageEntity>,
    val packagesSize: String,
    val medium: List<MediaEntity>,
    val mediumSize: String,
) : UiState

sealed class IndexUiIntent : UiIntent {
    data object UpdateApps : IndexUiIntent()
    data object UpdateFiles : IndexUiIntent()
    data class SetCloudEntity(val name: String) : IndexUiIntent()
    data class ToAppList(val navController: NavHostController) : IndexUiIntent()
    data class ToFileList(val navController: NavHostController) : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pkgRepo: PackageRepository,
    private val mediaRepo: MediaRepository,
    private val cloudRepo: CloudRepository,
    private val directoryRepo: DirectoryRepository,
    private val resticRepoLocator: ResticRepoLocator,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(
    IndexUiState(
        storageIndex = 0,
        storageType = StorageMode.Local,
        cloudEntity = null,
        packages = listOf(),
        packagesSize = "",
        medium = listOf(),
        mediumSize = "",
    )
) {
    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.UpdateApps -> {
                val packages = (when (state.storageType) {
                    // Otg 与 Local 都走本地 restic：仓库路径已由恢复入口前置对齐指到 OTG
                    StorageMode.Local, StorageMode.Otg ->
                        pkgRepo.queryPackages(OpType.RESTORE, "", context.localBackupSaveDir())

                    StorageMode.Cloud -> when {
                        (state.cloudEntity == null) -> listOf()
                        else -> pkgRepo.queryPackages(OpType.RESTORE, state.cloudEntity.name, state.cloudEntity.remote)
                    }
                })
                var bytes = 0.0
                packages.forEach { bytes += it.displayStatsBytes }
                emitState(state.copy(packages = packages, packagesSize = bytes.formatSize()))
            }

            is IndexUiIntent.UpdateFiles -> {
                val medium = (when (state.storageType) {
                    // Otg 与 Local 都走本地 restic
                    StorageMode.Local, StorageMode.Otg ->
                        mediaRepo.query(OpType.RESTORE, "", context.localBackupSaveDir())

                    StorageMode.Cloud -> when {
                        (state.cloudEntity == null) -> listOf()
                        else -> mediaRepo.query(OpType.RESTORE, state.cloudEntity.name, state.cloudEntity.remote)
                    }
                })
                var bytes = 0.0
                medium.forEach { bytes += it.displayStatsBytes }
                emitState(state.copy(medium = medium, mediumSize = bytes.formatSize()))
            }

            is IndexUiIntent.SetCloudEntity -> {
                context.saveCloudActivatedAccountName(intent.name)
                emitState(state.copy(cloudEntity = cloudRepo.queryByName(intent.name)))
                emitIntent(IndexUiIntent.UpdateApps)
                emitIntent(IndexUiIntent.UpdateFiles)
            }

            is IndexUiIntent.ToAppList -> {
                withMainContext {
                    when (state.storageType) {
                        // Otg 复用本地 restic 恢复路由（仓库在 OTG，路径已前置对齐）
                        StorageMode.Local, StorageMode.Otg -> {
                            val isOtg = state.storageType == StorageMode.Otg
                            intent.navController.navigateSingle(
                                MainRoutes.ResticRestore.getRoute(isOtg = isOtg)  // 本地/OTG Restic恢复
                            )
                        }
                        StorageMode.Cloud -> {
                            // 云端Restic恢复，传递账户信息
                            if (state.cloudEntity != null) {
                                val encodedAccountName = state.cloudEntity.name.encodeURL()
                                intent.navController.navigateSingle(
                                    MainRoutes.CloudRestore.getRoute(accountName = encodedAccountName)
                                )
                            }
                        }
                    }
                }
            }

            is IndexUiIntent.ToFileList -> {
                withMainContext {
                    when (state.storageType) {
                        // Otg 复用本地文件恢复路由
                        StorageMode.Local, StorageMode.Otg -> {
                            val isOtg = state.storageType == StorageMode.Otg
                            intent.navController.navigateSingle(
                                MainRoutes.List.getRoute(
                                    target = Target.Files,
                                    opType = OpType.RESTORE,
                                    backupDir = context.localBackupSaveDir().encodeURL(),
                                    isOtg = isOtg
                                )
                            )
                        }

                        StorageMode.Cloud -> {
                            if (state.cloudEntity != null) {
                                intent.navController.navigateSingle(
                                    MainRoutes.List.getRoute(
                                        target = Target.Files,
                                        opType = OpType.RESTORE,
                                        cloudName = state.cloudEntity.name.encodeURL(),
                                        backupDir = state.cloudEntity.remote.encodeURL()
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private val _lastRestoreTime: Flow<Long> = context.readLastRestoreTime().flowOnIO()
    val lastRestoreTimeState: StateFlow<Long> = _lastRestoreTime.stateInScope(0)

    private val _accounts: Flow<List<DialogRadioItem<Any>>> = cloudRepo.clouds.map { entities ->
        entities.map {
            DialogRadioItem(
                enum = Any(),
                title = it.name,
                desc = it.user,
            )
        }

    }.flowOnIO()
    val accounts: StateFlow<List<DialogRadioItem<Any>>> = _accounts.stateInScope(listOf())

    // 是否存在已初始化的 OTG restic 仓库（决定段选择是否出现「OTG USB」段）
    // 与备份页语义一致：按已保存的 OTG 仓库身份键（restic_otg_repo_path）判定，
    // 不再依赖固定层级 <mount>/restic_repo 扫描，嵌套子目录的仓库也不会漏。
    val hasOtgState: StateFlow<Boolean> =
        flow { emit(!context.readResticOtgRepoPath().isNullOrEmpty()) }
            .flowOnIO()
            .stateInScope(false)
}