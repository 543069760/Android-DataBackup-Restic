package com.xayah.feature.main.processing.packages.restore

import android.util.Log
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.xayah.core.datastore.KeyAutoScreenOff
import com.xayah.core.datastore.KeyResetRestoreList
import com.xayah.core.datastore.saveRestoreUser
import com.xayah.core.datastore.saveRestoreUserMap
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.PackageIcons
import com.xayah.core.ui.component.Selectable
import com.xayah.core.ui.component.Switchable
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.confirm
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.component.paddingVertical
import com.xayah.core.ui.component.select
import com.xayah.core.ui.token.SizeTokens
import com.xayah.feature.main.processing.FinishSetup
import com.xayah.feature.main.processing.GetUsers
import com.xayah.feature.main.processing.ProcessingSetupScaffold
import com.xayah.feature.main.processing.R
import com.xayah.feature.main.processing.SetCloudEntity
import com.xayah.feature.main.processing.UpdateApps
import com.xayah.feature.main.processing.UpdateAppsWithFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PagePackagesRestoreProcessingSetup(localNavController: NavHostController, viewModel: RestoreViewModelImpl, packageNameFilter: String = "") {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val packages by viewModel.packages.collectAsStateWithLifecycle()
    val packagesSize by viewModel.packagesSize.collectAsStateWithLifecycle()
    val restoreUsers by viewModel.restoreUsers.collectAsStateWithLifecycle()

    val dialogState = LocalSlotScope.current!!.dialogSlot
    val context = LocalContext.current
    var currentIndex by remember { mutableIntStateOf(0) }
    var prechecking by remember { mutableStateOf(false) }   // 「继续」预检进行中标志，防重入

    // 对话框文案（stringResource 必须在 Composable 作用域取）
    val lockedTitle = stringResource(id = R.string.restore_target_user_locked_title)
    val lockedMessage = stringResource(id = R.string.restore_target_user_locked_message)
    val missingTitle = stringResource(id = R.string.restore_target_user_missing_title)
    val missingMessage = stringResource(id = R.string.restore_target_user_missing_message)
    val createFailedTitle = stringResource(id = R.string.restore_target_user_create_failed_title)
    val createFailedMessage = stringResource(id = R.string.restore_target_user_create_failed_message)
    val cloneManualTitle = stringResource(id = R.string.restore_clone_manual_title)
    val cloneManualMessage = stringResource(id = R.string.restore_clone_manual_message)

    LaunchedEffect(null) {
        Log.d("Navigation", "PagePackagesRestoreProcessingSetup 启动，packageNameFilter: $packageNameFilter")
        viewModel.launchOnIO {
            // 清掉上次残留的 userId 重映射，保证本次预检写出的值是干净的
            context.saveRestoreUserMap("")
            viewModel.emitIntent(SetCloudEntity(""))
            viewModel.emitIntent(GetUsers)
            if (packageNameFilter.isNotEmpty()) {
                Log.d("Navigation", "使用包名筛选: $packageNameFilter")
                viewModel.emitIntent(UpdateAppsWithFilter(packageNameFilter))
            } else {
                Log.d("Navigation", "加载所有应用")
                viewModel.emitIntent(UpdateApps)
            }
        }
    }

    ProcessingSetupScaffold(
        scrollBehavior = scrollBehavior,
        snackbarHostState = viewModel.snackbarHostState,
        title = stringResource(id = R.string.setup),
        actions = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .paddingHorizontal(SizeTokens.Level24)
                    .paddingVertical(SizeTokens.Level8),
                horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level12, Alignment.End),
            ) {
                Button(
                    enabled = !prechecking,
                    onClick = {
                        if (prechecking) return@Button
                        Log.d(
                            "RestoreNavTrace",
                            "Continue clicked, currentIndex=$currentIndex, ts=${System.currentTimeMillis()}"
                        )
                        viewModel.launchOnIO {
                            prechecking = true
                            try {
                                // 预检
                                val targetUserIds =
                                    if (currentIndex == 0) viewModel.packages.value.map { it.userId }.toSet()
                                    else setOf(restoreUsers.getOrNull(currentIndex)?.title?.toIntOrNull() ?: -1)

                                // 进入时的用户快照：失败回滚时用它做差集，清扫 pm 半成功产生的游离用户
                                val usersAtEntry = viewModel.getUsers()

                                val classified = viewModel.classifyTargetUsers(targetUserIds)
                                val missing =
                                    classified[RestoreViewModelImpl.TargetUserState.MISSING] ?: emptyList()
                                val effectiveTargets = targetUserIds.toMutableSet()
                                val userRemap = mutableMapOf<Int, Int>()
                                val createdIds = mutableListOf<Int>()

                                // 失败/取消时的统一清扫：回滚本轮新建 + 差集清掉半成功游离用户
                                suspend fun rollbackNewUsers() {
                                    createdIds.forEach { viewModel.removeUser(it) }
                                    createdIds.clear()
                                    // 差集兜底：pm create-user 可能半成功——users 列表里出现了
                                    // 但 createUser 返回 -1 的游离记录，一并删掉防止污染系统分身服务
                                    val orphans = viewModel.getUsers() - usersAtEntry
                                    orphans.filter { it > 0 && it !in effectiveTargets }
                                        .forEach { viewModel.removeUser(it) }
                                    context.saveRestoreUserMap("")
                                }

                                // 分身类缺失（userId >= 100：OPPO=999/华为=810 等）pm create-user 建不出
                                // 对应 userType 且会污染 OEM 分身服务，直接引导手动开启
                                val (cloneMissing, normalMissing) = missing.partition { it >= 100 }

                                if (cloneMissing.isNotEmpty()) {
                                    dialogState.confirm(
                                        title = cloneManualTitle,
                                        text = String.format(cloneManualMessage, cloneMissing.joinToString(", ")),
                                    )
                                    return@launchOnIO   // 停留：用户去系统开分身后回来重走预检
                                }

                                // ① 普通缺失用户：一次确认 → 批量创建；中途失败回滚
                                if (normalMissing.isNotEmpty()) {
                                    val confirmed = dialogState.confirm(
                                        title = missingTitle,
                                        text = String.format(missingMessage, normalMissing.joinToString(", ")),
                                    )
                                    if (!confirmed) return@launchOnIO

                                    var failed = false
                                    for (missingId in normalMissing) {
                                        var newId = viewModel.findCreatedBackupUser(missingId)
                                        if (newId < 0) {
                                            newId = viewModel.createUser("backup-$missingId")
                                            if (newId >= 0) createdIds.add(newId)
                                        }
                                        if (newId < 0) { failed = true; break }

                                        // 后台启动新用户激活 CE：无锁屏用户此后 isUserUnlocked=true，
                                        // 不会误入 FBE 锁定对话框
                                        viewModel.startUser(newId)

                                        effectiveTargets.remove(missingId)
                                        effectiveTargets.add(newId)
                                        userRemap[missingId] = newId
                                    }

                                    if (failed) {
                                        rollbackNewUsers()
                                        dialogState.confirm(
                                            title = createFailedTitle,
                                            text = createFailedMessage,
                                        )
                                        return@launchOnIO
                                    }

                                    context.saveRestoreUserMap(
                                        userRemap.entries.joinToString(",") { "${it.key}:${it.value}" }
                                    )
                                }

                                // 把所有目标用户在后台拉起，激活 CE 存储。
                                // 已 running 的用户是 no-op；stopped 且无锁屏的用户启动后
                                // isUserUnlocked 即为 true，不会被误判为 FBE 锁定弹框。
                                effectiveTargets.filter { it > 0 }.forEach { viewModel.startUser(it) }

                                // ② 统一 FBE 复检（真锁定的系统分身仍会进锁定引导）
                                var locked = viewModel.queryLockedUserIds(effectiveTargets)
                                while (locked.isNotEmpty()) {
                                    val confirmed = dialogState.confirm(
                                        title = lockedTitle,
                                        text = lockedMessage,
                                    )
                                    if (!confirmed) {
                                        rollbackNewUsers()   // 取消也走差集清扫
                                        return@launchOnIO
                                    }
                                    locked = viewModel.queryLockedUserIds(effectiveTargets)
                                }

                                Log.d("RestoreNavTrace", "emit FinishSetup, ts=${System.currentTimeMillis()}")
                                viewModel.emitIntent(FinishSetup(navController = localNavController))
                            } finally {
                                prechecking = false
                            }
                        }
                    }) {
                    Text(text = stringResource(id = R.string._continue))
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize(),
        ) {
            Title(title = stringResource(id = R.string.storage)) {
                val interactionSource = remember { MutableInteractionSource() }
                Clickable(
                    title = stringResource(id = R.string.apps),
                    value = packagesSize,
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_apps),
                    interactionSource = interactionSource,
                    content = {
                        PackageIcons(modifier = Modifier.paddingTop(SizeTokens.Level8), packages = packages)
                    }
                )
            }
            Title(title = stringResource(id = R.string.settings)) {
                val restoreUserText = stringResource(R.string.restore_user)
                LaunchedEffect(currentIndex) {
                    viewModel.launchOnIO {
                        var userId = -1
                        if (currentIndex != 0) userId = restoreUsers[currentIndex].title.toIntOrNull() ?: -1
                        context.saveRestoreUser(userId)
                    }
                }
                Selectable(
                    enabled = restoreUsers.size != 1,
                    title = stringResource(id = R.string.restore_user),
                    value = stringResource(id = R.string.restore_user_desc),
                    current = restoreUsers[currentIndex].title,
                ) {
                    viewModel.launchOnIO {
                        val (state, selectedIndex) = dialogState.select(
                            title = restoreUserText,
                            defIndex = currentIndex,
                            items = restoreUsers
                        )
                        if (state.isConfirm) {
                            currentIndex = selectedIndex
                        }
                    }
                }
                Switchable(
                    key = KeyAutoScreenOff,
                    defValue = false,
                    title = stringResource(id = R.string.auto_screen_off),
                    checkedText = stringResource(id = R.string.auto_screen_off_desc),
                )
                Switchable(
                    key = KeyResetRestoreList,
                    defValue = false,
                    title = stringResource(id = R.string.reset_restore_list),
                    checkedText = stringResource(id = R.string.reset_restore_list_desc),
                )
            }
        }
    }
}