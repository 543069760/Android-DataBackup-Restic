package com.xayah.feature.main.restore

import android.util.Log
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.model.OpType
import com.xayah.core.util.navigateSingle
import com.xayah.core.model.Target
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.confirm
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.ProgressButton
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.DateUtil
import com.xayah.core.model.DataType
import com.xayah.core.datastore.readBackupDirectory
import com.xayah.core.model.ResticProgressState
import kotlinx.coroutines.launch
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun ResticFilesBackupDetailPage(
    navController: NavController,
    group: ResticFileBackupGroup,
    viewModel: ResticFilesRestoreViewModel = hiltViewModel()
) {
    val resticProgress by viewModel.resticProgress.collectAsStateWithLifecycle()
    val dialogState = LocalSlotScope.current!!.dialogSlot  // 新增
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // 新增删除状态变量
    val isDeleting = resticProgress.isDeleting
    val isRestoring = resticProgress.totalDataTypes > 0 &&
            resticProgress.currentDataTypeIndex < resticProgress.totalDataTypes &&
            !isDeleting  // 排除删除状态

    val isCompleted = resticProgress.isCompleted && !isDeleting
    val deleteButtonEnabled = !isRestoring && !isCompleted && !isDeleting
    val restoreButtonEnabled = !isRestoring && !isCompleted && !isDeleting  // 修改原有的 buttonEnabled

    // 新增删除进度相关变量
    val totalSnapshots = group.backups.size
    val totalSteps = totalSnapshots + 1  // 快照数量 + 1 (prune)
    val currentStep = if (isDeleting) {
        resticProgress.currentDataTypeIndex + 1
    } else {
        0
    }

    // 保留原有的 getCurrentDataTypeName 函数
    fun getCurrentDataTypeName(group: ResticFileBackupGroup, index: Int): String {
        val sortedBackups = group.backups.sortedBy { backup ->
            when (backup.dataType) {
                DataType.PACKAGE_MEDIA -> 0
                DataType.PACKAGE_CONFIG -> 1
                else -> 2
            }
        }
        return if (index < sortedBackups.size) {
            sortedBackups[index].dataType.type.uppercase()
        } else {
            ""
        }
    }

    // 保留原有的进度计算变量
    val currentProgress = if (resticProgress.bytesTotal > 0) {
        resticProgress.bytesWritten.toFloat() / resticProgress.bytesTotal
    } else 0f

    val currentIndex = resticProgress.currentDataTypeIndex
    val totalCount = resticProgress.totalDataTypes
    val speed = resticProgress.speed
    val progressSize = "${resticProgress.bytesWritten.formatSize()} / ${resticProgress.bytesTotal.formatSize()}"

    RestoreScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            rememberTopAppBarState()
        ),
        title = "文件备份详情",
        actions = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)
            ) {
                // 删除按钮
                ProgressButton(
                    modifier = Modifier.fillMaxWidth(),
                    progress = 0f,
                    currentIndex = if (isDeleting) currentStep else 0,
                    totalCount = if (isDeleting) totalSteps else 0,
                    speed = "",
                    progressSize = "",
                    enabled = deleteButtonEnabled,
                    text = if (isDeleting) {
                        if (currentStep <= totalSnapshots) {
                            val currentDataType = getCurrentDataTypeName(group, currentIndex)
                            "正在删除${currentDataType}快照 ($currentStep/$totalSteps)"
                        } else {
                            "正在清理存储空间 ($totalSteps/$totalSteps)"
                        }
                    } else {
                        "删除本地文件快照"
                    },
                    onClick = {
                        if (!isDeleting) {
                            coroutineScope.launch {
                                if (dialogState.confirm(
                                        title = "提示",
                                        text = "确认删除该文件的所有本地快照?\n共计 ${group.backups.size} 个快照"
                                    )) {
                                    val success = viewModel.deleteLocalFileSnapshots(group)
                                    if (success) {
                                        navController.popBackStack()
                                    }
                                }
                            }
                        }
                    }
                )

                // 恢复按钮 (保持原有逻辑,但修改 enabled 条件)
                ProgressButton(
                    modifier = Modifier.fillMaxWidth(),
                    progress = currentProgress,
                    currentIndex = currentIndex,
                    totalCount = totalCount,
                    speed = speed,
                    progressSize = progressSize,
                    enabled = restoreButtonEnabled,  // 修改这里
                    text = when {
                        isRestoring -> {
                            val currentDataType = getCurrentDataTypeName(group, currentIndex)
                            "正在恢复${currentDataType}快照"
                        }
                        isCompleted -> {
                            "文件恢复已完成"
                        }
                        else -> {
                            "恢复文件备份"
                        }
                    },
                    onClick = {
                        if (!isRestoring && !isCompleted && !isDeleting) {  // 添加 !isDeleting 检查
                            coroutineScope.launch {
                                try {
                                    Log.d("ResticFilesRestore", "用户点击恢复按钮，开始恢复流程")
                                    val success = viewModel.restoreFromResticSnapshots(group)
                                    Log.d("ResticFilesRestore", "恢复结果: $success")

                                    if (success) {
                                        Log.d("ResticFilesRestore", "恢复成功，准备读取备份目录")
                                        val backupDir = "${viewModel.readBackupDirectory()}/restore/"
                                        Log.d("ResticFilesRestore", "导航到恢复页面，备份目录: $backupDir")
                                        viewModel.refreshLocalDatabase(backupDir)
                                        viewModel.calculateSizesForActivatedMedia()

                                        val route = MainRoutes.MediumRestoreProcessingGraph.getRoute(
                                            cloudName = URLEncoder.encode("", "UTF-8"),
                                            backupDir = URLEncoder.encode(backupDir, "UTF-8"),
                                            mediaName = URLEncoder.encode(group.mediaName, "UTF-8")
                                        )
                                        Log.d("Navigation", "构建路由: $route")
                                        navController.navigateSingle(route)
                                        Log.d("Navigation", "导航完成: ResticFilesBackupDetailPage → MediumRestoreProcessingGraph")
                                    } else {
                                        Log.e("ResticFilesRestore", "恢复失败")
                                    }
                                } catch (e: Exception) {
                                    Log.e("ResticFilesRestore", "恢复流程异常: ${e.message}", e)
                                }
                            }
                        } else {
                            Log.d("ResticFilesRestore", "恢复正在进行中，忽略点击")
                        }
                    }
                )
            }
        }
    ) {
    Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SizeTokens.Level16)
                .verticalScroll(rememberScrollState())
        ) {
            // 文件图标和基本信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(SizeTokens.Level64)
                )

                Column {
                    TitleLargeText(text = group.mediaLabel)
                    BodyMediumText(
                        text = group.fullPath,
                        color = ThemedColorSchemeKeyTokens.Outline.value,
                        maxLines = 2
                    )
                    BodyMediumText(
                        text = DateUtil.formatTimestamp(
                            group.timestamp,
                            DateUtil.PATTERN_YMD_HMS
                        ),
                        color = ThemedColorSchemeKeyTokens.Outline.value
                    )
                }
            }

            Spacer(modifier = Modifier.height(SizeTokens.Level24))

            // 备份类型详情
            Title(title = "备份类型详情") {
                val sortedBackups = group.backups.sortedBy { backup ->
                    when (backup.dataType) {
                        DataType.PACKAGE_MEDIA -> 0
                        DataType.PACKAGE_CONFIG -> 1
                        else -> 2
                    }
                }
                sortedBackups.forEach { backup ->
                    Column(
                        modifier = Modifier.padding(vertical = SizeTokens.Level8)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = when (backup.dataType) {
                                        DataType.PACKAGE_MEDIA -> "媒体文件"
                                        DataType.PACKAGE_CONFIG -> "配置文件"
                                        else -> backup.dataType.type.uppercase()
                                    },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "快照ID: ${backup.snapshotId}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "快照大小: ${backup.totalBytesProcessed.formatSize()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (sortedBackups.last() != backup) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = SizeTokens.Level4)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Long.formatSize(): String {
    return when {
        this < 1024 -> "$this B"
        this < 1024 * 1024 -> String.format("%.2f KiB", this / 1024.0)
        this < 1024 * 1024 * 1024 -> String.format("%.2f MiB", this / (1024.0 * 1024))
        else -> String.format("%.2f GiB", this / (1024.0 * 1024 * 1024))
    }
}