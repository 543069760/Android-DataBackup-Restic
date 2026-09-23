package com.xayah.feature.main.processing.medium.backup

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
fun MediumBackupProcessingGraph(isOtg: Boolean = false) {
    val localNavController = rememberNavController()
    val viewModel = hiltViewModel<BackupViewModelImpl>()

    // 把任务级 isOtg 写入 ViewModel，供前置检查/服务读取侧按 OTG 键分流
    LaunchedEffect(Unit) {
        viewModel.mIsOtgTask = isOtg
    }

    AnimatedNavHost(
        navController = localNavController,
        startDestination = MainRoutes.MediumBackupProcessingSetup.route,
    ) {
        composable(MainRoutes.MediumBackupProcessing.route) {
            PageProcessing(
                topBarTitleId = { state ->
                    when (state) {
                        OperationState.PROCESSING -> R.string.processing
                        OperationState.DONE -> R.string.backup_completed
                        else -> R.string.backup
                    }
                },
                finishedTitleId = R.string.backup_completed,
                finishedSubtitleId = R.string.args_files_backed_up,
                finishedWithErrorsSubtitleId = R.string.args_files_backed_up_and_failed,
                viewModel = viewModel,
                opType = OpType.BACKUP
            )
        }
        composable(MainRoutes.MediumBackupProcessingSetup.route) {
            PageMediumBackupProcessingSetup(localNavController = localNavController, viewModel = viewModel)
        }
    }
}