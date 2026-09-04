package com.xayah.feature.main.restore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xayah.core.model.DataType
import android.content.Context
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.util.DateUtil
import com.xayah.feature.main.restore.RestoreScaffold
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.restore.ResticFilesRestoreUiState
import com.xayah.feature.main.restore.ResticFileBackupGroup
import com.xayah.feature.main.restore.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.net.URLEncoder

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResticFileBackupGroupItem(
    group: ResticFileBackupGroup,
    onClick: () -> Unit,
    context: Context
) {
    // 检查是否有 CONFIG 快照
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
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(SizeTokens.Level32),
                tint = MaterialTheme.colorScheme.primary
            )

            Column(modifier = Modifier.weight(1f)) {
                // 第1行: 文件名 + 胶囊标签
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TitleLargeText(
                        text = group.mediaName,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // 胶囊标签
                    if (!hasConfigSnapshot) {
                        Surface(
                            color = containerColor,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(0.5.dp, borderColor)
                        ) {
                            Text(
                                text = stringResource(R.string.restore_backup_incomplete),
                                color = contentColor,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

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
                    text = stringResource(R.string.restore_snapshot_count, group.snapshotCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun ResticFilesRestorePage(
    navController: NavController,
    viewModel: ResticFilesRestoreViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadBackedUpFiles()
    }

    RestoreScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState()),
        title = stringResource(R.string.restore_restic_file_restore_title)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SizeTokens.Level16)
        ) {
            when (val currentState = uiState) {
                is ResticFilesRestoreUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is ResticFilesRestoreUiState.Success -> {
                    // ✅ 关键修正：在 LazyColumn 外部获取 context
                    val context = LocalContext.current

                    if (currentState.groups.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            TitleLargeText(text = stringResource(R.string.restore_no_file_backup))
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)
                        ) {
                            itemsIndexed(
                                items = currentState.groups,
                                // ✅ 明确 Lambda 参数名，增强编译器推断稳定性
                                key = { index, group -> "${group.fullPath}-${group.timestamp}-$index" }
                            ) { index, group ->
                                ResticFileBackupGroupItem(
                                    group = group,
                                    context = context, // ✅ 使用上面获取好的 context 变量
                                    onClick = {
                                        val groupJson = Json.encodeToString(group)
                                        val encodedJson = URLEncoder.encode(groupJson, "UTF-8")
                                        val url = MainRoutes.ResticFilesBackupDetail.getRoute(encodedJson)
                                        navController.navigateSingle(url)
                                    }
                                )
                            }
                        }
                    }
                }

                is ResticFilesRestoreUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
                        ) {
                            TitleLargeText(text = stringResource(R.string.restore_load_failed))
                            BodyMediumText(text = currentState.message)
                            Button(onClick = { viewModel.loadBackedUpFiles() }) {
                                Text(stringResource(R.string.restore_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}