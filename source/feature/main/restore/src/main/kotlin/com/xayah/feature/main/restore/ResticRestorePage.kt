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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.xayah.core.model.DataType
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value  // 添加这个导入
import com.xayah.feature.main.restore.ResticBackupGroup
import android.content.Context
import androidx.compose.runtime.remember
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.DateUtil
import com.xayah.core.util.navigateSingle
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun ResticRestorePage(
    navController: NavController,
    viewModel: ResticRestoreViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadBackedUpApps()      // 首次/正常进入：守卫生效，秒开
    }

// 删除返回时的强制刷新信号
    val needsRefresh = navController.currentBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow("restic_needs_refresh", false)
        ?.collectAsStateWithLifecycle()

    LaunchedEffect(needsRefresh?.value) {
        if (needsRefresh?.value == true) {
            viewModel.forceReload()       // 绕过守卫，重列 + 重建缓存
            navController.currentBackStackEntry
                ?.savedStateHandle
                ?.set("restic_needs_refresh", false)  // 复位，避免重复触发
        }
    }

    RestoreScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState()),
        title = "从 Restic 备份恢复"
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SizeTokens.Level16)
        ) {
            val currentState = uiState
            when (currentState) {
                is ResticRestoreUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                // 修改 Success 状态的处理
                is ResticRestoreUiState.Success -> {
                    if (currentState.groups.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            TitleLargeText(text = "没有找到备份")
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)
                        ) {
                            // 修正后的 items 调用
                            items(
                                currentState.groups,
                                key = { item: ResticBackupGroup -> "${item.packageName}-${item.timestamp}" }
                            ) { group: ResticBackupGroup ->
                                ResticBackupGroupItem(
                                    group = group,
                                    onClick = {
                                        // 1. JSON 序列化
                                        val groupJson = Json.encodeToString(group)
                                        Log.d("ResticRestorePage", "Navigating with groupJson: $groupJson")

                                        // 2. URL 编码，并构造完整的路由
                                        // 使用 getRoute 函数简化构造
                                        val encodedJson = URLEncoder.encode(groupJson, "UTF-8")
                                        val url = MainRoutes.ResticBackupDetail.getRoute(groupJsonEncoded = encodedJson)

                                        Log.d("ResticRestorePage", "Full URL: $url")

                                        // 3. 执行导航
                                        navController.navigateSingle(url)
                                    },
                                    context = LocalContext.current,
                                    accountId = "local"
                                )
                            }
                        }
                    }
                }

                is ResticRestoreUiState.Error -> {
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
                            Button(onClick = { viewModel.loadBackedUpApps() }) {
                                Text("重试")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResticBackupGroupItem(
    group: ResticBackupGroup,
    onClick: () -> Unit,
    context: Context,
    accountId: String? = null
) {
    val hasConfigSnapshot = group.backups.any { it.dataType == DataType.PACKAGE_CONFIG }

    // 定义颜色
    val containerColor = Color(0xFFFF4D4F).copy(alpha = 0.12f)
    val contentColor = Color(0xFFD32F2F)
    val borderColor = Color(0xFFFFCCC7).copy(alpha = 0.5f)

    Surface(onClick = onClick) {
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .padding(SizeTokens.Level16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
        ) {
            PackageIconImage(packageName = group.packageName, size = SizeTokens.Level32, accountId = accountId)

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TitleLargeText(
                        text = group.appLabel,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (!hasConfigSnapshot) {
                        Surface(
                            color = containerColor,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(0.5.dp, borderColor) // 添加边框
                        ) {
                            Text(
                                text = "备份不完整",
                                color = contentColor,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                BodyMediumText(
                    text = group.packageName,
                    color = ThemedColorSchemeKeyTokens.Outline.value,
                    maxLines = 1
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