package com.xayah.feature.main.restore

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.datastore.readBackupDirectory
import com.xayah.core.datastore.readS3ResticPassword
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.ResticProgressState
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.restic.ResticRepository
import com.xayah.core.rootservice.service.RemoteRootService
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
class CloudFilesRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepo: ResticRepository,
    private val mediaDao: MediaDao,
    private val filesRepo: FilesRepo,
    private val rootService: RemoteRootService,
    private val cloudRepo: CloudRepository
) : ViewModel() {

    companion object {
        private const val TAG = "CloudFilesRestore"
    }

    private var lastBytes = 0L
    private var lastTime = System.currentTimeMillis()
    private var accountName: String = ""

    private val _uiState = MutableStateFlow<CloudFilesRestoreUiState>(CloudFilesRestoreUiState.Loading)
    val uiState: StateFlow<CloudFilesRestoreUiState> = _uiState.asStateFlow()

    private val _resticProgress = MutableStateFlow(ResticProgressState())
    val resticProgress: StateFlow<ResticProgressState> = _resticProgress.asStateFlow()

    fun setCloudEntity(accountName: String) {
        Log.d(TAG, "=== setCloudEntity 开始 ===")
        Log.d(TAG, "原始账户名: $accountName")

        val cleanAccountName = accountName.replace("accountName=", "")
        this.accountName = cleanAccountName
        Log.d(TAG, "清理后账户名: $cleanAccountName")

        viewModelScope.launch {
            Log.d(TAG, "开始查询云端账户: $cleanAccountName")
            try {
                val cloudEntity = cloudRepo.queryByName(cleanAccountName)
                if (cloudEntity != null) {
                    Log.d(TAG, "成功找到云端账户: ${cloudEntity.name}, 类型: ${cloudEntity.type}, 远程路径: ${cloudEntity.remote}")
                    loadCloudBackedUpFiles(cloudEntity)
                } else {
                    Log.e(TAG, "云端账户查询失败: $cleanAccountName")
                    _uiState.value = CloudFilesRestoreUiState.Error("账户不存在: $cleanAccountName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "查询云端账户时发生异常: $cleanAccountName", e)
                _uiState.value = CloudFilesRestoreUiState.Error("查询账户失败: ${e.message}")
            }
        }
        Log.d(TAG, "=== setCloudEntity 结束 ===")
    }

    fun loadCloudBackedUpFiles(cloudEntity: CloudEntity) {
        Log.d(TAG, "=== loadCloudBackedUpFiles 开始 ===")
        Log.d(TAG, "云端实体: ${cloudEntity.name}, 远程路径: ${cloudEntity.remote}")

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            _uiState.value = CloudFilesRestoreUiState.Loading
            Log.d(TAG, "UI状态设置为Loading")

            try {
                Log.d(TAG, "读取S3 Restic密码配置")
                val password = context.readS3ResticPassword()
                if (password.isNullOrEmpty()) {
                    Log.e(TAG, "S3 Restic密码未配置或为空")
                    _uiState.value = CloudFilesRestoreUiState.Error("Restic密码未配置")
                    return@launch
                }
                Log.d(TAG, "S3 Restic密码配置已读取 (长度: ${password.length})")

                // 优先使用 SQL 模式,失败时回退到 JSON 模式
                Log.d(TAG, "开始调用 listBackedUpFilesFromS3WithSql (SQL 模式)")
                val files: List<ResticBackupFiles> = try {
                    resticRepo.listBackedUpFilesFromS3WithSql(cloudEntity, password)
                } catch (e: Exception) {
                    Log.w(TAG, "SQL 模式失败,回退到 JSON 模式", e)
                    resticRepo.listBackedUpFilesFromS3(cloudEntity, password)
                }

                Log.d(TAG, "获取到 ${files.size} 个文件备份项")

                if (files.isEmpty()) {
                    Log.w(TAG, "未找到任何云端文件备份")
                    _uiState.value = CloudFilesRestoreUiState.Success(emptyList())
                    return@launch
                }

                Log.d(TAG, "开始分组文件备份")
                val groupedBackups = files
                    .groupBy {
                        // 提取前缀路径（去掉最后一层），与本地版本保持一致
                        val prefixPath = it.fullPath.substringBeforeLast("/")
                        Triple(it.mediaName, prefixPath, it.timestamp)
                    }
                    .map { (groupKey, backupsInGroup) ->
                        val (mediaName, prefixPath, timestamp) = groupKey
                        Log.d(TAG, "处理分组: $mediaName-$prefixPath-$timestamp, 包含 ${backupsInGroup.size} 个备份项")

                        val group = ResticFileBackupGroup(
                            mediaName = mediaName,
                            fullPath = prefixPath, // 使用前缀路径，与本地版本保持一致
                            timestamp = timestamp,
                            backups = backupsInGroup.sortedBy { backup ->
                                when (backup.dataType) {
                                    DataType.PACKAGE_MEDIA -> 0
                                    DataType.PACKAGE_CONFIG -> 1
                                    else -> 2
                                }
                            },
                            mediaLabel = mediaName
                        )
                        Log.d(TAG, "创建备份组: ${group.mediaName}, 时间戳: ${group.timestamp}, 备份数量: ${group.backups.size}")
                        group
                    }
                    .sortedByDescending { it.timestamp }

                val duration = System.currentTimeMillis() - startTime
                Log.d(TAG, "文件备份分组完成，共 ${groupedBackups.size} 个组，耗时: ${duration}ms")
                _uiState.value = CloudFilesRestoreUiState.Success(groupedBackups)

            } catch (e: Exception) {
                Log.e(TAG, "加载云端文件备份时发生异常", e)
                _uiState.value = CloudFilesRestoreUiState.Error("加载失败: ${e.message}")
            }
        }
        Log.d(TAG, "=== loadCloudBackedUpFiles 结束 ===")
    }

    suspend fun deleteCloudFileSnapshots(group: ResticFileBackupGroup): Boolean = withContext(Dispatchers.IO) {
        try {
            val cloudEntity = cloudRepo.queryByName(accountName) ?: return@withContext false
            val password = context.readS3ResticPassword() ?: return@withContext false

            val sortedBackups = group.backups.sortedBy { backup ->
                when (backup.dataType) {
                    DataType.PACKAGE_MEDIA -> 0
                    DataType.PACKAGE_CONFIG -> 1
                    else -> 2
                }
            }

            val totalSteps = sortedBackups.size + 1

            _resticProgress.value = ResticProgressState(
                totalDataTypes = totalSteps,
                currentDataTypeIndex = 0,
                isDeleting = true
            )

            sortedBackups.forEachIndexed { index, backup ->
                _resticProgress.value = _resticProgress.value.copy(
                    currentDataTypeIndex = index
                )

                val success = resticRepo.forgetSnapshotFromS3(
                    cloudEntity = cloudEntity,
                    password = password,
                    snapshotId = backup.snapshotId
                )

                if (!success) {
                    _resticProgress.value = ResticProgressState()
                    return@withContext false
                }
            }

            _resticProgress.value = _resticProgress.value.copy(
                currentDataTypeIndex = sortedBackups.size
            )

            val pruneSuccess = resticRepo.pruneS3Repository(cloudEntity, password)
            _resticProgress.value = ResticProgressState()

            pruneSuccess
        } catch (e: Exception) {
            Log.e(TAG, "删除文件快照异常: ${e.message}", e)
            _resticProgress.value = ResticProgressState()
            false
        }
    }

    suspend fun restoreFromCloudFileSnapshots(group: ResticFileBackupGroup): Boolean {
        Log.d(TAG, "=== restoreFromCloudFileSnapshots 开始 ===")
        Log.d(TAG, "恢复组: ${group.mediaName}, 时间戳: ${group.timestamp}, 备份数量: ${group.backups.size}")

        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                Log.d(TAG, "查询云端账户: $accountName")
                val cloudEntity = cloudRepo.queryByName(accountName)
                if (cloudEntity == null) {
                    Log.e(TAG, "云端账户不存在: $accountName")
                    return@withContext false
                }
                Log.d(TAG, "云端账户查询成功: ${cloudEntity.name}")

                Log.d(TAG, "读取S3 Restic密码")
                val password = context.readS3ResticPassword()
                if (password.isNullOrEmpty()) {
                    Log.e(TAG, "S3 Restic密码为空")
                    return@withContext false
                }
                Log.d(TAG, "S3 Restic密码读取成功")

                Log.d(TAG, "排序备份项，共 ${group.backups.size} 个")
                val sortedBackups = group.backups.sortedBy { backup ->
                    when (backup.dataType) {
                        DataType.PACKAGE_MEDIA -> 0
                        DataType.PACKAGE_CONFIG -> 1
                        else -> 2
                    }
                }
                Log.d(TAG, "备份项排序完成: ${sortedBackups.map { "${it.dataType.type}:${it.snapshotId}" }}")

                Log.d(TAG, "初始化进度状态")
                _resticProgress.value = _resticProgress.value.copy(
                    currentDataTypeIndex = 0,
                    totalDataTypes = sortedBackups.size
                )

                Log.d(TAG, "创建进度回调")
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
                        Log.v(TAG, "恢复进度: ${String.format("%.1f%%", progress * 100)}, 速度: $speedStr, 文件: $filesFinished/$filesTotal")
                    }

                    override fun onBackupProgress(percentDone: Float, bytesDone: Long, bytesTotal: Long, filesDone: Long, filesTotal: Long) {}
                }

                Log.d(TAG, "开始逐个恢复备份项")
                sortedBackups.forEachIndexed { index, backup ->
                    Log.d(TAG, "恢复第 ${index + 1}/${sortedBackups.size} 个备份: ${backup.dataType.type}")

                    _resticProgress.value = _resticProgress.value.copy(
                        currentDataTypeIndex = index,
                        totalDataTypes = sortedBackups.size
                    )

                    val targetPath = "${context.localBackupSaveDir()}/restore/"
                    val backupBaseDir = context.readBackupDirectory() ?: context.localBackupSaveDir()
                    val snapshotSubPath = "$backupBaseDir/files/${group.mediaName}"
                    val includePath = when (backup.dataType) {
                        DataType.PACKAGE_MEDIA -> "media.tar"
                        DataType.PACKAGE_CONFIG -> "media_restore_config.json"
                        else -> "${backup.dataType.type}.tar"
                    }
                    val fullTargetPath = "${targetPath}files/${group.mediaName}/"

                    Log.d(TAG, "恢复路径配置:")
                    Log.d(TAG, "  目标路径: $fullTargetPath")
                    Log.d(TAG, "  快照子路径: $snapshotSubPath")
                    Log.d(TAG, "  包含文件: $includePath")
                    Log.d(TAG, "  快照ID: ${backup.snapshotId}")

                    Log.d(TAG, "调用 restoreSnapshotFromS3")
                    val restoreStartTime = System.currentTimeMillis()
                    val success = resticRepo.restoreSnapshotFromS3(
                        cloudEntity = cloudEntity,
                        password = password,
                        snapshotId = backup.snapshotId,
                        targetPath = fullTargetPath,
                        snapshotSubPath = snapshotSubPath,
                        includePath = includePath,
                        progressCallback = progressCallback
                    )
                    val restoreDuration = System.currentTimeMillis() - restoreStartTime
                    Log.d(TAG, "restoreSnapshotFromS3 完成，结果: $success, 耗时: ${restoreDuration}ms")

                    if (!success) {
                        Log.e(TAG, "恢复失败: ${backup.dataType.type}, 快照ID: ${backup.snapshotId}")
                        _resticProgress.value = ResticProgressState()
                        return@withContext false
                    }
                    Log.d(TAG, "备份项恢复成功: ${backup.dataType.type}")
                }

                val totalDuration = System.currentTimeMillis() - startTime
                Log.d(TAG, "所有备份项恢复完成，总耗时: ${totalDuration}ms")
                _resticProgress.value = ResticProgressState(isCompleted = true)
                true

            } catch (e: Exception) {
                Log.e(TAG, "云端文件恢复异常: ${e.message}", e)
                false
            }
        }
    }

    suspend fun calculateSizesForActivatedMedia() {
        try {
            Log.d(TAG, "=== 开始计算激活媒体的大小 ===")
            val backupDir = "${context.localBackupSaveDir()}/restore/"
            val activatedMedia = mediaDao.queryActivated(OpType.RESTORE, "", backupDir)

            Log.d(TAG, "找到 ${activatedMedia.size} 个已激活媒体")
            activatedMedia.forEach { media ->
                Log.d(TAG, "计算媒体大小: ${media.name}")
                Log.d(TAG, "原始路径: ${media.path}")

                // 直接计算实际恢复文件的大小
                val mediaFile = File("${backupDir}files/${media.name}/media.tar")
                Log.d(TAG, "恢复文件路径: ${mediaFile.absolutePath}")
                Log.d(TAG, "媒体文件存在: ${mediaFile.exists()}, 大小: ${mediaFile.length()}")

                if (mediaFile.exists()) {
                    media.mediaInfo.displayBytes = mediaFile.length()
                    mediaDao.upsert(media)
                    Log.d(TAG, "大小计算完成: ${media.displayStatsBytes}")
                } else {
                    Log.w(TAG, "媒体文件不存在: ${mediaFile.absolutePath}")
                }
            }

            Log.d(TAG, "=== 激活媒体大小计算完成 ===")
        } catch (e: Exception) {
            Log.e(TAG, "计算大小失败", e)
        }
    }

    suspend fun refreshLocalDatabase(backupDir: String) {
        Log.d(TAG, "=== refreshLocalDatabase 开始 ===")
        Log.d(TAG, "备份目录: $backupDir")

        try {
            val restoreDir = "${context.localBackupSaveDir()}/restore/"
            Log.d(TAG, "恢复目录: $restoreDir")

            Log.d(TAG, "清理旧的恢复记录")
            mediaDao.deleteByOpTypeAndBackupDir(OpType.RESTORE, restoreDir)

            val restoreDirFile = File(restoreDir)
            Log.d(TAG, "检查恢复目录是否存在: ${restoreDirFile.exists()}")
            if (restoreDirFile.exists()) {
                val filesDir = File(restoreDirFile, "files")
                Log.d(TAG, "检查files目录是否存在: ${filesDir.exists()}, 路径: ${filesDir.path}")
                if (filesDir.exists()) {
                    Log.d(TAG, "开始扫描files目录")
                    scanFilesDirectory(filesDir)
                    Log.d(TAG, "files目录扫描完成")
                } else {
                    Log.w(TAG, "files目录不存在: ${filesDir.path}")
                }
            } else {
                Log.w(TAG, "恢复目录不存在: $restoreDir")
            }
            Log.d(TAG, "=== refreshLocalDatabase 完成 ===")
        } catch (e: Exception) {
            Log.e(TAG, "数据库刷新失败", e)
        }
    }

    private suspend fun scanFilesDirectory(filesDir: File) {
        Log.d(TAG, "=== scanFilesDirectory 开始 ===")
        Log.d(TAG, "files目录路径: ${filesDir.path}")
        Log.d(TAG, "files目录是否存在: ${filesDir.exists()}")

        try {
            val mediaDirs = filesDir.listFiles { file -> file.isDirectory }
            Log.d(TAG, "找到的媒体目录数量: ${mediaDirs?.size ?: 0}")

            mediaDirs?.forEach { mediaDir ->
                Log.d(TAG, "处理媒体目录: ${mediaDir.name}")
                val configFile = File(mediaDir, "media_restore_config.json")
                Log.d(TAG, "配置文件路径: ${configFile.path}")
                Log.d(TAG, "配置文件是否存在: ${configFile.exists()}")

                if (configFile.exists()) {
                    try {
                        Log.d(TAG, "开始读取配置文件: ${configFile.path}")
                        val mediaEntity = readMediaConfig(configFile, mediaDir.name)

                        if (mediaEntity != null) {
                            Log.d(TAG, "配置文件解析成功: ${mediaEntity.name}")
                            Log.d(TAG, "媒体信息: 名称=${mediaEntity.name}, 激活状态=${mediaEntity.extraInfo.activated}")

                            Log.d(TAG, "插入媒体实体到数据库")
                            mediaDao.upsert(mediaEntity)

                            Log.d(TAG, "更新媒体数据库状态")
                            updateFilesDatabase(mediaDir.name)

                            Log.d(TAG, "媒体目录处理完成: ${mediaDir.name}")
                        } else {
                            Log.w(TAG, "配置文件解析为空: ${configFile.path}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "处理媒体配置失败: ${configFile.path}", e)
                    }
                } else {
                    Log.w(TAG, "配置文件不存在: ${configFile.path}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "扫描媒体目录时发生异常", e)
        }

        Log.d(TAG, "=== scanFilesDirectory 结束 ===")
    }

    private suspend fun readMediaConfig(configFile: File, mediaName: String): MediaEntity? {
        Log.d(TAG, "=== readMediaConfig 开始 ===")
        Log.d(TAG, "配置文件: ${configFile.path}")
        Log.d(TAG, "媒体名称: $mediaName")

        return try {
            Log.d(TAG, "使用RemoteRootService读取JSON配置")
            val entity = rootService.readJson<MediaEntity>(configFile.path)

            if (entity != null) {
                Log.d(TAG, "原始配置读取成功: 名称=${entity.name}, 备份目录=${entity.indexInfo.backupDir}")

                val modifiedEntity = entity.copy(
                    id = 0,
                    indexInfo = entity.indexInfo.copy(
                        opType = OpType.RESTORE,
                        name = mediaName,
                        cloud = "",
                        backupDir = "${context.localBackupSaveDir()}/restore/"
                    ),
                    extraInfo = entity.extraInfo.copy(activated = false)
                )
                Log.d(TAG, "配置实体修改完成")
                modifiedEntity
            } else {
                Log.w(TAG, "读取的配置实体为null")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取媒体配置文件失败: ${configFile.path}", e)
            null
        }
    }

    private suspend fun updateFilesDatabase(mediaName: String) {
        Log.d(TAG, "=== updateFilesDatabase 开始: $mediaName ===")
        try {
            val backupDir = "${context.localBackupSaveDir()}/restore/"
            Log.d(TAG, "查询参数: opType=RESTORE, cloud=, backupDir=$backupDir")

            // 查询该媒体名称的所有记录
            Log.d(TAG, "开始查询媒体记录: $mediaName")
            val existingMedia = mediaDao.query(OpType.RESTORE, "", backupDir)
                .filter { it.name == mediaName }

            Log.d(TAG, "查询到的媒体记录数: ${existingMedia.size}")
            existingMedia.forEach { media ->
                Log.d(TAG, "媒体记录: ${media.name}, backupDir: ${media.indexInfo.backupDir}, activated: ${media.extraInfo.activated}, id: ${media.id}")
            }

            if (existingMedia.isNotEmpty()) {
                Log.d(TAG, "开始激活媒体: $mediaName, 找到 ${existingMedia.size} 个记录")
                existingMedia.forEach { media ->
                    Log.d(TAG, "激活媒体ID: ${media.id}")
                    mediaDao.activateById(media.id, true)
                    Log.d(TAG, "媒体已激活: $mediaName (ID: ${media.id})")
                }
            } else {
                Log.w(TAG, "未找到媒体记录: $mediaName")

                // 添加调试查询
                val allMedia = mediaDao.query(OpType.RESTORE, "", backupDir)
                Log.d(TAG, "数据库中所有相关媒体 (${allMedia.size} 个):")
                allMedia.forEach { media ->
                    Log.d(TAG, "- ${media.name}, backupDir: ${media.indexInfo.backupDir}, activated: ${media.extraInfo.activated}")
                }
            }

            Log.d(TAG, "=== 媒体激活完成: $mediaName ===")
        } catch (e: Exception) {
            Log.e(TAG, "激活媒体失败: ${e.message}", e)
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

sealed interface CloudFilesRestoreUiState {
    data object Loading : CloudFilesRestoreUiState
    data class Success(val groups: List<ResticFileBackupGroup>) : CloudFilesRestoreUiState
    data class Error(val message: String) : CloudFilesRestoreUiState
}