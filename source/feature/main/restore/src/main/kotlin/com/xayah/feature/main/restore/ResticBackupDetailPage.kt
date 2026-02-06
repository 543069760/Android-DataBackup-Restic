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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.model.OpType
import com.xayah.core.util.navigateSingle
import com.xayah.core.model.Target
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.ui.component.ProgressButton
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.DateUtil
import com.xayah.core.model.DataType
import com.xayah.feature.main.restore.ResticBackupGroup
import kotlinx.coroutines.launch
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun ResticBackupDetailPage(
    navController: NavController,
    group: ResticBackupGroup,
    viewModel: ResticRestoreViewModel = hiltViewModel()
) {
    val resticProgress by viewModel.resticProgress.collectAsStateWithLifecycle()
    val isRestoring = resticProgress.totalDataTypes > 0 &&
            resticProgress.currentDataTypeIndex < resticProgress.totalDataTypes

    val isCompleted = resticProgress.isCompleted

// 按钮禁用条件：正在恢复 或 已完成
    val buttonEnabled = !isRestoring && !isCompleted

    fun getCurrentDataTypeName(group: ResticBackupGroup, index: Int): String {
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
        return if (index < sortedBackups.size) {
            sortedBackups[index].dataType.type.uppercase()
        } else {
            ""
        }
    }

    // 计算进度信息
    val currentProgress = if (resticProgress.bytesTotal > 0) {
        resticProgress.bytesWritten.toFloat() / resticProgress.bytesTotal
    } else 0f

    val currentIndex = resticProgress.currentDataTypeIndex
    val totalCount = resticProgress.totalDataTypes
    val speed = resticProgress.speed
    val progressSize = "${resticProgress.bytesWritten.formatSize()}/${resticProgress.bytesTotal.formatSize()}"
    val coroutineScope = rememberCoroutineScope()
    RestoreScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            rememberTopAppBarState()
        ),
        title = "备份详情",
        actions = {
            ProgressButton(
                modifier = Modifier.fillMaxWidth(),
                progress = currentProgress,
                currentIndex = currentIndex,
                totalCount = totalCount,
                speed = speed,
                progressSize = progressSize,
                enabled = buttonEnabled,
                text = when {  // 将文本逻辑移到这里
                    isRestoring -> {
                        val currentDataType = getCurrentDataTypeName(group, currentIndex)
                        "正在恢复${currentDataType}快照"
                    }
                    isCompleted -> {
                        "快照恢复已完成"
                    }
                    else -> {
                        "恢复快照备份"
                    }
                },
                onClick = {
                    if (!isRestoring && !isCompleted) {
                        Log.d("ResticRestore", "用户点击恢复按钮，开始恢复流程")
                        coroutineScope.launch {
                            try {
                                Log.d("ResticRestore", "调用 ViewModel.restoreFromResticSnapshots")
                                val success = viewModel.restoreFromResticSnapshots(group)
                                Log.d("ResticRestore", "恢复结果: $success")

                                if (success) {
                                    Log.d("ResticRestore", "恢复成功，准备读取备份目录")
                                    val backupDir = "${viewModel.readBackupDirectory()}/restore/"
                                    Log.d("ResticRestore", "导航到恢复页面，备份目录: $backupDir")
                                    viewModel.refreshLocalDatabase(backupDir)
                                    viewModel.calculateSizesForActivatedApps()
                                    // 修改为传递备份目录参数
                                    val route = MainRoutes.PackagesRestoreProcessingGraph.getRoute(
                                        cloudName = URLEncoder.encode("", "UTF-8"),
                                        backupDir = URLEncoder.encode(backupDir, "UTF-8"),
                                        packageName = group.packageName
                                    )
                                    Log.d("Navigation", "构建路由: $route")
                                    navController.navigateSingle(route)
                                    Log.d("Navigation", "导航完成: ResticBackupDetailPage → PackagesRestoreProcessingGraph")
                                } else {
                                    Log.e("ResticRestore", "恢复失败")
                                }
                            } catch (e: Exception) {
                                Log.e("ResticRestore", "恢复流程异常: ${e.message}", e)
                            }
                        }
                    } else {
                        Log.d("ResticRestore", "恢复正在进行中，忽略点击")
                    }
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SizeTokens.Level16)
                .verticalScroll(rememberScrollState())
        ) {
            // APP图标和基本信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
            ) {
                PackageIconImage(packageName = group.packageName, size = SizeTokens.Level64)

                Column {
                    TitleLargeText(text = group.appLabel)
                    BodyMediumText(
                        text = group.packageName,
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
                group.backups.forEach { backup ->
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