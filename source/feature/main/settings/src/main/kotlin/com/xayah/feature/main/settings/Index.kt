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
import com.xayah.core.datastore.KeyResticEnableCompression
import com.xayah.core.datastore.readResticCompressionLevel
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.Switchable
import com.xayah.core.ui.component.Title
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

    // 状态收集
    val resticVersion by resticViewModel.resticVersionState.collectAsStateWithLifecycle()
    val resticInitialized by resticViewModel.resticInitializedState.collectAsStateWithLifecycle(initialValue = false)
    val snapshotCount by resticViewModel.resticSnapshotCountState.collectAsStateWithLifecycle(initialValue = 0)
    val repoPath by resticViewModel.repoPathState.collectAsStateWithLifecycle()
    val resticError by resticViewModel.resticErrorState.collectAsStateWithLifecycle()

    // 逻辑：首次进入页面检查状态
    LaunchedEffect(Unit) {
        resticViewModel.checkResticStatus()
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

            // --- 备份管理 ---
            Title(title = stringResource(id = R.string.manage_backups)) {
                Clickable(
                    icon = Icons.Outlined.Block,
                    title = stringResource(id = R.string.blacklist),
                    value = stringResource(id = R.string.blacklist_desc),
                ) {
                    navController.navigateSingle(MainRoutes.BlackList.route)
                }
                Clickable(
                    icon = ImageVector.vectorResource(id = R.drawable.ic_rounded_folder_open),
                    title = stringResource(id = R.string.backup_dir),
                    value = if (directoryState == null) null else stringResource(id = directoryState!!.titleResId),
                ) {
                    navController.navigateSingle(MainRoutes.Directory.route)
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
                        navController.navigateSingle(MainRoutes.ResticInitialization.route)
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
            }
            InnerBottomSpacer(innerPadding = paddingValues)
        }
    }
}