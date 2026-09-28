package com.xayah.feature.main.restore

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.xayah.core.ui.component.DropdownMenuItem
import com.xayah.core.ui.component.IconButton
import com.xayah.core.ui.component.ModalDropdownMenu
import com.xayah.core.model.DataType
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.SearchBar
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.encodeAccountId
import com.xayah.core.util.decodeURL
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.restore.R
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
fun CloudRestorePage(
    navController: NavController,
    accountName: String,
    viewModel: CloudRestoreViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val iconVersion by viewModel.iconVersion.collectAsStateWithLifecycle()
    val accountId = encodeAccountId(accountName.replace("accountName=", "").decodeURL())
    val scope = rememberCoroutineScope()

    // 已选 group 的 key 集合：提升为 ViewModel 持有，详情页往返不清空
    val selectedKeys = viewModel.selectedKeys
    var isPreparing by remember { mutableStateOf(false) }

    // 搜索关键字：仅用于过滤可见列表，绝不影响 selectedKeys 与互斥选择逻辑
    var searchText by remember { mutableStateOf("") }

    fun keyOf(g: ResticBackupGroup) = "${g.userId}-${g.packageName}-${g.timestamp}"

    // 统一的退出选择：清空已选集合，避免二次进入 Setup 时累加
    fun exitSelection() {
        selectedKeys.clear()
    }

    // ---- 批量选择（全选/反选） ----

    fun appKey(g: ResticBackupGroup) = "${g.userId}-${g.packageName}"

    fun selectableGroups(): List<ResticBackupGroup> =
        (uiState as? CloudRestoreUiState.Success)?.groups
            ?.filter { g -> g.backups.any { it.dataType == DataType.PACKAGE_CONFIG } }
            ?: emptyList()

    fun hasMultiSnapshotApp(): Boolean =
        selectableGroups().groupBy { appKey(it) }.values.any { it.size > 1 }

    // 与单个勾选一致的互斥：同 (userId, packageName) 只保留一个 key
    fun selectExclusive(g: ResticBackupGroup) {
        val key = keyOf(g)
        val prefix = "${g.userId}-${g.packageName}-"
        selectedKeys.removeAll { it.startsWith(prefix) && it != key }
        if (!selectedKeys.contains(key)) selectedKeys.add(key)
    }

    fun selectAllLatest() {
        selectableGroups().groupBy { appKey(it) }.values.forEach { list ->
            list.maxByOrNull { it.timestamp }?.let { selectExclusive(it) }
        }
    }

    fun reverseAllLatest() {
        selectableGroups().groupBy { appKey(it) }.values.forEach { list ->
            val latest = list.maxByOrNull { it.timestamp } ?: return@forEach
            val prefix = "${latest.userId}-${latest.packageName}-"
            if (selectedKeys.any { it.startsWith(prefix) }) {
                selectedKeys.removeAll { it.startsWith(prefix) }
            } else {
                selectedKeys.add(keyOf(latest))
            }
        }
    }

    var pendingBulkAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun runBulk(action: () -> Unit) {
        if (hasMultiSnapshotApp()) pendingBulkAction = action else action()
    }

    LaunchedEffect(accountName) {
        viewModel.setCloudEntity(accountName)
    }

    val needsRefresh = navController.currentBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow("cloud_needs_refresh", false)
        ?.collectAsStateWithLifecycle()

    LaunchedEffect(needsRefresh?.value) {
        if (needsRefresh?.value == true) {
            exitSelection()   // ★ 返回列表时兜底清空勾选，防止残留
            viewModel.forceReload()
            navController.currentBackStackEntry
                ?.savedStateHandle
                ?.set("cloud_needs_refresh", false)
        }
    }

    RestoreScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState()),
        title = if (selectedKeys.isNotEmpty())
            stringResource(R.string.restore_selected_count, selectedKeys.size)
        else
            stringResource(R.string.restore_cloud_restic_restore_title),
        topBarActions = {
            var checkListExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
                IconButton(icon = Icons.Rounded.Checklist) {
                    checkListExpanded = true
                }
                ModalDropdownMenu(
                    expanded = checkListExpanded,
                    onDismissRequest = { checkListExpanded = false }
                ) {
                    DropdownMenuItem(text = stringResource(R.string.select_all)) {
                        checkListExpanded = false
                        runBulk { selectAllLatest() }
                    }
                    DropdownMenuItem(text = stringResource(R.string.unselect_all)) {
                        checkListExpanded = false
                        selectedKeys.clear()
                    }
                    DropdownMenuItem(text = stringResource(R.string.reverse_selection)) {
                        checkListExpanded = false
                        runBulk { reverseAllLatest() }
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = selectedKeys.isNotEmpty(),
                enter = scaleIn(),
                exit = scaleOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (isPreparing) return@ExtendedFloatingActionButton
                        val groups = (uiState as? CloudRestoreUiState.Success)?.groups ?: emptyList()
                        val selectedGroups = groups.filter { selectedKeys.contains(keyOf(it)) }
                        if (selectedGroups.isEmpty()) return@ExtendedFloatingActionButton
                        isPreparing = true
                        scope.launch {
                            try {
                                val ok = viewModel.prepareBatchRestore(selectedGroups)
                                if (ok) {
                                    exitSelection()   // ★ 成功后立即清空，再导航；防止退回列表时残留累加
                                    // 第二阶段统一走本地写回：packageName 空 → getPackages 返回全部激活包
                                    val route = MainRoutes.PackagesRestoreProcessingGraph.getRoute(packageName = "")
                                    navController.navigateSingle(route)
                                } else {
                                    // ★ 失败不清空，保留用户选择以便重试
                                    Log.e("CloudRestorePage", "prepareBatchRestore 失败，无可恢复项")
                                }
                            } catch (e: Exception) {
                                Log.e("CloudRestorePage", "批量恢复准备异常: ${e.message}", e)
                            } finally {
                                isPreparing = false
                            }
                        }
                    },
                    icon = { Icon(Icons.Rounded.ChevronRight, null) },
                    text = {
                        Text(
                            if (isPreparing) stringResource(R.string.processing)
                            else stringResource(R.string.restore_next_step)
                        )
                    },
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SizeTokens.Level16)
        ) {
            val currentState = uiState
            when (currentState) {
                is CloudRestoreUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is CloudRestoreUiState.Success -> {
                    if (currentState.groups.isEmpty()) {
                        // 原始数据为空：无任何云端备份
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            TitleLargeText(text = stringResource(R.string.restore_no_cloud_backup))
                        }
                    } else {
                        // 纯过滤：只影响可见列表，绝不修改 selectedKeys / 互斥选择逻辑
                        val filteredGroups = if (searchText.isBlank()) {
                            currentState.groups
                        } else {
                            val q = searchText.lowercase()
                            currentState.groups.filter { g ->
                                g.appLabel.lowercase().contains(q) ||
                                        g.packageName.lowercase().contains(q)
                            }
                        }

                        // 常驻搜索框（原始列表非空时显示）
                        SearchBar(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = SizeTokens.Level8),
                            enabled = true,
                            placeholder = stringResource(R.string.restore_search_hint),
                            onTextChange = { searchText = it }
                        )

                        if (filteredGroups.isEmpty()) {
                            // 有备份但无匹配结果
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                TitleLargeText(text = stringResource(R.string.restore_no_match))
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)
                            ) {
                                items(
                                    filteredGroups,
                                    key = { item: ResticBackupGroup -> "${item.userId}-${item.packageName}-${item.timestamp}" }
                                ) { group: ResticBackupGroup ->
                                    val hasConfigSnapshot =
                                        group.backups.any { it.dataType == DataType.PACKAGE_CONFIG }
                                    val key = keyOf(group)
                                    val checked = selectedKeys.contains(key)

                                    ResticBackupGroupItem(
                                        group = group,
                                        selectable = hasConfigSnapshot,   // 不完整备份不可选
                                        selected = checked,
                                        onSelectedChange = { want ->
                                            if (want) {
                                                // ★ (packageName, userId) 维度互斥：勾选新版本前，先剔除同一应用同用户的其它已选版本
                                                val dup = selectedKeys.filter { k ->
                                                    val parts = k.split("-", limit = 3)
                                                    parts.size == 3 &&
                                                            parts[0] == group.userId.toString() &&
                                                            parts[1] == group.packageName
                                                }
                                                selectedKeys.removeAll(dup)
                                                if (!selectedKeys.contains(key)) selectedKeys.add(key)
                                            } else {
                                                selectedKeys.remove(key)
                                            }
                                        },
                                        onClick = {
                                            // 行点击统一进入详情页（勾选交给右侧复选框）
                                            try {
                                                val groupJson = Json.encodeToString(group)
                                                val encodedJson = URLEncoder.encode(groupJson, "UTF-8")
                                                val cleanAccountName = accountName.replace("accountName=", "")
                                                val encodedAccountName = URLEncoder.encode(cleanAccountName, "UTF-8")
                                                val url = MainRoutes.CloudBackupDetail.getRoute(
                                                    encodedJson,
                                                    encodedAccountName
                                                )
                                                navController.navigateSingle(url)
                                            } catch (e: Exception) {
                                                Log.e("CloudRestorePage", "点击事件处理失败", e)
                                            }
                                        },
                                        context = LocalContext.current,
                                        accountId = accountId,
                                        iconVersion = iconVersion
                                    )
                                }
                            }
                        }
                    }
                }

                is CloudRestoreUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
                        ) {
                            TitleLargeText(text = stringResource(R.string.restore_load_failed))
                            BodyMediumText(text = currentState.message)
                            Button(onClick = { viewModel.setCloudEntity(accountName) }) {
                                Text(stringResource(R.string.restore_retry))
                            }
                        }
                    }
                }
            }
        }
    }
    // 多快照提示：确认后执行暂存的批量操作
    pendingBulkAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingBulkAction = null },
            title = { Text(stringResource(R.string.restore_dialog_notice)) },
            text = { Text(stringResource(R.string.restore_multi_snapshot_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingBulkAction = null
                    action()
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBulkAction = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}