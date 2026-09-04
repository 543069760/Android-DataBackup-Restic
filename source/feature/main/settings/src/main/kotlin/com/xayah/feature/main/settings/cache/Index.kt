package com.xayah.feature.main.settings.cache

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.Card as ThemedCard
import com.xayah.core.ui.material3.CardDefaults as ThemedCardDefaults
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold

@androidx.compose.foundation.ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageCacheManagement() {
    val viewModel: CacheManagementViewModel = hiltViewModel()
    val cacheInfo by viewModel.cacheInfo.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // 进入页面时自动刷新
    LaunchedEffect(Unit) {
        viewModel.calculateCacheSize()
    }

    SettingsScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.cache_management)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // 恢复缓存信息
            CacheInfoCard(
                title = stringResource(id = R.string.restore_cache),
                size = cacheInfo.restoreCacheSize,
                onClear = { viewModel.clearRestoreCache() }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 恢复缓存说明卡片
            CacheDescCard(desc = stringResource(id = R.string.restore_cache_desc))

            Spacer(modifier = Modifier.height(24.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 手动统计按钮
                Button(
                    onClick = { viewModel.calculateCacheSize() },
                    modifier = Modifier.weight(1f),
                    enabled = !cacheInfo.isCalculating
                ) {
                    if (cacheInfo.isCalculating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(id = R.string.refresh))
                    }
                }

                // 清除恢复缓存按钮
                Button(
                    onClick = { viewModel.clearRestoreCache() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(id = R.string.clear_all))
                }
            }
        }
    }
}

@Composable
fun CacheInfoCard(
    title: String,
    size: Long,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = formatSize(size),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Clear"
                )
            }
        }
    }
}

@androidx.compose.foundation.ExperimentalFoundationApi
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun CacheDescCard(desc: String) {
    ThemedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = ThemedCardDefaults.cardColors(
            containerColor = ThemedColorSchemeKeyTokens.BluePrimaryContainer.value
        ),
    ) {
        BodyMediumText(
            modifier = Modifier.padding(SizeTokens.Level16),
            text = desc,
            color = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value
        )
    }
}

// 格式化文件大小
fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}