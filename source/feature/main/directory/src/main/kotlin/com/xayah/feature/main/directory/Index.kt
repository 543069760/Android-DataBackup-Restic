package com.xayah.feature.main.directory

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.token.SizeTokens

@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageDirectory() {
    val viewModel = hiltViewModel<IndexViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val internalDirectoriesState by viewModel.internalDirectoriesState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    LaunchedEffect(Unit) {
        viewModel.emitIntentOnIO(IndexUiIntent.Update)
    }

    DirectoryScaffold(
        scrollBehavior = scrollBehavior,
        isLoading = uiState.updating,
        title = stringResource(id = R.string.backup_dir),
        actions = {}
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .paddingHorizontal(SizeTokens.Level16),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
        ) {
            item {
                Spacer(modifier = Modifier.size(SizeTokens.Level0))
            }

            items(items = internalDirectoriesState) { item ->
                val isUser0 = item.path.contains("/emulated/0") || item.path.contains("/user/0")

                // 修复点：通过 Box 应用 Modifier
                Box(modifier = Modifier.alpha(if (isUser0) 1f else 0.5f)) {
                    DirectoryCard(item = item) {
                        if (isUser0) {
                            viewModel.emitIntentOnIO(IndexUiIntent.Select(entity = item))
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.size(SizeTokens.Level0))
            }
        }
    }
}