package com.xayah.feature.main.restore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.Icons.Default
import android.content.Context
import androidx.compose.material.icons.filled.Folder
import com.xayah.feature.main.restore.ResticFilesRestoreViewModel
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.util.DateUtil
import com.xayah.feature.main.restore.RestoreScaffold
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.token.SizeTokens
import com.xayah.feature.main.restore.ResticFilesRestoreUiState
import com.xayah.feature.main.restore.ResticFileBackupGroup

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResticFileBackupGroupItem(
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
                    text = group.fullPath,  // 显示完整路径
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
        title = "从 Restic 备份恢复文件"
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
                    if (currentState.groups.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            TitleLargeText(text = "没有找到文件备份")
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)
                        ) {
                            items(
                                currentState.groups,
                                key = { item: ResticFileBackupGroup -> "${item.fullPath}-${item.timestamp}" }
                            ) { group: ResticFileBackupGroup ->
                                ResticFileBackupGroupItem(
                                    group = group,
                                    onClick = {
                                        // 导航到文件恢复详情页
                                    },
                                    context = LocalContext.current
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
                            TitleLargeText(text = "加载失败")
                            BodyMediumText(text = currentState.message)
                            Button(onClick = { viewModel.loadBackedUpFiles() }) {
                                Text("重试")
                            }
                        }
                    }
                }
            }
        }
    }
}