package com.xayah.databackup

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import java.net.URLDecoder
import com.xayah.core.ui.component.AnimatedNavHost
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.theme.DataBackupTheme
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.command.BaseUtil
import com.xayah.feature.main.cloud.PageCloud
import com.xayah.feature.main.cloud.add.PageCloudAddAccount
import com.xayah.feature.main.cloud.add.PageFTPSetup
import com.xayah.feature.main.cloud.add.PageSFTPSetup
import com.xayah.feature.main.cloud.add.PageSMBSetup
import com.xayah.feature.main.cloud.add.PageWebDAVSetup
import com.xayah.feature.main.cloud.add.PageS3Setup
import com.xayah.feature.main.configurations.PageConfigurations
import com.xayah.feature.main.dashboard.PageDashboard
import com.xayah.feature.main.details.DetailsRoute
import com.xayah.feature.main.directory.PageDirectory
import com.xayah.feature.main.history.HistoryRoute
import com.xayah.feature.main.history.TaskDetailsRoute
import com.xayah.feature.main.list.ListRoute
import com.xayah.feature.main.processing.medium.backup.MediumBackupProcessingGraph
import com.xayah.feature.main.processing.medium.restore.MediumRestoreProcessingGraph
import com.xayah.feature.main.processing.packages.backup.PackagesBackupProcessingGraph
import com.xayah.feature.main.processing.packages.restore.PackagesRestoreProcessingGraph
import com.xayah.feature.main.restore.PageRestore
import com.xayah.feature.main.restore.reload.PageReload
import com.xayah.feature.main.settings.PageSettings
import com.xayah.feature.main.settings.about.PageAboutSettings
import com.xayah.feature.main.settings.about.PageTranslatorsSettings
import com.xayah.feature.main.settings.backup.PageBackupSettings
import com.xayah.feature.main.settings.blacklist.PageBlackList
import com.xayah.feature.main.settings.language.PageLanguageSelector
import com.xayah.feature.main.settings.restore.PageRestoreSettings
import com.xayah.feature.main.settings.cache.PageCacheManagement
import com.xayah.feature.main.settings.restic.ResticRepoPathScreen
import com.xayah.feature.main.settings.restic.ResticPasswordScreen
import com.xayah.feature.main.settings.restic.ResticInitializationScreen
import com.xayah.feature.main.restore.ResticRestorePage
import com.xayah.feature.main.restore.ResticFilesRestorePage
import com.xayah.feature.main.restore.ResticBackupDetailPage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.ExperimentalSerializationApi
import com.xayah.feature.main.restore.ResticBackupGroup

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @ExperimentalCoroutinesApi
    @ExperimentalAnimationApi
    @ExperimentalFoundationApi
    @ExperimentalLayoutApi
    @ExperimentalMaterial3Api
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        runBlocking {
            runCatching {
                BaseUtil.initializeEnvironment(context = this@MainActivity)
            }
        }

        setContent {
            DataBackupTheme {
                val navController = rememberNavController()
                CompositionLocalProvider(
                    LocalNavController provides navController,
                    androidx.lifecycle.compose.LocalLifecycleOwner provides androidx.compose.ui.platform.LocalLifecycleOwner.current,
                ) {
                    AnimatedNavHost(
                        navController = navController,
                        startDestination = MainRoutes.Dashboard.route,
                    ) {
                        composable(MainRoutes.Dashboard.route) {
                            PageDashboard()
                        }
                        composable(MainRoutes.Cloud.route) {
                            PageCloud()
                        }
                        composable(MainRoutes.CloudAddAccount.route) {
                            PageCloudAddAccount()
                        }
                        composable(MainRoutes.FTPSetup.route) {
                            PageFTPSetup()
                        }
                        composable(MainRoutes.WebDAVSetup.route) {
                            PageWebDAVSetup()
                        }
                        composable(MainRoutes.SMBSetup.route) {
                            PageSMBSetup()
                        }
                        composable(MainRoutes.SFTPSetup.route) {
                            PageSFTPSetup()
                        }
                        composable(MainRoutes.S3Setup.route) {  // 新增路由注册
                            PageS3Setup()
                        }
                        composable(MainRoutes.List.route) {
                            ListRoute()
                        }
                        composable(MainRoutes.Details.route) {
                            DetailsRoute()
                        }
                        composable(MainRoutes.History.route) {
                            HistoryRoute()
                        }
                        composable(MainRoutes.TaskDetails.route) {
                            TaskDetailsRoute()
                        }
                        composable(MainRoutes.PackagesBackupProcessingGraph.route) {
                            PackagesBackupProcessingGraph()
                        }
                        composable(
                            route = MainRoutes.PackagesRestoreProcessingGraph.route,
                            arguments = listOf(
                                navArgument(MainRoutes.ARG_ACCOUNT_NAME) { type = NavType.StringType },
                                navArgument(MainRoutes.ARG_ACCOUNT_REMOTE) { type = NavType.StringType },
                                navArgument(MainRoutes.ARG_PACKAGE_NAME_FILTER) { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val cloudName = backStackEntry.arguments?.getString(MainRoutes.ARG_ACCOUNT_NAME) ?: ""
                            val backupDir = backStackEntry.arguments?.getString(MainRoutes.ARG_ACCOUNT_REMOTE) ?: ""
                            val packageName = backStackEntry.arguments?.getString(MainRoutes.ARG_PACKAGE_NAME_FILTER) ?: ""
                            Log.d("Navigation", "PackagesRestoreProcessingGraph: cloudName=$cloudName, backupDir=$backupDir, packageName=$packageName")
                            PackagesRestoreProcessingGraph(packageNameFilter = packageName)
                        }
                        composable(MainRoutes.MediumBackupProcessingGraph.route) {
                            MediumBackupProcessingGraph()
                        }
                        composable(MainRoutes.MediumRestoreProcessingGraph.route) {
                            MediumRestoreProcessingGraph()
                        }
                        composable(MainRoutes.CacheManagement.route) {
                            PageCacheManagement()
                        }
                        composable(MainRoutes.Settings.route) {
                            PageSettings()
                        }
                        composable(MainRoutes.Restore.route) {
                            PageRestore()
                        }

                        composable(MainRoutes.ResticRestore.route) {  // 添加这行
                            ResticRestorePage(navController = navController)
                        }

                        composable(
                            route = MainRoutes.ResticBackupDetail.route,
                            arguments = listOf( // 【必须声明参数】
                                navArgument(MainRoutes.ARG_GROUP) {
                                    type = NavType.StringType
                                    nullable = true
                                }
                            )
                        ) { backStackEntry ->
                            // 1. 提取已编码的参数
                            val groupJsonEncoded = backStackEntry.arguments?.getString(MainRoutes.ARG_GROUP)

                            val group = groupJsonEncoded?.let { encodedJson ->
                                try {
                                    // 2. URL 解码
                                    val groupJsonDecoded = URLDecoder.decode(encodedJson, "UTF-8")

                                    // 3. JSON 反序列化
                                    val decodedGroup = Json.decodeFromString<ResticBackupGroup>(groupJsonDecoded)

                                    Log.d("MainActivity", "Successfully decoded ResticBackupGroup.")
                                    decodedGroup
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to decode ResticBackupGroup for nav: ${e.message}", e)
                                    null
                                }
                            }

                            group?.let {
                                // 4. 成功后导航到详情页
                                ResticBackupDetailPage(navController = navController, group = it)
                            } ?: run {
                                Log.e("MainActivity", "Group is null, popping back stack.")
                                // 参数获取失败，返回上一页
                                navController.popBackStack()
                            }
                        }

                        composable(MainRoutes.Reload.route) {
                            PageReload()
                        }
                        composable(MainRoutes.BackupSettings.route) {
                            PageBackupSettings()
                        }
                        composable(MainRoutes.RestoreSettings.route) {
                            PageRestoreSettings()
                        }
                        composable(MainRoutes.LanguageSettings.route) {
                            PageLanguageSelector()
                        }
                        composable(MainRoutes.BlackList.route) {
                            PageBlackList()
                        }
                        composable(MainRoutes.Configurations.route) {
                            PageConfigurations()
                        }
                        composable(MainRoutes.About.route) {
                            PageAboutSettings()
                        }
                        composable(MainRoutes.Translators.route) {
                            PageTranslatorsSettings()
                        }
                        composable(route = MainRoutes.Directory.route) {
                            PageDirectory()
                        }

                        composable(MainRoutes.ResticFilesRestore.route) {
                            ResticFilesRestorePage(navController = navController)
                        }

                        composable(MainRoutes.ResticRepoPath.route) {
                            ResticRepoPathScreen()
                        }
                        composable(MainRoutes.ResticPassword.route) {
                            ResticPasswordScreen()
                        }
                        composable(MainRoutes.ResticInitialization.route) {
                            ResticInitializationScreen()
                        }
                    }
                }
            }
        }
    }
}
