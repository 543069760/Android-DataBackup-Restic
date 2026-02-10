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
import androidx.compose.runtime.LaunchedEffect
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.model.OpType
import com.xayah.core.util.navigateSingle
import com.xayah.core.util.localBackupSaveDir
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
import com.xayah.core.model.ResticProgressState
import kotlinx.coroutines.launch
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun CloudFilesBackupDetailPage(
    navController: NavController,
    group: ResticFileBackupGroup,
    accountName: String,
    viewModel: CloudFilesRestoreViewModel = hiltViewModel()
) {
    val resticProgress by viewModel.resticProgress.collectAsStateWithLifecycle()
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(accountName) {
        viewModel.setCloudEntity(accountName)
    }

    // 状态变量
    val isDeleting = resticProgress.isDeleting
    val isRestoring = resticProgress.totalDataTypes > 0 &&
            resticProgress.currentDataTypeIndex < resticProgress.totalDataTypes &&
            !isDeleting
    val isCompleted = resticProgress.isCompleted && !isDeleting
    val deleteButtonEnabled = !isRestoring && !isCompleted && !isDeleting
    val restoreButtonEnabled = !isRestoring && !isCompleted && !isDeleting

    val totalSnapshots = group.backups.size
    val totalSteps = totalSnapshots + 1
    val currentStep = if (isDeleting) {
        resticProgress.currentDataTypeIndex + 1
    } else {
        0
    }

    // 进度信息
    val currentProgress = if (resticProgress.bytesTotal > 0) {
        resticProgress.bytesWritten.toFloat() / resticProgress.bytesTotal
    } else 0f

    val currentIndex = resticProgress.currentDataTypeIndex
    val totalCount = resticProgress.totalDataTypes
    val speed = resticProgress.speed
    val progressSize = "${resticProgress.bytesWritten.formatSize()} / ${resticProgress.bytesTotal.formatSize()}"

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

    RestoreScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState()),
        title = "云端文件备份详情",
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
                        "删除云端文件快照"
                    },
                    onClick = {
                        if (!isDeleting) {
                            coroutineScope.launch {
                                if (dialogState.confirm(
                                        title = "提示",
                                        text = "确认删除该文件的所有云端快照?\n共计 ${group.backups.size} 个快照"
                                    )) {
                                    val success = viewModel.deleteCloudFileSnapshots(group)
                                    if (success) {
                                        navController.popBackStack()
                                    }
                                }
                            }
                        }
                    }
                )

                // 恢复按钮
                ProgressButton(
                    modifier = Modifier.fillMaxWidth(),
                    progress = currentProgress,
                    currentIndex = currentIndex,
                    totalCount = totalCount,
                    speed = speed,
                    progressSize = progressSize,
                    enabled = restoreButtonEnabled,
                    text = when {
                        isRestoring -> {
                            val currentDataType = getCurrentDataTypeName(group, currentIndex)
                            "正在恢复${currentDataType}快照"
                        }
                        isCompleted -> "云端文件恢复已完成"
                        else -> "恢复云端文件快照"
                    },
                    onClick = {
                        if (!isRestoring && !isCompleted && !isDeleting) {
                            coroutineScope.launch {
                                try {
                                    Log.d("CloudFilesRestore", "用户点击恢复按钮，开始云端文件恢复流程")
                                    val success = viewModel.restoreFromCloudFileSnapshots(group)
                                    Log.d("CloudFilesRestore", "云端文件恢复结果: $success")

                                    if (success) {
                                        Log.d("CloudFilesRestore", "云端文件恢复成功，准备读取备份目录")
                                        val backupDir = "${context.localBackupSaveDir()}/restore/"
                                        Log.d("CloudFilesRestore", "导航到文件恢复页面，备份目录: $backupDir")
                                        viewModel.refreshLocalDatabase(backupDir)

                                        viewModel.calculateSizesForActivatedMedia()
                                        val route = MainRoutes.MediumRestoreProcessingGraph.getRoute(
                                            cloudName = URLEncoder.encode("", "UTF-8"),
                                            backupDir = URLEncoder.encode(backupDir, "UTF-8"),
                                            mediaName = URLEncoder.encode(group.mediaName, "UTF-8")
                                        )
                                        Log.d("Navigation", "构建路由: $route")
                                        navController.navigateSingle(route)
                                        Log.d("Navigation", "导航完成: CloudFilesBackupDetailPage → MediumRestoreProcessingGraph")
                                    } else {
                                        Log.e("CloudFilesRestore", "云端文件恢复失败")
                                    }
                                } catch (e: Exception) {
                                    Log.e("CloudFilesRestore", "云端文件恢复流程异常: ${e.message}", e)
                                }
                            }
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
                        text = "云端账户: $accountName",
                        color = ThemedColorSchemeKeyTokens.Outline.value
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
                                    text = backup.dataType.type.uppercase(),
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

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = SizeTokens.Level4)
                        )
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