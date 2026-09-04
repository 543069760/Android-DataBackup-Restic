package com.xayah.feature.main.restore

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.model.StorageMode
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.PackageIcons
import com.xayah.core.ui.component.Selectable
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.paddingBottom
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.component.select
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.ui.util.icon
import com.xayah.core.util.navigateSingle

@SuppressLint("StringFormatInvalid")
@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageRestore() {
    val navController = LocalNavController.current!!
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val viewModel = hiltViewModel<IndexViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lastRestoreTime by viewModel.lastRestoreTimeState.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()

    LaunchedEffect(null) {
        viewModel.launchOnIO {
            viewModel.emitIntent(IndexUiIntent.UpdateApps)
            viewModel.emitIntent(IndexUiIntent.UpdateFiles)
        }
    }

    RestoreScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.restore),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            OverviewLastRestoreCard(
                modifier = Modifier.padding(SizeTokens.Level16),
                lastRestoreTime = lastRestoreTime
            )

            var enabled by remember { mutableStateOf(true) }
            val localText = stringResource(id = R.string.local)
            val cloudText = stringResource(id = R.string.cloud)
            val storageOptions = remember { listOf(localText, cloudText) }
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .paddingHorizontal(SizeTokens.Level16)
                    .paddingBottom(SizeTokens.Level16),
            ) {
                storageOptions.forEachIndexed { index, label ->
                    SegmentedButton(
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = storageOptions.size),
                        onClick = {
                            viewModel.launchOnIO {
                                enabled = false
                                viewModel.emitState(state = uiState.copy(storageIndex = index, storageType = if (index == 0) StorageMode.Local else StorageMode.Cloud))
                                viewModel.emitIntent(IndexUiIntent.UpdateApps)
                                viewModel.emitIntent(IndexUiIntent.UpdateFiles)
                                enabled = true
                            }
                        },
                        selected = index == uiState.storageIndex
                    ) {
                        Text(label)
                    }
                }
            }

            AnimatedVisibility(uiState.storageIndex == 1) {
                if (accounts.isEmpty()) {
                    Clickable(
                        title = stringResource(id = R.string.account),
                        value = stringResource(id = R.string.no_available_account),
                        leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_cancel_circle),
                        trailingIcon = Icons.Rounded.KeyboardArrowRight,
                    ) {
                        navController.navigateSingle(MainRoutes.Cloud.route)
                    }
                } else {
                    val dialogState = LocalSlotScope.current!!.dialogSlot
                    val accountText = stringResource(id = R.string.account)
                    var currentIndex by remember { mutableIntStateOf(if (uiState.cloudEntity == null) 0 else accounts.indexOfFirst { it.title == uiState.cloudEntity!!.name }) }
                    LaunchedEffect(currentIndex) {
                        viewModel.emitIntentOnIO(IndexUiIntent.SetCloudEntity(name = accounts[currentIndex].title))
                    }
                    Selectable(
                        title = stringResource(id = R.string.account),
                        leadingIcon = uiState.cloudEntity?.type?.icon ?: ImageVector.vectorResource(id = R.drawable.ic_rounded_person),
                        value = if (uiState.cloudEntity == null) stringResource(id = R.string.choose_an_account) else accounts[currentIndex].desc
                            ?: stringResource(id = R.string.unknown),
                        current = if (uiState.cloudEntity == null) stringResource(id = R.string.not_selected) else accounts[currentIndex].title
                    ) {
                        viewModel.launchOnIO {
                            val (state, selectedIndex) = dialogState.select(
                                title = accountText,
                                defIndex = currentIndex,
                                items = accounts
                            )
                            if (state.isConfirm) {
                                currentIndex = selectedIndex
                            }
                        }
                    }
                }
            }

            val appsInteractionSource = remember { MutableInteractionSource() }
            Clickable(
                title = stringResource(id = R.string.apps),
                value = stringResource(id = R.string.restore_query_apps_desc),
                leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_apps),
                interactionSource = appsInteractionSource,
            ) {
                viewModel.emitIntentOnIO(IndexUiIntent.ToAppList(navController))
            }

            val filesInteractionSource = remember { MutableInteractionSource() }
            Clickable(
                title = stringResource(id = R.string.files),
                value = stringResource(id = R.string.restore_query_files_desc),
                leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_folder_open),
                interactionSource = filesInteractionSource,
            ) {
                // 根据存储类型选择不同的导航路径
                val route = if (uiState.storageIndex == 1) {
                    // 云端文件恢复
                    MainRoutes.CloudFilesRestore.getRoute(uiState.cloudEntity?.name ?: "")
                } else {
                    // 本地文件恢复
                    MainRoutes.ResticFilesRestore.route
                }
                navController.navigateSingle(route)
            }

            Title(title = stringResource(id = R.string.advanced)) {
                Clickable(
                    title = stringResource(id = R.string.reload),
                    value = stringResource(id = R.string.reload_desc),
                    leadingIcon = Icons.Rounded.ManageSearch,
                ) {
                    viewModel.emitIntentOnIO(IndexUiIntent.ToReload(navController))
                }
            }
        }
    }
}
