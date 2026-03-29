package com.xayah.feature.main.dashboard

import android.annotation.SuppressLint
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.ui.component.DismissState
import com.xayah.core.ui.component.IconButton
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.MainIndexSubScaffold
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.model.SegmentProgress
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.navigateSingle
import kotlinx.coroutines.launch

@SuppressLint("StringFormatInvalid")
@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageDashboard() {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val viewModel = hiltViewModel<IndexViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current!!
    val lastBackupTime by viewModel.lastBackupTimeState.collectAsStateWithLifecycle()
    val directoryState by viewModel.directoryState.collectAsStateWithLifecycle()
    val nullBackupDir by remember(directoryState) { mutableStateOf(directoryState == null) }
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val scope = rememberCoroutineScope()

    LaunchedEffect(null) {
        viewModel.emitIntentOnIO(IndexUiIntent.Update)
    }

    MainIndexSubScaffold(
        scrollBehavior = scrollBehavior,
        snackbarHostState = viewModel.snackbarHostState,
        title = stringResource(id = R.string.app_name),
        updateAvailable = uiState.latestRelease != null,
        onVersionChipClick = {
            scope.launch {
                val state = dialogState.open(
                    initialState = false,
                    title = context.getString(R.string.update_available),
                    icon = null,
                    dismissText = context.getString(R.string.changelog),
                    confirmText = context.getString(R.string.download),
                    block = { _ -> Text(text = context.getString(R.string.args_update_from, BuildConfigUtil.VERSION_NAME, uiState.latestRelease?.name)) }
                ).first
                when (state) {
                    DismissState.CONFIRM -> {
                        uiState.latestRelease?.assets?.firstOrNull {
                            it.url.contains("revived") &&  // 添加 revived 关键字
                                    it.url.contains(BuildConfigUtil.FLAVOR_feature) &&
                                    it.url.contains(BuildConfigUtil.FLAVOR_abi)
                        }?.apply {
                            viewModel.emitIntent(IndexUiIntent.ToBrowser(context = context, url = this.url))
                        }
                    }

                    DismissState.CANCEL -> {
                        uiState.latestRelease?.url?.apply {
                            viewModel.emitIntent(IndexUiIntent.ToBrowser(context = context, url = this))
                        }
                    }

                    DismissState.DISMISS -> {}
                }
            }
        },
        actions = {
            IconButton(
                enabled = nullBackupDir.not(),
                icon = Icons.Outlined.Settings,
                onClick = {
                    navController.navigateSingle(MainRoutes.Settings.route)
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .paddingTop(SizeTokens.Level16)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
        ) {
            // 1. 存储信息区域（平铺大号数字 + 进度条）
            if (directoryState != null) {
                StorageOverviewSection(
                    title = stringResource(id = directoryState!!.titleResId),
                    used = SegmentProgress(used = directoryState!!.usedBytes, total = directoryState!!.totalBytes),
                    backupUsed = SegmentProgress(used = directoryState!!.childUsedBytes, total = directoryState!!.totalBytes),
                ) {
                    navController.navigateSingle(MainRoutes.Directory.route)
                }
            }

            // 2. 上次备份小卡片
            LastBackupChip(nullBackupDir = nullBackupDir, lastBackupTime = lastBackupTime) {
                if (nullBackupDir)
                    navController.navigateSingle(MainRoutes.Directory.route)
            }

            // 3. 备份应用 - 全宽填充按钮
            PrimaryActionButton(
                modifier = Modifier.padding(horizontal = SizeTokens.Level16),
                enabled = nullBackupDir.not(),
                title = stringResource(id = R.string.backup_apps),
                icon = Icons.Rounded.Apps,
            ) {
                navController.navigateSingle(MainRoutes.List.getRoute(target = Target.Apps, opType = OpType.BACKUP))
            }

            // 4. 备份文件 - 全宽描边按钮
            SecondaryActionButton(
                modifier = Modifier.padding(horizontal = SizeTokens.Level16),
                enabled = nullBackupDir.not(),
                title = stringResource(id = R.string.backup_files),
                icon = Icons.Rounded.Description,
            ) {
                navController.navigateSingle(MainRoutes.List.getRoute(target = Target.Files, opType = OpType.BACKUP))
            }

            Spacer(modifier = Modifier.height(SizeTokens.Level8))

            // 5. 导航列表项
            Column(
                modifier = Modifier.padding(horizontal = SizeTokens.Level16),
                verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)
            ) {
                // Cloud
                NavigationListItem(
                    icon = Icons.Outlined.Cloud,
                    iconTint = MaterialTheme.colorScheme.primary,
                    iconBackgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    title = stringResource(id = R.string.cloud),
                    subtitle = "Remote vault",
                    enabled = nullBackupDir.not(),
                ) {
                    navController.navigateSingle(MainRoutes.Cloud.route)
                }

                // Restore
                NavigationListItem(
                    icon = ImageVector.vectorResource(id = R.drawable.ic_rounded_history),
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    iconBackgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                    title = stringResource(id = R.string.restore),
                    subtitle = "Recovery tools",
                    enabled = nullBackupDir.not(),
                ) {
                    navController.navigateSingle(MainRoutes.Restore.route)
                }

                // History
                NavigationListItem(
                    icon = Icons.Rounded.ListAlt,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    iconBackgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                    title = stringResource(R.string.history),
                    subtitle = "Audit logs",
                    enabled = nullBackupDir.not(),
                ) {
                    navController.navigateSingle(MainRoutes.History.route)
                }
            }

            Spacer(modifier = Modifier.height(SizeTokens.Level16))
        }
    }
}