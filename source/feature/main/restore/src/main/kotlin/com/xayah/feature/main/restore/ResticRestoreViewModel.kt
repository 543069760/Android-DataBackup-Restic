package com.xayah.feature.main.restore

import android.util.Log
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.model.DataType
import com.xayah.core.model.ResticProgressState
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.restic.ResticRepository
import com.xayah.core.util.DateUtil
import com.xayah.feature.main.restore.ResticBackupGroup
import com.xayah.core.datastore.readBackupDirectory
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@HiltViewModel
class ResticRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepo: ResticRepository
) : ViewModel() {

    // 速度跟踪变量 - 添加到这里
    private var lastBytes = 0L
    private var lastTime = System.currentTimeMillis()

    private val _uiState = MutableStateFlow<ResticRestoreUiState>(ResticRestoreUiState.Loading)
    val uiState: StateFlow<ResticRestoreUiState> = _uiState.asStateFlow()

    // 添加进度状态跟踪
    private val _resticProgress = MutableStateFlow<ResticProgressState>(ResticProgressState())
    val resticProgress: StateFlow<ResticProgressState> = _resticProgress.asStateFlow()

    private val startTime = System.currentTimeMillis()

    fun loadBackedUpApps() {
        viewModelScope.launch {
            _uiState.value = ResticRestoreUiState.Loading

            val repoPath = context.readResticRepoPath()
            val password = context.readResticPassword()
            // 添加调试日志
            Log.d("ResticRestore", "读取到的 repoPath: $repoPath")
            Log.d("ResticRestore", "读取到的 password: ${if (password.isNullOrEmpty()) "空" else "已设置"}")

            if (repoPath.isNullOrEmpty() || password.isNullOrEmpty()) {
                _uiState.value = ResticRestoreUiState.Error("Restic not configured")
                return@launch
            }

            try {
                val apps = resticRepo.listBackedUpApps(repoPath, password)
                // 按 (userId, packageName, timestamp) 分组
                val groupedBackups = apps
                    .groupBy { "${it.userId}-${it.packageName}-${it.timestamp}" }
                    .values
                    .map { backups ->
                        val first = backups.first()
                        ResticBackupGroup(
                            packageName = first.packageName,
                            userId = first.userId,
                            timestamp = first.timestamp,
                            backups = backups.sortedBy { it.dataType.type },
                            appLabel = backups.firstOrNull()?.let { backup ->
                                // 通过 PackageManager 获取应用标签
                                try {
                                    val pm = context.packageManager
                                    val packageInfo = pm.getPackageInfo(backup.packageName, 0)
                                    packageInfo.applicationInfo?.loadLabel(pm)?.toString() ?: backup.packageName
                                } catch (e: Exception) {
                                    backup.packageName
                                }
                            } ?: first.packageName
                        )
                    }
                    .sortedByDescending { it.timestamp }

                _uiState.value = ResticRestoreUiState.Success(groupedBackups)
            } catch (e: Exception) {
                _uiState.value = ResticRestoreUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // 添加恢复方法
    suspend fun restoreFromResticSnapshots(group: ResticBackupGroup): Boolean {
        Log.d("ResticRestore", "开始快照恢复流程，包名: ${group.packageName}")
        return try {
            Log.d("ResticRestore", "读取 Restic 配置")
            val repoPath = context.readResticRepoPath()
            val password = context.readResticPassword()
            Log.d("ResticRestore", "仓库路径: $repoPath")

            if (repoPath.isNullOrEmpty() || password.isNullOrEmpty()) {
                Log.e("ResticRestore", "Restic 配置不完整")
                _uiState.value = ResticRestoreUiState.Error("Restic not configured")
                return false
            }

            // 使用用户配置的备份目录 + /restore/
            Log.d("ResticRestore", "读取用户备份目录配置")
            val backupBaseDir = readBackupDirectory()
            val targetBasePath = "$backupBaseDir/restore/"
            Log.d("ResticRestore", "恢复目标路径: $targetBasePath")

            // 按正确顺序排序：APK优先
            Log.d("ResticRestore", "排序数据类型，共 ${group.backups.size} 个备份项")
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
            Log.d("ResticRestore", "排序后的数据类型: ${sortedBackups.map { it.dataType.type }}")

            // 依次恢复每个数据类型
            Log.d("ResticRestore", "开始逐个恢复数据类型")
            sortedBackups.forEachIndexed { index, backup ->
                val userBackupDir = context.localBackupSaveDir()
                Log.d("ResticRestore", "恢复第 ${index + 1}/${sortedBackups.size} 个数据类型: ${backup.dataType.type}")
                _resticProgress.value = _resticProgress.value.copy(
                    currentDataTypeIndex = index,
                    totalDataTypes = sortedBackups.size
                )

                // 为当前 backup 创建专用的进度回调
                val progressCallback = object : ResticRepository.ResticProgressCallback {
                    override fun onProgress(
                        filesFinished: Long,
                        filesTotal: Long,
                        bytesWritten: Long,
                        bytesTotal: Long,
                        filesSkipped: Long,
                        bytesSkipped: Long
                    ) {
                        val progress = if (bytesTotal > 0) {
                            bytesWritten.toFloat() / bytesTotal
                        } else 0f

                        // 正确的瞬时速度计算
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = currentTime - lastTime
                        val speed = if (timeDiff > 0 && bytesWritten > lastBytes) {
                            ((bytesWritten - lastBytes) * 1000 / timeDiff).formatSpeed()
                        } else "0 B/s"

                        lastTime = currentTime
                        lastBytes = bytesWritten

                        Log.d("ResticRestore", "进度更新: $progress, 速度: $speed, 文件: $filesFinished/$filesTotal")
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
                }

                val targetPath = "${context.localBackupSaveDir()}/restore/"
                Log.d("ResticRestore", "恢复到用户备份目录: $targetPath")
                val backupBaseDir = context.readBackupDirectory() ?: context.localBackupSaveDir()
                val snapshotSubPath = "$backupBaseDir/apps/${backup.packageName}/user_${backup.userId}"
                val includePath = when (backup.dataType) {
                    DataType.PACKAGE_CONFIG -> "package_restore_config.json"
                    else -> "${backup.dataType.type}.tar.zst"
                }
                val fullTargetPath = "${targetPath}apps/${backup.packageName}/user_${backup.userId}/"
                Log.d("ResticRestore", "恢复 ${backup.dataType.type} 到目标: $targetPath")
                Log.d("ResticRestore", "快照子路径: $snapshotSubPath")
                Log.d("ResticRestore", "包含文件: $includePath")
                val success = resticRepo.restoreSnapshot(
                    repoPath = repoPath,
                    password = password,
                    snapshotId = backup.snapshotId,
                    targetPath = fullTargetPath,
                    includePath = includePath,
                    snapshotSubPath = snapshotSubPath,
                    progressCallback = progressCallback
                )

                // 将检查移到这里
                if (!success) {
                    Log.e("ResticRestore", "恢复失败: ${backup.dataType.type}, 快照ID: ${backup.snapshotId}")
                    _resticProgress.value = ResticProgressState()
                    return false
                }
                Log.d("ResticRestore", "恢复成功: ${backup.dataType.type}")
            }

            Log.d("ResticRestore", "所有数据类型恢复完成")
            true  // 成功时返回 true
        } catch (e: Exception) {
            Log.e("ResticRestore", "快照恢复异常: ${e.message}", e)
            _uiState.value = ResticRestoreUiState.Error(e.message ?: "Unknown error")
            false  // 失败时返回 false
        }
    }

    suspend fun readBackupDirectory(): String {
        Log.d("ResticRestore", "从 DataStore 读取备份目录配置")
        val backupDir = context.localBackupSaveDir()
        Log.d("ResticRestore", "读取到的备份目录: $backupDir")
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

    private fun Long.formatTime(): String {
        val minutes = this / 60
        val seconds = this % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}

// 修改 UI 状态
sealed interface ResticRestoreUiState {
    object Loading : ResticRestoreUiState
    data class Success(val groups: List<ResticBackupGroup>) : ResticRestoreUiState
    data class Error(val message: String) : ResticRestoreUiState
}