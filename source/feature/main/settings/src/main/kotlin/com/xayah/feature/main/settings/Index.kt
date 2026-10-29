package com.xayah.feature.main.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.datastore.KeyAutoScreenOff
import com.xayah.core.datastore.KeyMonet
import com.xayah.core.datastore.KeyResticOtgEnabled
import com.xayah.core.datastore.readAppIcon
import com.xayah.core.datastore.readResticCompressionLevel
import com.xayah.core.datastore.readResticOtgEnabled
import com.xayah.core.datastore.readUpdateChannel
import com.xayah.core.datastore.saveAppIcon
import com.xayah.core.datastore.saveUpdateChannel
import com.xayah.core.model.AppIconType
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.LabelLargeText
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.RoundChip
import com.xayah.core.ui.component.Selectable
import com.xayah.core.ui.component.Switchable
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.component.TitleSmallText
import com.xayah.core.ui.component.confirm
import com.xayah.core.ui.component.select
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.theme.withState
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.AppIconUtil
import com.xayah.core.util.LanguageUtil
import com.xayah.core.util.getActivity
import com.xayah.core.util.navigateSingle
import com.xayah.core.util.readMappedLanguage
import com.xayah.feature.main.settings.restic.ResticViewModel
import com.xayah.feature.setup.MainActivity as SetupActivity
import com.xayah.feature.setup.SetupRoutes
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

                // 应用图标：每个选项对应一个 activity-alias，切换后立即退出应用让桌面生效
                val appIconTypes = remember { AppIconType.values().toList() }
                val currentAppIconIndex by context.readAppIcon().collectAsStateWithLifecycle(initialValue = AppIconType.DEFAULT.id)
                val currentAppIcon = remember(currentAppIconIndex) { AppIconType.of(currentAppIconIndex) }
                val appIconTitle = stringResource(id = R.string.app_icon)
                // 设置行上直接展示当前样式真实的桌面图标，名称只作为无障碍描述保留
                val currentAppIconBitmap = remember(currentAppIcon) { renderAppIcon(context, currentAppIcon) }
                Selectable(
                    title = appIconTitle,
                    desc = stringResource(id = R.string.app_icon_desc),
                    current = stringResource(id = currentAppIcon.titleRes),
                    currentIcon = currentAppIconBitmap,
                ) {
                    val (state, selectedIndex) = dialogState.open(
                        initialState = appIconTypes.indexOf(currentAppIcon).coerceAtLeast(0),
                        title = appIconTitle,
                        contentHorizontalPadding = false,
                    ) { uiState ->
                        AppIconOptions(
                            types = appIconTypes,
                            selectedIndex = uiState.value,
                            onSelectedChange = { uiState.value = it },
                        )
                    }
                    if (state.isConfirm) {
                        val type = appIconTypes.getOrNull(selectedIndex) ?: return@Selectable
                        if (type != currentAppIcon) {
                            context.saveAppIcon(type.id)
                            AppIconUtil.apply(context, type)
                            AppIconUtil.exitApp(context)
                        }
                    }
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

                val initStatusEnabled = resticVersion != null
                val initPositionText = when {
                    resticVersion == null -> stringResource(id = R.string.restic_not_detected)
                    resticError != null -> resticError!!
                    !resticInitialized -> stringResource(id = R.string.restic_not_initialized)
                    else -> stringResource(id = R.string.restic_initialized_at, repoPath ?: "")
                }
                // 整行不再跳转（与「快照数量」一致，点击无动作）；只有胶囊可进入仓库选择流程。
                // 注意：整行 enabled 必须为 true，否则父级 Surface 关闭后会把胶囊的点击一起吞掉（表现为点了不跳转）
                Clickable(
                    enabled = true,
                    onClick = {},
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            TitleLargeText(
                                enabled = initStatusEnabled,
                                text = stringResource(id = R.string.restic_initialization_status),
                                color = ThemedColorSchemeKeyTokens.OnSurface.value.withState(initStatusEnabled),
                                fontWeight = FontWeight.Normal,
                            )
                            // 胶囊提示：插在「标题」与「初始化位置」之间，始终可点，点击直接进整套「选择仓库」流程
                            Spacer(modifier = Modifier.height(SizeTokens.Level4))
                            // 胶囊：直接复用引导页第二阶段（setup_two）的「全新仓库 / 已有仓库」自选流程，不在此重复实现
                            ChooseRepoChip {
                                context.startActivity(
                                    Intent(context, SetupActivity::class.java)
                                        .putExtra(SetupActivity.EXTRA_START_DESTINATION, SetupRoutes.Two.route)
                                )
                            }
                            Spacer(modifier = Modifier.height(SizeTokens.Level4))
                            TitleSmallText(
                                enabled = initStatusEnabled,
                                text = initPositionText,
                                color = ThemedColorSchemeKeyTokens.Outline.value.withState(initStatusEnabled),
                                fontWeight = FontWeight.Normal,
                            )
                        }
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

                        // 整行不再跳转（与「快照数量」一致，点击无动作）；OTG 的调整只能点下方胶囊
                        Clickable(
                            title = stringResource(id = R.string.restic_initialization_status),
                            value = when {
                                resticVersion == null -> stringResource(id = R.string.restic_not_detected)
                                !otgInitialized -> stringResource(id = R.string.restic_not_initialized)
                                else -> stringResource(id = R.string.restic_initialized_at, otgRepoPath ?: "")
                            }
                        ) {}

                        // 胶囊始终可点：父级行若被 disabled，会把胶囊的点击一起吞掉
                        ChooseRepoChip {
                            navController.navigateSingle(MainRoutes.ResticInitialization.getRoute(isOtg = true))
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

/**
 * 「点我选择新仓库」胶囊提示：M3E 风格胶囊按钮，点击直接进入重新选择仓库流程
 * （与存储设置页一致：先切到“重新初始化”模式，再进入仓库选择页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseRepoChip(
    onClick: () -> Unit,
) {
    RoundChip(onClick = onClick) {
        Row(
            modifier = Modifier.padding(horizontal = SizeTokens.Level16, vertical = SizeTokens.Level6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = ThemedColorSchemeKeyTokens.OnPrimaryContainer.value,
                modifier = Modifier.size(SizeTokens.Level18),
            )
            Spacer(modifier = Modifier.width(SizeTokens.Level4))
            LabelLargeText(
                text = stringResource(id = R.string.restic_choose_new_repo),
                color = ThemedColorSchemeKeyTokens.OnPrimaryContainer.value,
            )
        }
    }
}

/** 图标样式对应的显示名。 */
private val AppIconType.titleRes: Int
    get() = when (this) {
        AppIconType.DEFAULT -> R.string.app_icon_default
        AppIconType.DEVICE_BRIDGE -> R.string.app_icon_device_bridge
        AppIconType.SHIELD_SYNC -> R.string.app_icon_shield_sync
    }

/** PackageManager 中该别名声明的图标，直接栅格化，保证预览与桌面完全一致。 */
private fun renderAppIcon(context: Context, type: AppIconType): ImageBitmap? {
    val drawable = AppIconUtil.getIcon(context, type) ?: return null
    val px = 192
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, px, px)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}

/**
 * 三个图标横向平铺，只展示图标本身。
 *
 * 图标在进入弹窗时一次性栅格化并缓存：若在每一项内部按需 remember，
 * 选中项变化时重组会导致未选中项被重新解码，表现为"只剩选中项渲染"。
 */
@Composable
private fun AppIconOptions(
    types: List<AppIconType>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val icons = remember { types.map { renderAppIcon(context, it) } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SizeTokens.Level24, vertical = SizeTokens.Level8),
        horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16),
    ) {
        types.forEachIndexed { index, type ->
            AppIconBadge(
                modifier = Modifier
                    .weight(1f)
                    .selectable(selected = index == selectedIndex, onClick = { onSelectedChange(index) }),
                icon = icons[index],
                label = stringResource(id = type.titleRes),
                selected = index == selectedIndex,
            )
        }
    }
}

@Composable
private fun AppIconBadge(
    modifier: Modifier = Modifier,
    icon: ImageBitmap?,
    label: String,
    selected: Boolean,
) {
    val shape = RoundedCornerShape(SizeTokens.Level16)
    val outline = if (selected) ThemedColorSchemeKeyTokens.Primary.value else ThemedColorSchemeKeyTokens.Outline.value
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(ThemedColorSchemeKeyTokens.Surface.value, shape)
            .border(width = 3.dp, color = outline, shape = shape)
            .padding(SizeTokens.Level12),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = label, modifier = Modifier.fillMaxSize())
        }
    }
}