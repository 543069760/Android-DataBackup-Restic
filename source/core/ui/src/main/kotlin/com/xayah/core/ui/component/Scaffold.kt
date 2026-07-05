package com.xayah.core.ui.component

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition                        // <-- 新增
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.ui.R
import com.xayah.core.ui.material3.SnackbarHost
import com.xayah.core.ui.material3.SnackbarHostState
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.capitalizeString
import kotlinx.coroutines.delay

@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun MainIndexSubScaffold(
    scrollBehavior: TopAppBarScrollBehavior,
    snackbarHostState: SnackbarHostState? = null,
    title: String,
    updateAvailable: Boolean,
    onVersionChipClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},               // <-- 新增
    content: @Composable (BoxScope.() -> Unit)
) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(SizeTokens.Level4)
                    ) {
                        // 第1行: APP名称
                        Text(text = title)

                        // 第2行: 版本信息和更新徽章
                        BadgedBox(
                            badge = {
                                if (updateAvailable)
                                    Badge(modifier = Modifier.size(SizeTokens.Level6))
                            }
                        ) {
                            RoundChip(onClick = if (updateAvailable) onVersionChipClick else null) {
                                var version by remember {
                                    mutableStateOf("${BuildConfigUtil.VERSION_NAME} ${BuildConfigUtil.FLAVOR_feature.capitalizeString()}")
                                }
                                val updateAvailableText = stringResource(id = R.string.update_available)   // 新增：Composable 作用域内预取
                                LaunchedEffect(updateAvailable) {
                                    while (updateAvailable) {
                                        delay(3000)
                                        val tmp = version
                                        version = updateAvailableText   // ← 原为 context.getString(R.string.update_available)
                                        delay(3000)
                                        version = tmp
                                    }
                                }
                                AnimatedTextContainer(targetState = version) { text ->
                                    LabelLargeText(modifier = Modifier.paddingHorizontal(SizeTokens.Level12), text = text, maxLines = 1)
                                }
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                actions = actions,
            )
        },
        snackbarHost = {
            if (snackbarHostState != null) {
                SnackbarHost(
                    modifier = Modifier.paddingBottom(SizeTokens.Level24 + SizeTokens.Level4),
                    hostState = snackbarHostState,
                )
            }
        },
        floatingActionButton = floatingActionButton,                 // <-- 新增
        floatingActionButtonPosition = FabPosition.Center,           // <-- 新增
    ) { innerPadding ->
        Column {
            InnerTopSpacer(innerPadding = innerPadding)

            Box(modifier = Modifier.weight(1f), content = content)
        }
    }
}