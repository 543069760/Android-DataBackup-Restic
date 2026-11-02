package com.xayah.feature.main.settings.benchmark

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xayah.feature.benchmark.CompressionBenchmarkSection
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold

/**
 * 压缩档位基准页（设置入口）：这里只负责页面骨架。
 *
 * 三态内容、表格与推荐卡片全部来自共享模块的 [CompressionBenchmarkSection]——
 * 首次引导页复用同一份 UI，避免两处各写一套。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun PageCompressionBenchmark() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    SettingsScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.compression_benchmark),
    ) { _ ->
        // 注意：SettingsScaffold 内部已用 InnerTopSpacer(innerPadding) 让出了标题栏高度，
        // 这里不能再 padding(innerPadding)，否则大标题下方会多出一整屏高度的空白。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            CompressionBenchmarkSection()
        }
    }
}
