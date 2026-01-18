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
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.datastore.readBackupDirectory
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
fun CloudBackupDetailPage(
    navController: NavController,
    group: ResticBackupGroup,
    accountName: String,
    viewModel: CloudRestoreViewModel = hiltViewModel()
) {
    val resticProgress by viewModel.resticProgress.collectAsStateWithLifecycle()
    val isRestoring = resticProgress.totalDataTypes > 0 &&
            resticProgress.currentDataTypeIndex < resticProgress.totalDataTypes
    val isCompleted = resticProgress.isCompleted
    val buttonEnabled = !isRestoring && !isCompleted
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // 计算进度信息
    val currentProgress = if (resticProgress.bytesTotal > 0) {
        resticProgress.bytesWritten.toFloat() / resticProgress.bytesTotal
    } else 0f

    val currentIndex = resticProgress.currentDataTypeIndex
    val totalCount = resticProgress.totalDataTypes
    val speed = resticProgress.speed
    val progressSize = "${resticProgress.bytesWritten.formatSize()} / ${resticProgress.bytesTotal.formatSize()}"

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

    RestoreScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState()),
        title = "云端备份详情",
        actions = {
            ProgressButton(
                modifier = Modifier.fillMaxWidth(),
                progress = currentProgress,
                currentIndex = currentIndex,
                totalCount = totalCount,
                speed = speed,
                progressSize = progressSize,
                enabled = buttonEnabled,
                text = when {
                    isRestoring -> {
                        val currentDataType = getCurrentDataTypeName(group, currentIndex)
                        "正在恢复${currentDataType}快照"
                    }
                    isCompleted -> "云端恢复已完成"
                    else -> "恢复云端快照"
                },
                onClick = {
                    if (!isRestoring && !isCompleted) {
                        coroutineScope.launch {
                            try {
                                Log.d("CloudRestore", "用户点击恢复按钮，开始云端恢复流程")
                                val success = viewModel.restoreFromCloudSnapshots(group)
                                Log.d("CloudRestore", "云端恢复结果: $success")

                                if (success) {
                                    Log.d("CloudRestore", "云端恢复成功，准备读取备份目录")
                                    val backupDir = "${context.readBackupDirectory()}/restore/"
                                    Log.d("CloudRestore", "导航到恢复页面，备份目录: $backupDir")
                                    viewModel.refreshLocalDatabase(backupDir)

                                    val route = MainRoutes.PackagesRestoreProcessingGraph.getRoute(
                                        cloudName = URLEncoder.encode(accountName, "UTF-8"),
                                        backupDir = URLEncoder.encode(backupDir, "UTF-8"),
                                        packageName = group.packageName
                                    )
                                    Log.d("Navigation", "构建路由: $route")
                                    navController.navigateSingle(route)
                                    Log.d("Navigation", "导航完成: CloudBackupDetailPage → PackagesRestoreProcessingGraph")
                                } else {
                                    Log.e("CloudRestore", "云端恢复失败")
                                }
                            } catch (e: Exception) {
                                Log.e("CloudRestore", "云端恢复流程异常: ${e.message}", e)
                            }
                        }
                    } else {
                        Log.d("CloudRestore", "云端恢复正在进行中，忽略点击")
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
        this < 1024 * 1024 -> "${this / 1024} KiB"
        this < 1024 * 1024 * 1024 -> "${this / (1024 * 1024)} MiB"
        else -> "${this / (1024 * 1024 * 1024)} GiB"
    }
}