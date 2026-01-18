package com.xayah.feature.main.restore

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.datastore.readBackupDirectory
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.ResticProgressState
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.S3Extra
import com.xayah.core.restic.ResticRepository
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.io.File

@HiltViewModel
class CloudRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepo: ResticRepository,
    private val appsDao: PackageDao,
    private val rootService: RemoteRootService,
    private val cloudRepo: CloudRepository
) : ViewModel() {

    private var lastBytes = 0L
    private var lastTime = System.currentTimeMillis()

    private val _uiState = MutableStateFlow<CloudRestoreUiState>(CloudRestoreUiState.Loading)
    val uiState: StateFlow<CloudRestoreUiState> = _uiState.asStateFlow()

    // 进度状态跟踪：此处不再传递任何参数，因为你的 ResticProgressState 所有字段都有默认值
    private val _resticProgress = MutableStateFlow(ResticProgressState())
    val resticProgress: StateFlow<ResticProgressState> = _resticProgress.asStateFlow()

    fun setCloudEntity(accountName: String) {
        viewModelScope.launch {
            val cloudEntity = cloudRepo.queryByName(accountName)
            if (cloudEntity != null) {
                loadCloudBackedUpApps(cloudEntity)
            } else {
                _uiState.value = CloudRestoreUiState.Error("账户不存在: $accountName")
            }
        }
    }

    fun loadCloudBackedUpApps(cloudEntity: CloudEntity) {
        viewModelScope.launch {
            _uiState.value = CloudRestoreUiState.Loading
            val password = context.readResticPassword()
            if (password.isNullOrEmpty()) {
                _uiState.value = CloudRestoreUiState.Error("Restic密码未配置")
                return@launch
            }
            try {
                val apps = resticRepo.listBackedUpAppsFromS3(cloudEntity, password)
                val groupedBackups = apps
                    .groupBy { "${it.userId}-${it.packageName}-${it.timestamp}" }
                    .values
                    .map { backups ->
                        val first = backups.first()
                        ResticBackupGroup(
                            packageName = first.packageName,
                            userId = first.userId,
                            timestamp = first.timestamp,
                            backups = backups.sortedBy { backup ->
                                when (backup.dataType) {
                                    DataType.PACKAGE_APK -> 0
                                    DataType.PACKAGE_USER -> 1
                                    DataType.PACKAGE_USER_DE -> 2
                                    DataType.PACKAGE_DATA -> 3
                                    DataType.PACKAGE_OBB -> 4
                                    DataType.PACKAGE_MEDIA -> 5
                                    DataType.PACKAGE_CONFIG -> 6
                                    else -> 7
                                }
                            },
                            appLabel = try {
                                val pm = context.packageManager
                                val packageInfo = pm.getPackageInfo(first.packageName, 0)
                                packageInfo.applicationInfo?.loadLabel(pm)?.toString() ?: first.packageName
                            } catch (e: Exception) {
                                first.packageName
                            }
                        )
                    }
                    .sortedByDescending { it.timestamp }
                _uiState.value = CloudRestoreUiState.Success(groupedBackups)
            } catch (e: Exception) {
                Log.e("CloudRestore", "加载云端备份失败", e)
                _uiState.value = CloudRestoreUiState.Error("加载失败: ${e.message}")
            }
        }
    }

    suspend fun restoreFromCloudSnapshots(group: ResticBackupGroup, accountName: String): Boolean {
        return try {
            val cloudEntity = cloudRepo.queryByName(accountName) ?: return false
            val extra = GsonUtil().fromJson(cloudEntity.extra, S3Extra::class.java) ?: return false
            val repoUrl = resticRepo.buildS3ResticUrl(extra, cloudEntity.remote)
            val password = context.readResticPassword() ?: return false
            val backupBaseDir = context.readBackupDirectory()

            group.backups.forEachIndexed { index, backup ->
                // 更新进度起始状态：使用 .copy 确保不传入不存在的 'type'
                _resticProgress.value = _resticProgress.value.copy(
                    currentDataTypeIndex = index,
                    totalDataTypes = group.backups.size,
                    isCompleted = false,
                    percentage = 0f
                )

                val progressCallback = object : ResticRepository.ResticProgressCallback {
                    override fun onRestoreProgress(
                        filesFinished: Long, filesTotal: Long,
                        bytesWritten: Long, bytesTotal: Long,
                        filesSkipped: Long, bytesSkipped: Long
                    ) {
                        val progress = if (bytesTotal > 0) bytesWritten.toFloat() / bytesTotal else 0f
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = currentTime - lastTime
                        val speedStr = if (timeDiff > 0 && bytesWritten > lastBytes) {
                            ((bytesWritten - lastBytes) * 1000 / timeDiff).formatSpeed()
                        } else "0 B/s"

                        lastTime = currentTime
                        lastBytes = bytesWritten
                        _resticProgress.value = _resticProgress.value.copy(
                            filesFinished = filesFinished,
                            filesTotal = filesTotal,
                            filesSkipped = filesSkipped,
                            bytesWritten = bytesWritten,
                            bytesTotal = bytesTotal,
                            bytesSkipped = bytesSkipped,
                            percentage = progress,
                            speed = speedStr
                        )
                    }
                    override fun onBackupProgress(percentDone: Float, bytesDone: Long, bytesTotal: Long, filesDone: Long, filesTotal: Long) {}
                }

                val targetPath = "${context.localBackupSaveDir()}/restore/"
                val snapshotSubPath = "$backupBaseDir/apps/${backup.packageName}/user_${backup.userId}"
                val includePath = if (backup.dataType == DataType.PACKAGE_CONFIG) "package_restore_config.json" else "${backup.dataType.type}.tar"
                val fullTargetPath = "${targetPath}apps/${backup.packageName}/user_${backup.userId}/"

                val success = resticRepo.restoreSnapshot(
                    repoPath = repoUrl,
                    password = password,
                    snapshotId = backup.snapshotId,
                    targetPath = fullTargetPath,
                    includePath = includePath,
                    snapshotSubPath = snapshotSubPath,
                    progressCallback = progressCallback
                )
                if (!success) return false
            }

            refreshLocalDatabase("${context.localBackupSaveDir()}/restore/")
            _resticProgress.value = _resticProgress.value.copy(percentage = 1.0f, isCompleted = true)
            true
        } catch (e: Exception) {
            Log.e("CloudRestore", "恢复异常: ${e.message}")
            false
        }
    }

    suspend fun refreshLocalDatabase(backupDir: String) {
        try {
            appsDao.deleteByOpTypeAndBackupDir(OpType.RESTORE, backupDir)
            val restoreDir = File(backupDir)
            if (restoreDir.exists()) {
                val appsDir = File(restoreDir, "apps")
                if (appsDir.exists()) {
                    scanAppsDirectory(appsDir)
                }
            }
        } catch (e: Exception) {
            Log.e("CloudRestore", "数据库刷新失败", e)
        }
    }

    private suspend fun scanAppsDirectory(appsDir: File) {
        appsDir.listFiles { file -> file.isDirectory }?.forEach { packageDir ->
            packageDir.listFiles { file -> file.isDirectory }?.forEach { userDir ->
                val configFile = File(userDir, "package_restore_config.json")
                if (configFile.exists()) {
                    readPackageConfig(configFile, packageDir.name, userDir.name)?.let {
                        appsDao.upsert(it)
                        updateDatabase(packageDir.name)
                    }
                }
            }
        }
    }

    private suspend fun readPackageConfig(configFile: File, packageName: String, userDirName: String): PackageEntity? {
        return try {
            val entity = rootService.readJson<PackageEntity>(configFile.path)
            entity?.copy(
                id = 0,
                indexInfo = entity.indexInfo.copy(
                    opType = OpType.RESTORE,
                    packageName = packageName,
                    userId = userDirName.split("_").lastOrNull()?.toIntOrNull() ?: 0,
                    cloud = "",
                    backupDir = "${context.localBackupSaveDir()}/restore/"
                ),
                extraInfo = entity.extraInfo.copy(activated = false)
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun updateDatabase(packageName: String) {
        appsDao.queryPackages(OpType.RESTORE, "", "${context.localBackupSaveDir()}/restore/")
            .filter { it.packageName == packageName }
            .forEach { appsDao.activateById(it.id, true) }
    }

    private fun Long.formatSpeed(): String {
        return when {
            this < 1024 -> "$this B/s"
            this < 1024 * 1024 -> "${this / 1024} KiB/s"
            this < 1024 * 1024 * 1024 -> "${this / (1024 * 1024)} MiB/s"
            else -> "${this / (1024 * 1024 * 1024)} GiB/s"
        }
    }
}

sealed interface CloudRestoreUiState {
    data object Loading : CloudRestoreUiState
    data class Success(val groups: List<ResticBackupGroup>) : CloudRestoreUiState
    data class Error(val message: String) : CloudRestoreUiState
}