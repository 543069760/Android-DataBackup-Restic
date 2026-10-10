package com.xayah.feature.main.processing.packages.restore

import android.util.Log
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xayah.core.model.OperationState
import com.xayah.core.ui.component.AnimatedNavHost
import com.xayah.core.ui.route.MainRoutes
import com.xayah.feature.main.processing.PageProcessing
import com.xayah.feature.main.processing.R
import com.xayah.core.model.OpType
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
@ExperimentalAnimationApi
@ExperimentalLayoutApi
@ExperimentalFoundationApi
@ExperimentalMaterial3Api
@Composable
fun PackagesRestoreProcessingGraph(packageNameFilter: String = "", isOtg: Boolean = false) {
    val localNavController = rememberNavController()
    val viewModel = hiltViewModel<RestoreViewModelImpl>()

    // 只在 graph 真正创建（首次进入组合）时打印一次；重组不会重复触发。
    // 若同一次「下一步」出现两条 "Graph created"，说明 graph 被二重生成（route 重复 push）。
    LaunchedEffect(Unit) {
        Log.d("RestoreNavTrace", "Graph created, ts=${System.currentTimeMillis()}")
    }

    AnimatedNavHost(
        navController = localNavController,
        startDestination = MainRoutes.PackagesRestoreProcessingSetup.route,
    ) {
        composable(MainRoutes.PackagesRestoreProcessing.route) {
            PageProcessing(
                topBarTitleId = { state ->
                    when (state) {
                        OperationState.PROCESSING -> R.string.processing
                        OperationState.DONE -> R.string.restore_completed
                        else -> R.string.restore
                    }
                },
                finishedTitleId = R.string.restore_completed,
                finishedSubtitleId = R.string.args_apps_restored,
                finishedWithErrorsSubtitleId = R.string.args_apps_restored_and_failed,
                viewModel = viewModel,
                opType = OpType.RESTORE
            )
        }
        composable(MainRoutes.PackagesRestoreProcessingSetup.route) {
            PagePackagesRestoreProcessingSetup(
                localNavController = localNavController,
                viewModel = viewModel,
                packageNameFilter = packageNameFilter
            )
        }
    }
}