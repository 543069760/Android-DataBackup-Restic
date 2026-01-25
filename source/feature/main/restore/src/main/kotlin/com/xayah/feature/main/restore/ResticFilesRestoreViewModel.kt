package com.xayah.feature.main.restore

import android.util.Log
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.restic.ResticRepository
import com.xayah.feature.main.restore.ResticFileBackupGroup
import com.xayah.core.model.ResticProgressState
import com.xayah.core.datastore.readBackupDirectory
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.OpType
import com.xayah.core.model.DataType
import com.xayah.core.rootservice.service.RemoteRootService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject
import java.io.File

sealed class ResticFilesRestoreUiState {
    object Loading : ResticFilesRestoreUiState()
    data class Success(val groups: List<ResticFileBackupGroup>) : ResticFilesRestoreUiState()
    data class Error(val message: String) : ResticFilesRestoreUiState()
}

@HiltViewModel
class ResticFilesRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepo: ResticRepository,
    private val rootService: RemoteRootService,
    private val mediaDao: MediaDao,
    private val filesRepo: FilesRepo
) : ViewModel() {

    // 添加进度状态流
    private val _resticProgress = MutableStateFlow(ResticProgressState())
    val resticProgress: StateFlow<ResticProgressState> = _resticProgress.asStateFlow()

    // 速度跟踪变量
    private var lastBytes = 0L
    private var lastTime = System.currentTimeMillis()

    private val _uiState = MutableStateFlow<ResticFilesRestoreUiState>(ResticFilesRestoreUiState.Loading)
    val uiState: StateFlow<ResticFilesRestoreUiState> = _uiState.asStateFlow()

    // 添加恢复方法
    suspend fun restoreFromResticSnapshots(group: ResticFileBackupGroup): Boolean {
        Log.d("ResticFilesRestore", "开始文件快照恢复流程，媒体名称: ${group.mediaName}")
        return try {
            Log.d("ResticFilesRestore", "读取 Restic 配置")
            val repoPath = context.readResticRepoPath()
            val password = context.readResticPassword()
            Log.d("ResticFilesRestore", "仓库路径: $repoPath")

            if (repoPath.isNullOrEmpty() || password.isNullOrEmpty()) {
                Log.e("ResticFilesRestore", "Restic 配置不完整")
                return false
            }

            // 使用用户配置的备份目录 + /restore/
            Log.d("ResticFilesRestore", "读取用户备份目录配置")
            val backupBaseDir = readBackupDirectory()
            val targetBasePath = "$backupBaseDir/restore/"
            Log.d("ResticFilesRestore", "恢复目标路径: $targetBasePath")

            // 按正确顺序排序：MEDIA 优先，CONFIG 最后
            Log.d("ResticFilesRestore", "排序数据类型，共 ${group.backups.size} 个备份项")
            val sortedBackups = group.backups.sortedBy { backup ->
                when (backup.dataType) {
                    DataType.PACKAGE_MEDIA -> 0
                    DataType.PACKAGE_CONFIG -> 1
                    else -> 2
                }
            }
            Log.d("ResticFilesRestore", "排序后的数据类型: ${sortedBackups.map { it.dataType.type }}")

            // 依次恢复每个数据类型
            Log.d("ResticFilesRestore", "开始逐个恢复数据类型")
            sortedBackups.forEachIndexed { index, backup ->
                Log.d("ResticFilesRestore", "恢复第 ${index + 1}/${sortedBackups.size} 个数据类型: ${backup.dataType.type}")
                _resticProgress.value = _resticProgress.value.copy(
                    currentDataTypeIndex = index,
                    totalDataTypes = sortedBackups.size
                )

                // 为当前 backup 创建专用的进度回调
                val progressCallback = object : ResticRepository.ResticProgressCallback {
                    override fun onRestoreProgress(
                        filesFinished: Long,
                        filesTotal: Long,
                        bytesWritten: Long,
                        bytesTotal: Long,
                        filesSkipped: Long,
                        bytesSkipped: Long
                    ) {
                        // 恢复进度处理
                        val progress = if (bytesTotal > 0) {
                            bytesWritten.toFloat() / bytesTotal
                        } else 0f

                        // 瞬时速度计算
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = currentTime - lastTime
                        val speed = if (timeDiff > 0 && bytesWritten > lastBytes) {
                            ((bytesWritten - lastBytes) * 1000 / timeDiff).formatSpeed()
                        } else "0 B/s"

                        lastTime = currentTime
                        lastBytes = bytesWritten

                        // 速度计算和状态更新
                        _resticProgress.value = ResticProgressState(
                            filesFinished = filesFinished,
                            filesTotal = filesTotal,
                            filesSkipped = filesSkipped,
                            bytesWritten = bytesWritten,
                            bytesTotal = bytesTotal,
                            bytesSkipped = bytesSkipped,
                            percentage = progress,
                            speed = speed,
                            currentDataTypeIndex = index,
                            totalDataTypes = sortedBackups.size
                        )
                    }

                    override fun onBackupProgress(
                        percentDone: Float,
                        bytesDone: Long,
                        bytesTotal: Long,
                        filesDone: Long,
                        filesTotal: Long
                    ) {
                        // 备份进度（文件恢复时不使用）
                    }
                }

                val targetPath = "${context.localBackupSaveDir()}/restore/"
                Log.d("ResticFilesRestore", "恢复到用户备份目录: $targetPath")
                val backupBaseDir = context.readBackupDirectory() ?: context.localBackupSaveDir()
                val snapshotSubPath = "$backupBaseDir/files/${group.mediaName}"
                val includePath = when (backup.dataType) {
                    DataType.PACKAGE_MEDIA -> "media.tar"
                    DataType.PACKAGE_CONFIG -> "media_restore_config.json"
                    else -> "${backup.dataType.type}.tar"
                }
                val fullTargetPath = "${targetPath}files/${group.mediaName}/"
                Log.d("ResticFilesRestore", "恢复 ${backup.dataType.type} 到目标: $targetPath")
                Log.d("ResticFilesRestore", "快照子路径: $snapshotSubPath")
                Log.d("ResticFilesRestore", "包含文件: $includePath")

                val success = resticRepo.restoreSnapshot(
                    repoPath = repoPath,
                    password = password,
                    snapshotId = backup.snapshotId,
                    targetPath = fullTargetPath,
                    includePath = includePath,
                    snapshotSubPath = snapshotSubPath,
                    progressCallback = progressCallback
                )

                if (!success) {
                    Log.e("ResticFilesRestore", "恢复失败: ${backup.dataType.type}, 快照ID: ${backup.snapshotId}")
                    _resticProgress.value = ResticProgressState()
                    return false
                }
                Log.d("ResticFilesRestore", "恢复成功: ${backup.dataType.type}")
            }

            Log.d("ResticFilesRestore", "所有数据类型恢复完成")
            _resticProgress.value = ResticProgressState(isCompleted = true)
            true
        } catch (e: Exception) {
            Log.e("ResticFilesRestore", "文件快照恢复异常: ${e.message}", e)
            false
        }
    }

    suspend fun refreshLocalDatabase(backupDir: String) {
        Log.d("ResticFilesRestore", "刷新媒体数据库: $backupDir")
        try {
            // 删除所有旧的恢复记录
            val restoreDir = "${context.localBackupSaveDir()}/restore/"
            mediaDao.deleteByOpTypeAndBackupDir(OpType.RESTORE, restoreDir)

            val restoreDirFile = File(backupDir)
            if (restoreDirFile.exists()) {
                Log.d("ResticFilesRestore", "开始扫描恢复目录: $backupDir")

                val filesDir = File(restoreDirFile, "files")
                if (filesDir.exists()) {
                    scanFilesDirectory(filesDir)  // 修复：使用正确的扫描方法
                } else {
                    Log.w("ResticFilesRestore", "files 目录不存在: ${filesDir.path}")
                }
            }
        } catch (e: Exception) {
            Log.e("ResticFilesRestore", "刷新数据库失败: ${e.message}", e)
        }
    }

    private suspend fun updateFilesDatabase(mediaName: String) {
        Log.d("ResticFilesRestore", "激活媒体: $mediaName")
        try {
            // 查询该媒体名称的所有记录
            val existingMedia = mediaDao.query(OpType.RESTORE, "", "${context.localBackupSaveDir()}/restore/")
                .filter { it.name == mediaName }

            if (existingMedia.isNotEmpty()) {
                existingMedia.forEach { media ->
                    mediaDao.activateById(media.id, true)
                    Log.d("ResticFilesRestore", "媒体已激活: $mediaName (ID: ${media.id})")
                }
            } else {
                Log.w("ResticFilesRestore", "未找到媒体记录: $mediaName")
            }
        } catch (e: Exception) {
            Log.e("ResticFilesRestore", "激活媒体失败: ${e.message}", e)
        }
    }

    private suspend fun scanFilesDirectory(filesDir: File) {
        val mediaDirs = filesDir.listFiles { file -> file.isDirectory }
        mediaDirs?.forEach { mediaDir ->
            val configFile = File(mediaDir, "media_restore_config.json")
            if (configFile.exists()) {
                try {
                    // 读取配置文件并更新数据库
                    val mediaEntity = readMediaConfig(configFile, mediaDir.name)
                    if (mediaEntity != null) {
                        mediaDao.upsert(mediaEntity)
                        Log.d("ResticFilesRestore", "媒体已插入数据库: ${mediaDir.name}")
                        updateFilesDatabase(mediaDir.name)
                        Log.d("ResticFilesRestore", "发现恢复的媒体: ${mediaDir.name}")
                    }
                } catch (e: Exception) {
                    Log.e("ResticFilesRestore", "处理媒体配置失败: ${configFile.path}", e)
                }
            }
        }
    }

    suspend fun calculateSizesForActivatedMedia() {
        try {
            Log.d("ResticFilesRestore", "=== 开始计算激活媒体的大小 ===")
            val backupDir = "${context.localBackupSaveDir()}/restore/"
            val activatedMedia = mediaDao.queryActivated(OpType.RESTORE, "", backupDir)

            Log.d("ResticFilesRestore", "找到 ${activatedMedia.size} 个已激活媒体")
            activatedMedia.forEach { media ->
                Log.d("ResticFilesRestore", "计算媒体大小: ${media.name}, 路径: ${media.path}")

                // 直接计算实际恢复文件的大小
                val mediaFile = File("${backupDir}files/${media.name}/media.tar")
                Log.d("ResticFilesRestore", "媒体文件存在: ${mediaFile.exists()}, 大小: ${mediaFile.length()}")

                if (mediaFile.exists()) {
                    media.mediaInfo.displayBytes = mediaFile.length()
                    mediaDao.upsert(media)
                    Log.d("ResticFilesRestore", "大小计算完成: ${media.displayStatsBytes}")
                } else {
                    Log.w("ResticFilesRestore", "媒体文件不存在: ${mediaFile.absolutePath}")
                }
            }

            Log.d("ResticFilesRestore", "=== 激活媒体大小计算完成 ===")
        } catch (e: Exception) {
            Log.e("ResticFilesRestore", "计算大小失败", e)
        }
    }

    suspend fun readBackupDirectory(): String {
        Log.d("ResticFilesRestore", "从 DataStore 读取备份目录配置")
        val backupDir = context.localBackupSaveDir()
        Log.d("ResticFilesRestore", "读取到的备份目录: $backupDir")
        return backupDir ?: throw Exception("备份目录未配置")
    }

    private fun Long.formatSpeed(): String {
        return when {
            this < 1024 -> "$this B/s"
            this < 1024 * 1024 -> "${this / 1024} KiB/s"
            this < 1024 * 1024 * 1024 -> "${this / (1024 * 1024)} MiB/s"
            else -> "${this / (1024 * 1024 * 1024)} GiB/s"
        }
    }

    private suspend fun readMediaConfig(configFile: File, mediaName: String): MediaEntity? {
        return try {
            val entity = rootService.readJson<MediaEntity>(configFile.path)
            entity?.copy(
                id = 0,
                indexInfo = entity.indexInfo.copy(
                    opType = OpType.RESTORE,
                    name = mediaName,
                    cloud = "",
                    backupDir = "${context.localBackupSaveDir()}/restore/"
                ),
                extraInfo = entity.extraInfo.copy(activated = false)
            )
        } catch (e: Exception) {
            Log.e("ResticFilesRestore", "读取媒体配置文件失败: ${configFile.path}", e)
            null
        }
    }

    fun loadBackedUpFiles() {
        Log.d("ResticFilesRestore", "=== loadBackedUpFiles 开始 ===")

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            _uiState.value = ResticFilesRestoreUiState.Loading
            Log.d("ResticFilesRestore", "UI状态设置为Loading")

            try {
                Log.d("ResticFilesRestore", "读取本地 Restic 配置")
                val repoPath = context.readResticRepoPath()
                val password = context.readResticPassword()

                if (repoPath.isNullOrEmpty() || password.isNullOrEmpty()) {
                    Log.e("ResticFilesRestore", "Restic配置不完整")
                    _uiState.value = ResticFilesRestoreUiState.Error("Restic not configured")
                    return@launch
                }
                Log.d("ResticFilesRestore", "Restic配置读取成功")

                Log.d("ResticFilesRestore", "开始调用 listBackedUpFiles")
                val files = resticRepo.listBackedUpFiles(repoPath, password)
                Log.d("ResticFilesRestore", "listBackedUpFiles 返回 ${files.size} 个文件备份项")

                if (files.isEmpty()) {
                    Log.w("ResticFilesRestore", "未找到任何本地文件备份")
                    _uiState.value = ResticFilesRestoreUiState.Success(emptyList())
                    return@launch
                }

                Log.d("ResticFilesRestore", "开始分组文件备份")
                val groupedBackups = files
                    .groupBy {
                        val prefixPath = it.fullPath.substringBeforeLast("/")
                        Triple(it.mediaName, prefixPath, it.timestamp)
                    }
                    .map { (groupKey, backupsInGroup) ->
                        val (mediaName, prefixPath, timestamp) = groupKey
                        Log.d("ResticFilesRestore", "处理分组: $mediaName-$prefixPath-$timestamp, 包含 ${backupsInGroup.size} 个备份项")

                        val group = ResticFileBackupGroup(
                            mediaName = mediaName,
                            fullPath = prefixPath,
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
                        Log.d("ResticFilesRestore", "创建备份组: ${group.mediaName}, 时间戳: ${group.timestamp}, 备份数量: ${group.backups.size}")
                        group
                    }
                    .sortedByDescending { it.timestamp }

                val duration = System.currentTimeMillis() - startTime
                Log.d("ResticFilesRestore", "文件备份分组完成，共 ${groupedBackups.size} 个组，耗时: ${duration}ms")
                _uiState.value = ResticFilesRestoreUiState.Success(groupedBackups)

            } catch (e: Exception) {
                Log.e("ResticFilesRestore", "加载本地文件备份时发生异常", e)
                _uiState.value = ResticFilesRestoreUiState.Error("加载失败: ${e.message}")
            }
        }
        Log.d("ResticFilesRestore", "=== loadBackedUpFiles 结束 ===")
    }
}