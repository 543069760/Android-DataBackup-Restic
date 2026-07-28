package com.xayah.feature.main.restore

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.datastore.readBackupDirectory
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readS3ResticPassword
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.ResticProgressState
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.restic.ResticRepository
import com.xayah.core.restic.ResticRepositoryCos
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.decodeURL
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import javax.inject.Inject
import java.io.File

@HiltViewModel
class CloudRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepoCos: ResticRepositoryCos,
    private val appsDao: PackageDao,
    private val appsRepo: com.xayah.core.data.repository.AppsRepo,
    private val rootService: RemoteRootService,
    private val cloudRepo: CloudRepository
) : ViewModel() {

    private var lastBytes = 0L
    private var lastTime = System.currentTimeMillis()
    private var accountName: String = "" // 添加账户名存储

    private val _uiState = MutableStateFlow<CloudRestoreUiState>(CloudRestoreUiState.Loading)
    val uiState: StateFlow<CloudRestoreUiState> = _uiState.asStateFlow()

    private val _resticProgress = MutableStateFlow(ResticProgressState())
    val resticProgress: StateFlow<ResticProgressState> = _resticProgress.asStateFlow()

    fun setCloudEntity(accountName: String) {
        val cleanAccountName = accountName.replace("accountName=", "").decodeURL()
        this.accountName = cleanAccountName // 存储账户名
        Log.d("CloudRestore", "setCloudEntity 被调用，账户名: $accountName")
        viewModelScope.launch {
            Log.d("CloudRestore", "开始查询云端账户: $cleanAccountName")
            val cloudEntity = cloudRepo.queryByName(cleanAccountName)
            if (cloudEntity != null) {
                Log.d("CloudRestore", "找到云端账户: ${cloudEntity.name}, 类型: ${cloudEntity.type}")
                Log.d("CloudRestore", "准备调用 loadCloudBackedUpApps")
                loadCloudBackedUpApps(cloudEntity)
            } else {
                Log.e("CloudRestore", "云端账户查询失败: $cleanAccountName")
                _uiState.value = CloudRestoreUiState.Error("账户不存在: $cleanAccountName")
            }
        }
    }

    fun loadCloudBackedUpApps(cloudEntity: CloudEntity) {
        Log.d("CloudRestore", "=== loadCloudBackedUpApps 开始 ===")
        viewModelScope.launch {
            _uiState.value = CloudRestoreUiState.Loading
            val password = context.readS3ResticPassword()
            if (password.isNullOrEmpty()) {
                _uiState.value = CloudRestoreUiState.Error("Restic密码未配置")
                return@launch
            }
            try {
                // 明确指定类型，消除歧义
                // JNI 模式（opendal:cos，走 rootService.listRusticSnapshotsDb + parseAppsDb）
                // 该方法内部已 try/catch，失败时返回 emptyList()，不再需要二进制 fallback
                val apps: List<ResticBackupApp> =
                    resticRepoCos.listBackedUpAppsFromS3WithSqlJni(cloudEntity, password)

                val groupedBackups = apps
                    .groupBy { "${it.userId}-${it.packageName}-${it.timestamp}" }
                    .map { entry ->
                        val backupsInGroup = entry.value
                        val first = backupsInGroup.first()
                        ResticBackupGroup(
                            packageName = first.packageName,
                            userId = first.userId,
                            timestamp = first.timestamp,
                            backups = backupsInGroup.sortedBy { backup ->
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
                _uiState.value = CloudRestoreUiState.Error("加载失败: ${e.message}")
            }
        }
    }

    suspend fun restoreFromCloudSnapshots(group: ResticBackupGroup): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val cloudEntity = cloudRepo.queryByName(accountName) ?: return@withContext false
                val password = context.readS3ResticPassword() ?: return@withContext false

                // 按优先级排序数据类型
                val sortedBackups = group.backups.sortedBy { backup ->
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
                }

                // 初始化进度状态
                _resticProgress.value = _resticProgress.value.copy(
                    currentDataTypeIndex = 0,
                    totalDataTypes = sortedBackups.size
                )

                // 创建进度回调
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

                    override fun onBackupProgress(
                        percentDone: Float, bytesDone: Long,
                        bytesTotal: Long, filesDone: Long, filesTotal: Long,
                        speed: Long
                    ) {
                        // 原有方法体保持不变；restore 相关 VM 里 onBackupProgress 备份时不触发，可保留空/日志实现
                    }
                }

                sortedBackups.forEachIndexed { index, backup ->
                    // 更新当前数据类型索引
                    _resticProgress.value = _resticProgress.value.copy(
                        currentDataTypeIndex = index,
                        totalDataTypes = sortedBackups.size
                    )

                    // 使用与本地恢复相同的路径逻辑
                    val targetPath = "${context.localBackupSaveDir()}/restore/"
                    val backupBaseDir = context.readBackupDirectory() ?: context.localBackupSaveDir()
                    val snapshotSubPath = "$backupBaseDir/apps/${backup.packageName}/user_${backup.userId}"
                    val includePath = when (backup.dataType) {
                        DataType.PACKAGE_CONFIG -> "package_restore_config.json"
                        else -> "${backup.dataType.type}.tar"
                    }
                    val fullTargetPath = "${targetPath}apps/${backup.packageName}/user_${backup.userId}/"

                    val success = resticRepoCos.restoreSnapshotFromCos(
                        cloudEntity = cloudEntity,
                        password = password,
                        snapshotId = backup.snapshotId,
                        targetPath = fullTargetPath,
                        snapshotSubPath = snapshotSubPath,
                        includePath = includePath,
                        progressCallback = progressCallback
                    )

                    if (!success) {
                        Log.e("CloudRestore", "恢复失败: ${backup.dataType.type}, 快照ID: ${backup.snapshotId}")
                        _resticProgress.value = ResticProgressState()
                        return@withContext false
                    }
                }
                _resticProgress.value = ResticProgressState(isCompleted = true)
                true
            } catch (e: Exception) {
                Log.e("CloudRestore", "云端恢复异常: ${e.message}", e)
                false
            }
        }
    }

    suspend fun deleteCloudSnapshots(group: ResticBackupGroup): Boolean = withContext(Dispatchers.IO) {
        try {
            val cloudEntity = cloudRepo.queryByName(accountName) ?: return@withContext false
            val password = context.readS3ResticPassword() ?: return@withContext false

            val sortedBackups = group.backups.sortedBy { backup ->
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
            }

            // 总步骤 = 快照数量 + 1 (prune)
            val totalSteps = sortedBackups.size + 1

            // 初始化删除进度状态
            _resticProgress.value = ResticProgressState(
                totalDataTypes = totalSteps,
                currentDataTypeIndex = 0,
                isDeleting = true
            )

            // 逐个删除快照
            sortedBackups.forEachIndexed { index, backup ->
                Log.d("CloudRestore", "删除第 ${index + 1}/${sortedBackups.size} 个快照: ${backup.dataType.type}")

                _resticProgress.value = _resticProgress.value.copy(
                    currentDataTypeIndex = index
                )

                val success = resticRepoCos.forgetSnapshotFromCos(
                    cloudEntity = cloudEntity,
                    password = password,
                    snapshotId = backup.snapshotId
                )

                if (!success) {
                    _resticProgress.value = ResticProgressState()
                    return@withContext false
                }
            }

            // 最后一步: 执行 prune
            Log.d("CloudRestore", "执行 prune 清理 (步骤 ${totalSteps}/${totalSteps})")
            _resticProgress.value = _resticProgress.value.copy(
                currentDataTypeIndex = sortedBackups.size  // 最后一步
            )

            val pruneSuccess = resticRepoCos.pruneCosRepository(cloudEntity, password)

            // 重置进度状态
            _resticProgress.value = ResticProgressState()

            pruneSuccess
        } catch (e: Exception) {
            Log.e("CloudRestore", "删除快照异常: ${e.message}", e)
            _resticProgress.value = ResticProgressState()
            false
        }
    }

    suspend fun refreshLocalDatabase(backupDir: String) {
        Log.d("CloudRestore", "=== 开始刷新本地数据库 ===")
        try {
            // 参考本地恢复：使用固定的restore目录进行清理
            val restoreDir = "${context.localBackupSaveDir()}/restore/"
            Log.d("CloudRestore", "清理旧的恢复记录: $restoreDir")
            appsDao.deleteByOpTypeAndBackupDir(OpType.RESTORE, restoreDir)

            val restoreDirFile = File(restoreDir)
            Log.d("CloudRestore", "检查恢复目录是否存在: ${restoreDirFile.exists()}")
            if (restoreDirFile.exists()) {
                val appsDir = File(restoreDirFile, "apps")
                Log.d("CloudRestore", "检查apps目录是否存在: ${appsDir.exists()}, 路径: ${appsDir.path}")
                if (appsDir.exists()) {
                    Log.d("CloudRestore", "开始扫描apps目录")
                    scanAppsDirectory(appsDir)
                    Log.d("CloudRestore", "apps目录扫描完成")
                } else {
                    Log.w("CloudRestore", "apps目录不存在: ${appsDir.path}")
                }
            } else {
                Log.w("CloudRestore", "恢复目录不存在: $restoreDir")
            }
            Log.d("CloudRestore", "=== 本地数据库刷新完成 ===")
        } catch (e: Exception) {
            Log.e("CloudRestore", "数据库刷新失败", e)
        }
    }

    private suspend fun scanAppsDirectory(appsDir: File) {
        Log.d("CloudRestore", "=== 开始扫描apps目录 ===")
        Log.d("CloudRestore", "apps目录路径: ${appsDir.path}")
        Log.d("CloudRestore", "apps目录是否存在: ${appsDir.exists()}")

        val packageDirs = appsDir.listFiles { file -> file.isDirectory }
        Log.d("CloudRestore", "找到的包目录数量: ${packageDirs?.size ?: 0}")

        packageDirs?.forEach { packageDir ->
            Log.d("CloudRestore", "处理包目录: ${packageDir.name}")
            val userDirs = packageDir.listFiles { file -> file.isDirectory }
            Log.d("CloudRestore", "包 ${packageDir.name} 中的用户目录数量: ${userDirs?.size ?: 0}")

            userDirs?.forEach { userDir ->
                Log.d("CloudRestore", "处理用户目录: ${userDir.name}")
                val configFile = File(userDir, "package_restore_config.json")
                Log.d("CloudRestore", "配置文件路径: ${configFile.path}")
                Log.d("CloudRestore", "配置文件是否存在: ${configFile.exists()}")

                if (configFile.exists()) {
                    try {
                        Log.d("CloudRestore", "开始读取配置文件: ${configFile.path}")
                        val entity = readPackageConfig(configFile, packageDir.name, userDir.name)

                        if (entity != null) {
                            Log.d("CloudRestore", "配置文件解析成功: ${entity.packageName}")
                            Log.d("CloudRestore", "应用信息: packageName=${entity.packageName}, userId=${entity.indexInfo.userId}")
                            Log.d("CloudRestore", "备份信息: opType=${entity.indexInfo.opType}, backupDir=${entity.indexInfo.backupDir}")

                            Log.d("CloudRestore", "插入数据库: ${entity.packageName}")
                            appsDao.upsert(entity)

                            Log.d("CloudRestore", "激活应用: ${packageDir.name}")
                            updateDatabase(packageDir.name)

                            Log.d("CloudRestore", "应用 ${packageDir.name} 处理完成")
                        } else {
                            Log.w("CloudRestore", "配置文件解析为空: ${configFile.path}")
                        }
                    } catch (e: Exception) {
                        Log.e("CloudRestore", "处理应用配置失败: ${configFile.path}", e)
                    }
                } else {
                    Log.w("CloudRestore", "配置文件不存在: ${configFile.path}")
                }
            }
        }

        Log.d("CloudRestore", "=== apps目录扫描完成 ===")
    }

    private suspend fun readPackageConfig(configFile: File, packageName: String, userDirName: String): PackageEntity? {
        Log.d("CloudRestore", "读取配置文件: ${configFile.path}")
        return try {
            val entity = rootService.readJson<PackageEntity>(configFile.path)
            Log.d("CloudRestore", "配置文件原始数据: packageName=${entity?.packageName}, userId=${entity?.indexInfo?.userId}")

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
            Log.e("CloudRestore", "读取配置文件失败: ${configFile.path}", e)
            null
        }
    }

    suspend fun calculateSizesForActivatedApps() {
        try {
            Log.d("CloudRestore", "=== 开始计算激活应用的大小 ===")
            val backupDir = "${context.localBackupSaveDir()}/restore/"
            // 修改：只查询激活的应用，与第二阶段保持一致
            val activatedApps = appsDao.queryActivated(OpType.RESTORE, "", backupDir)

            Log.d("CloudRestore", "找到 ${activatedApps.size} 个已激活应用")
            activatedApps.forEach { app ->
                Log.d("CloudRestore", "计算应用大小: ${app.packageName}")
                appsRepo.calculateLocalAppArchiveSize(app)
            }

            Log.d("CloudRestore", "=== 激活应用大小计算完成 ===")
        } catch (e: Exception) {
            Log.e("CloudRestore", "计算应用大小失败", e)
        }
    }

    private suspend fun updateDatabase(packageName: String) {
        Log.d("CloudRestore", "=== 开始激活应用: $packageName ===")
        try {
            val backupDir = "${context.localBackupSaveDir()}/restore/"
            Log.d("CloudRestore", "查询参数: opType=RESTORE, cloud=, backupDir=$backupDir")

            // 查询该包名的所有应用记录
            val existingApps = appsDao.queryPackages(OpType.RESTORE, "", backupDir)
                .filter { it.packageName == packageName }

            Log.d("CloudRestore", "查询到的应用记录数: ${existingApps.size}")
            existingApps.forEach { app ->
                Log.d("CloudRestore", "应用记录: ${app.packageName}, backupDir: ${app.indexInfo.backupDir}, activated: ${app.extraInfo.activated}, id: ${app.id}")
            }

            if (existingApps.isNotEmpty()) {
                Log.d("CloudRestore", "开始激活应用: $packageName, 找到 ${existingApps.size} 个记录")
                existingApps.forEach { app ->
                    Log.d("CloudRestore", "激活应用ID: ${app.id}")
                    appsDao.activateById(app.id, true)
                    Log.d("CloudRestore", "应用已激活: $packageName (ID: ${app.id})")
                }
            } else {
                Log.w("CloudRestore", "未找到应用记录: $packageName")

                // 添加调试查询
                val allApps = appsDao.queryPackages(OpType.RESTORE, "", backupDir)
                Log.d("CloudRestore", "数据库中所有相关应用 (${allApps.size} 个):")
                allApps.forEach { app ->
                    Log.d("CloudRestore", "- ${app.packageName}, backupDir: ${app.indexInfo.backupDir}, activated: ${app.extraInfo.activated}")
                }
            }

            Log.d("CloudRestore", "=== 应用激活完成: $packageName ===")
        } catch (e: Exception) {
            Log.e("CloudRestore", "激活应用失败: ${e.message}", e)
        }
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