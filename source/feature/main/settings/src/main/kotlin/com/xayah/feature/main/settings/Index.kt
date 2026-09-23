package com.xayah.feature.main.settings

import android.content.Intent
import android.os.Build
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.datastore.KeyAutoScreenOff
import com.xayah.core.datastore.KeyMonet
import com.xayah.core.datastore.KeyResticOtgEnabled
import com.xayah.core.datastore.readResticCompressionLevel
import com.xayah.core.datastore.readResticOtgEnabled
import com.xayah.core.datastore.readUpdateChannel
import com.xayah.core.datastore.saveUpdateChannel
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.Selectable
import com.xayah.core.ui.component.Switchable
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.confirm
import com.xayah.core.ui.component.select
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.LanguageUtil
import com.xayah.core.util.getActivity
import com.xayah.core.util.navigateSingle
import com.xayah.core.util.readMappedLanguage
import com.xayah.feature.main.settings.restic.ResticViewModel
import com.xayah.feature.setup.MainActivity as SetupActivity
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PageSettings() {
    val context = LocalContext.current
    val navController = LocalNavController.current!!
    val viewModel = hiltViewModel<IndexViewModel>()
    val resticViewModel = hiltViewModel<ResticViewModel>()
    val directoryState by viewModel.directoryState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val scope = rememberCoroutineScope()
    val dialogState = LocalSlotScope.current!!.dialogSlot

    // 状态收集
    val resticVersion by resticViewModel.resticVersionState.collectAsStateWithLifecycle()
    val resticInitialized by resticViewModel.resticInitializedState.collectAsStateWithLifecycle(initialValue = false)
    val snapshotCount by resticViewModel.resticSnapshotCountState.collectAsStateWithLifecycle(initialValue = 0)
    val repoPath by resticViewModel.repoPathState.collectAsStateWithLifecycle()
    val resticError by resticViewModel.resticErrorState.collectAsStateWithLifecycle()

    // OTG 状态收集（独立于本地/云端，读 OTG 独立键与 OTG 专用状态流）
    val otgEnabled by context.readResticOtgEnabled().collectAsStateWithLifecycle(initialValue = false)
    val otgInitialized by resticViewModel.otgInitializedState.collectAsStateWithLifecycle(initialValue = false)
    val otgRepoPath by resticViewModel.otgRepoPathState.collectAsStateWithLifecycle()
    val otgSnapshotCount by resticViewModel.otgSnapshotCountState.collectAsStateWithLifecycle(initialValue = 0)

    // OTG 实时插盘挂载点（/mnt/media_rw/<UUID>）：由 ResticViewModel 订阅 otgMountEvents 事件驱动，
    // 插拔/换盘即时更新，无盘为 null。不再使用 delay 轮询。
    val otgMountPath by resticViewModel.otgMountPathState.collectAsStateWithLifecycle(initialValue = null)

    // 逻辑：首次进入页面检查本地/云端状态，并对 OTG 做一次兜底首刷
    // （实时性由 ViewModel 的 otgMountEvents 订阅承担，此处仅兜底）。
    LaunchedEffect(Unit) {
        resticViewModel.checkResticStatus()
        resticViewModel.refreshOtgStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.settings)) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
        ) {
            // --- 备份和恢复设置 ---
            Column {
                Clickable(
                    icon = ImageVector.vectorResource(id = R.drawable.ic_rounded_acute),
                    title = stringResource(id = R.string.backup_settings),
                ) {
                    navController.navigateSingle(MainRoutes.BackupSettings.route)
                }
                Clickable(
                    icon = ImageVector.vectorResource(id = R.drawable.ic_rounded_history),
                    title = stringResource(id = R.string.restore_settings),
                ) {
                    navController.navigateSingle(MainRoutes.RestoreSettings.route)
                }
                Clickable(
                    title = stringResource(id = R.string.setup),
                    value = stringResource(id = R.string.enter_the_setup_page_again),
                ) {
                    context.getActivity()?.finish()
                    context.startActivity(Intent(context, SetupActivity::class.java))
                }
            }

            // --- 外观设置 ---
            Title(title = stringResource(id = R.string.appearance)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Switchable(
                        key = KeyMonet,
                        title = stringResource(id = R.string.monet),
                        checkedText = stringResource(id = R.string.monet_desc),
                    )
                }
                DarkThemeSelectable()

                val locale by context.readMappedLanguage().collectAsStateWithLifecycle(initialValue = LanguageUtil.getSystemLocale(context))
                Clickable(
                    title = stringResource(id = R.string.language),
                    value = locale.getDisplayName(locale)
                ) {
                    navController.navigateSingle(MainRoutes.LanguageSettings.route)
                }
            }

            // --- Restic 配置 ---
            Title(title = stringResource(id = R.string.restic_configuration)) {
                Clickable(
                    title = stringResource(id = R.string.restic_version),
                    value = resticVersion ?: stringResource(id = R.string.restic_not_detected)
                ) {
                    scope.launch {
                        resticViewModel.checkResticStatus()
                    }
                }

                Clickable(
                    title = stringResource(id = R.string.restic_initialization_status),
                    value = when {
                        resticVersion == null -> stringResource(id = R.string.restic_not_detected)
                        resticError != null -> resticError!!
                        !resticInitialized -> stringResource(id = R.string.restic_not_initialized)
                        else -> stringResource(id = R.string.restic_initialized_at, repoPath ?: "")
                    }
                ) {
                    if (resticVersion != null) {
                        navController.navigateSingle(MainRoutes.ResticInitialization.getRoute(isOtg = false))
                    }
                }

                Clickable(
                    title = stringResource(id = R.string.restic_snapshot_count),
                    value = when {
                        resticVersion == null -> stringResource(id = R.string.restic_not_detected)
                        resticError != null -> resticError!!
                        !resticInitialized -> stringResource(id = R.string.restic_not_initialized)
                        snapshotCount > 0 -> stringResource(id = R.string.restic_snapshots_count, snapshotCount)
                        else -> stringResource(id = R.string.restic_no_snapshots)
                    }
                ) {}

                Clickable(
                    title = stringResource(id = R.string.restic_password),
                    value = stringResource(id = R.string.restic_password_desc),
                ) {
                    navController.navigateSingle(MainRoutes.ResticPassword.route)
                }
                val compressionLevel by context.readResticCompressionLevel().collectAsStateWithLifecycle(initialValue = -1)
                val currentLevelLabel = when (compressionLevel) {
                    -1 -> "AUTO"
                    0 -> "OFF"
                    else -> "L$compressionLevel"
                }
                Clickable(
                    title = stringResource(id = R.string.restic_enable_compression),
                    value = stringResource(R.string.args_current_level, currentLevelLabel),
                ) {
                    navController.navigateSingle(MainRoutes.BackupSettings.route)
                }
            }

            // --- OTG USB 存储 ---
            Title(title = stringResource(id = R.string.otg_usb_storage)) {
                // 总开关：始终显示，控制下方 OTG 项显隐
                Switchable(
                    key = KeyResticOtgEnabled,
                    defValue = false,
                    title = stringResource(id = R.string.otg_usb_storage_enable),
                    checkedText = stringResource(id = R.string.otg_usb_storage_enable_desc),
                )

                if (otgEnabled) {
                    val mount = otgMountPath
                    // OTG 状态：始终一行
                    Clickable(
                        title = stringResource(id = R.string.restic_otg_status),
                        value = if (mount == null)
                            stringResource(id = R.string.restic_otg_please_insert)
                        else
                            stringResource(id = R.string.restic_otg_connected)
                    ) {
                        scope.launch {
                            resticViewModel.refreshOtgStatus()
                        }
                    }

                    // 已插盘：展开路径 / 初始化状态 / 快照数
                    if (mount != null) {
                        Clickable(
                            title = stringResource(id = R.string.restic_otg_storage_path),
                            value = mount
                        ) {}

                        Clickable(
                            title = stringResource(id = R.string.restic_initialization_status),
                            value = when {
                                resticVersion == null -> stringResource(id = R.string.restic_not_detected)
                                !otgInitialized -> stringResource(id = R.string.restic_not_initialized)
                                else -> stringResource(id = R.string.restic_initialized_at, otgRepoPath ?: "")
                            }
                        ) {
                            if (resticVersion != null) {
                                navController.navigateSingle(MainRoutes.ResticInitialization.getRoute(isOtg = true))
                            }
                        }

                        Clickable(
                            title = stringResource(id = R.string.restic_snapshot_count),
                            value = when {
                                resticVersion == null -> stringResource(id = R.string.restic_not_detected)
                                !otgInitialized -> stringResource(id = R.string.restic_not_initialized)
                                otgSnapshotCount > 0 -> stringResource(id = R.string.restic_snapshots_count, otgSnapshotCount)
                                else -> stringResource(id = R.string.restic_no_snapshots)
                            }
                        ) {}
                        // 备份应用到 OTG
                        Clickable(
                            enabled = otgInitialized,
                            title = stringResource(id = R.string.otg_backup_apps),
                        ) {
                            navController.navigateSingle(
                                MainRoutes.List.getRoute(
                                    target = Target.Apps,
                                    opType = OpType.BACKUP,
                                    isOtg = true
                                )
                            )
                        }

                        // 备份文件到 OTG
                        Clickable(
                            enabled = otgInitialized,
                            title = stringResource(id = R.string.otg_backup_files),
                        ) {
                            navController.navigateSingle(
                                MainRoutes.List.getRoute(
                                    target = Target.Files,
                                    opType = OpType.BACKUP,
                                    isOtg = true
                                )
                            )
                        }
                    }
                }
            }

            // --- 高级设置 ---
            Title(title = stringResource(id = R.string.advanced)) {
                Switchable(
                    key = KeyAutoScreenOff,
                    defValue = false,
                    title = stringResource(id = R.string.auto_screen_off),
                    checkedText = stringResource(id = R.string.auto_screen_off_desc),
                )

                Clickable(
                    title = stringResource(id = R.string.cache_management),
                    value = stringResource(id = R.string.cache_management_desc),
                ) {
                    navController.navigateSingle(MainRoutes.CacheManagement.route)
                }
                Clickable(
                    title = stringResource(id = R.string.configurations),
                    value = stringResource(id = R.string.configurations_desc),
                ) {
                    navController.navigateSingle(MainRoutes.Configurations.route)
                }
                Clickable(
                    title = stringResource(id = R.string.about),
                    value = stringResource(id = R.string.about_app),
                ) {
                    navController.navigateSingle(MainRoutes.About.route)
                }

                // --- 更新通道 ---
                val channelOptions = listOf(
                    stringResource(id = R.string.update_channel_stable),
                    stringResource(id = R.string.update_channel_beta),
                )
                val updateChannelTitle = stringResource(id = R.string.update_channel)
                val betaTitle = stringResource(id = R.string.update_channel_beta_title)
                val betaWarning = stringResource(id = R.string.update_channel_beta_warning)
                val channelDialogItems = remember(channelOptions) {
                    channelOptions.map { DialogRadioItem<Unit>(enum = null, title = it, desc = null) }
                }
                val currentChannel by context.readUpdateChannel().collectAsStateWithLifecycle(initialValue = 0)
                Selectable(
                    title = updateChannelTitle,
                    current = channelOptions[currentChannel.coerceIn(0, 1)]
                ) {
                    val (state, selectedIndex) = dialogState.select(
                        title = updateChannelTitle,
                        defIndex = currentChannel.coerceIn(0, 1),
                        items = channelDialogItems
                    )
                    if (state.isConfirm) {
                        if (selectedIndex == 1) {
                            // 测试版：二次确认
                            if (dialogState.confirm(title = betaTitle, text = betaWarning)) {
                                context.saveUpdateChannel(1)
                            }
                        } else {
                            context.saveUpdateChannel(0)
                        }
                    }
                }
            }

            InnerBottomSpacer(innerPadding = paddingValues)
        }
    }
}