package com.xayah.feature.main.restore

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.feature.main.restore.ResticFileBackupGroup
import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.LocalContentColor
import com.xayah.feature.main.restore.RestoreScaffold
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.component.LabelLargeText
import com.xayah.core.ui.component.LabelMediumText
import com.xayah.core.ui.component.TextButton
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.DateUtil
import com.xayah.core.util.navigateSingle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CloudFileBackupGroupItem(
    group: ResticFileBackupGroup,
    onClick: () -> Unit,
    context: Context
) {
    Surface(onClick = onClick) {
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .padding(SizeTokens.Level16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(SizeTokens.Level32),
                tint = MaterialTheme.colorScheme.primary
            )

            Column(modifier = Modifier.weight(1f)) {
                TitleLargeText(
                    text = group.mediaName,
                    maxLines = 1
                )

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
                    color = ThemedColorSchemeKeyTokens.Outline.value,
                    maxLines = 1
                )

                Text(
                    text = "共计 ${group.snapshotCount} 个快照",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun CloudFilesRestorePage(
    navController: NavController,
    accountName: String,
    viewModel: CloudFilesRestoreViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(key1 = accountName) {
        Log.d("CloudFilesRestore", "LaunchedEffect 触发，账户名: $accountName")
        if (accountName.isNotEmpty()) {
            Log.d("CloudFilesRestore", "调用 viewModel.setCloudEntity")
            viewModel.setCloudEntity(accountName)
        }
    }

    RestoreScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState()),
        title = "云端文件恢复"
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SizeTokens.Level16)
        ) {
            val currentState = uiState
            when (currentState) {
                is CloudFilesRestoreUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is CloudFilesRestoreUiState.Success -> {
                    if (currentState.groups.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            TitleLargeText(text = "没有找到云端文件备份")
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)
                        ) {
                            items(
                                currentState.groups,
                                key = { item: ResticFileBackupGroup -> "${item.fullPath}-${item.timestamp}" }
                            ) { group: ResticFileBackupGroup ->
                                CloudFileBackupGroupItem(
                                    group = group,
                                    onClick = {
                                        try {
                                            Log.d("CloudFilesRestorePage", "开始处理点击事件")
                                            Log.d("CloudFilesRestorePage", "备份项被点击: ${group.mediaName}")

                                            // 1. JSON 序列化 group 对象
                                            val groupJson = Json.encodeToString(group)
                                            val encodedJson = URLEncoder.encode(groupJson, "UTF-8")
                                            Log.d("CloudFilesRestorePage", "JSON序列化成功: ${encodedJson.take(100)}...")

                                            // 2. URL 编码账户名称
                                            val cleanAccountName = accountName.replace("accountName=", "")
                                            val encodedAccountName = URLEncoder.encode(cleanAccountName, "UTF-8")
                                            Log.d("CloudFilesRestorePage", "账户名编码: $encodedAccountName")

                                            // 3. 构建导航路由
                                            val url = MainRoutes.CloudFilesBackupDetail.getRoute(
                                                encodedJson,
                                                encodedAccountName
                                            )
                                            Log.d("CloudFilesRestorePage", "构建路由: $url")

                                            // 4. 执行导航
                                            navController.navigateSingle(url)
                                            Log.d("CloudFilesRestorePage", "导航调用完成")
                                        } catch (e: Exception) {
                                            Log.e("CloudFilesRestorePage", "点击事件处理失败", e)
                                        }
                                    },
                                    context = LocalContext.current
                                )
                            }
                        }
                    }
                }

                is CloudFilesRestoreUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
                        ) {
                            TitleLargeText(text = "加载失败")
                            BodyMediumText(text = currentState.message)
                            Button(onClick = { viewModel.setCloudEntity(accountName) }) {
                                Text("重试")
                            }
                        }
                    }
                }
            }
        }
    }
}