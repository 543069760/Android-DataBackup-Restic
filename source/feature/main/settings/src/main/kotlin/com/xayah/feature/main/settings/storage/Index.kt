package com.xayah.feature.main.settings.storage

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold
import com.xayah.feature.main.settings.cache.CacheInfoCard
import com.xayah.feature.main.settings.cache.CacheManagementViewModel
import com.xayah.feature.main.settings.restic.ResticViewModel

@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageStorageStats() {
    val navController = LocalNavController.current!!
    val resticViewModel = hiltViewModel<ResticViewModel>()
    val cacheViewModel = hiltViewModel<CacheManagementViewModel>()

    val repoPath by resticViewModel.repoPathState.collectAsStateWithLifecycle()
    val resticInitialized by resticViewModel.resticInitializedState
        .collectAsStateWithLifecycle(initialValue = false)
    val cacheInfo by cacheViewModel.cacheInfo.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    LaunchedEffect(Unit) {
        resticViewModel.checkResticStatus()
        cacheViewModel.calculateCacheSize()
    }

    SettingsScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.storage_stats_title)
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // --- 1. Restic 备份仓库 ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(id = R.string.restic_repo_path_label),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (resticInitialized && !repoPath.isNullOrEmpty())
                            repoPath!!
                        else
                            stringResource(id = R.string.restic_not_initialized),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            // 进入“重新初始化”模式：仅切换 UI，不清空已保存路径/密码
                            resticViewModel.enterReinitializeMode()
                            navController.navigateSingle(MainRoutes.ResticInitialization.route)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(id = R.string.storage_reselect_and_reinit))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- 2. 缓存统计（弱化路径，只提示可清理）---
            CacheInfoCard(
                title = stringResource(id = R.string.storage_cache_title),
                size = cacheInfo.restoreCacheSize,
                onClear = { cacheViewModel.clearRestoreCache() }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.storage_cache_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}