package com.xayah.feature.main.settings

import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
// DataStore Keys
import com.xayah.core.datastore.KeyAutoScreenOff
import com.xayah.core.datastore.KeyMonet
import com.xayah.core.datastore.KeyResticEnableCompression // 引入 Restic 压缩 Key
// UI Components
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.Switchable
import com.xayah.core.ui.component.Title
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar  // 补充导入
import androidx.compose.runtime.LaunchedEffect
// Navigation and Routing
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.util.LocalNavController // 补充导入
// Tokens and Utils
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.LanguageUtil
import com.xayah.core.util.getActivity
import com.xayah.core.util.navigateSingle
import com.xayah.core.util.readMappedLanguage
// Activities and ViewModels
import com.xayah.feature.setup.MainActivity as SetupActivity
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.IndexViewModel // 补充导入
import com.xayah.feature.main.settings.restic.ResticViewModel

@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageSettings() {
    val context = LocalContext.current
    val navController = LocalNavController.current!!
    val viewModel = hiltViewModel<IndexViewModel>()
    val resticViewModel = hiltViewModel<ResticViewModel>()
    val directoryState by viewModel.directoryState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // 收集状态
    val resticVersion by resticViewModel.resticVersionState.collectAsStateWithLifecycle()
    val resticInitialized by resticViewModel.resticInitializedState.collectAsStateWithLifecycle(initialValue = false)
    val snapshotCount by resticViewModel.resticSnapshotCountState.collectAsStateWithLifecycle(initialValue = 0)
    val repoPath by resticViewModel.repoPathState.collectAsStateWithLifecycle()

    // 触发 Restic 状态检查
    LaunchedEffect(Unit) {
        resticViewModel.checkResticStatus()
    }

    // 监听状态变化
    LaunchedEffect(resticVersion, resticInitialized, snapshotCount) {
        Log.d("Settings", "Restic状态: Version=$resticVersion, Initialized=$resticInitialized, Snapshots=$snapshotCount")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.settings)) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->  // 使用明确的参数名
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize()
                .padding(paddingValues),  // 使用 paddingValues 而不是 it
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
                    // 重新进入设置页面（通常用于重新配置应用）
                    context.getActivity().finish()
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
                // 状态显示部分
                Clickable(
                    title = stringResource(id = R.string.restic_version),
                    value = if (resticVersion != null) {
                        resticVersion
                    } else {
                        stringResource(id = R.string.restic_not_detected)
                    }
                ) {
                    // 版本信息不可点击，仅用于显示
                }

                // 根据初始化状态显示不同信息
                if (resticInitialized) {
                    Clickable(
                        title = stringResource(id = R.string.restic_initialization_status),
                        value = stringResource(id = R.string.restic_initialized_at, repoPath ?: "")
                    ) {
                        // 状态信息不可点击，仅用于显示
                    }

                    Clickable(
                        title = stringResource(id = R.string.restic_snapshot_count),
                        value = if (snapshotCount > 0) {
                            stringResource(id = R.string.restic_snapshots_count, snapshotCount)
                        } else {
                            stringResource(id = R.string.restic_no_snapshots)
                        }
                    ) {
                        // 快照信息不可点击，仅用于显示
                    }
                } else {
                    // 未初始化时显示初始化按钮
                    Clickable(
                        title = stringResource(id = R.string.initialize_restic),
                        value = stringResource(id = R.string.initialize_restic_desc),
                    ) {
                        navController.navigateSingle(MainRoutes.ResticInitialization.route)
                    }
                }

                // 配置选项 - 始终显示
                Clickable(
                    title = stringResource(id = R.string.restic_password),
                    value = stringResource(id = R.string.restic_password_desc),
                ) {
                    navController.navigateSingle(MainRoutes.ResticPassword.route)
                }
                Switchable(
                    key = KeyResticEnableCompression,
                    defValue = true,
                    title = stringResource(id = R.string.restic_enable_compression),
                    checkedText = stringResource(id = R.string.restic_enable_compression_desc),
                )
            }

            // --- 高级设置 ---
            Title(title = stringResource(id = R.string.advanced)) {
                Switchable(
                    key = KeyAutoScreenOff,
                    defValue = false,
                    title = stringResource(id = R.string.auto_screen_off),
                    checkedText = stringResource(id = R.string.auto_screen_off_desc),
                )

                // 添加缓存管理入口
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