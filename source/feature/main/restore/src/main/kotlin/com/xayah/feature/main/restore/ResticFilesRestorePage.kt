package com.xayah.feature.main.restore

import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.xayah.core.model.DataType
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.DropdownMenuItem
import com.xayah.core.ui.component.IconButton
import com.xayah.core.ui.component.ModalDropdownMenu
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.DateUtil
import com.xayah.core.util.navigateSingle
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResticFileBackupGroupItem(
    group: ResticFileBackupGroup,
    onClick: () -> Unit,
    context: Context,
    selectable: Boolean = false,
    selected: Boolean = false,
    onSelectedChange: ((Boolean) -> Unit)? = null,
) {
    // 检查是否有 CONFIG 快照
    val hasConfigSnapshot = group.backups.any { it.dataType == DataType.PACKAGE_CONFIG }

    // 定义颜色
    val containerColor = Color(0xFFFF4D4F).copy(alpha = 0.12f)
    val contentColor = Color(0xFFD32F2F)
    val borderColor = Color(0xFFFFCCC7).copy(alpha = 0.5f)

    Surface(onClick = onClick) {
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .padding(SizeTokens.Level16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(SizeTokens.Level32),
                tint = MaterialTheme.colorScheme.primary
            )

            Column(modifier = Modifier.weight(1f)) {
                // 第1行: 文件名 + 胶囊标签
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TitleLargeText(
                        text = group.mediaName,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // 胶囊标签
                    if (!hasConfigSnapshot) {
                        Surface(
                            color = containerColor,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(0.5.dp, borderColor)
                        ) {
                            Text(
                                text = stringResource(R.string.restore_backup_incomplete),
                                color = contentColor,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                BodyMediumText(
                    text = group.fullPath,
                    color = ThemedColorSchemeKeyTokens.Outline.value,
                    maxLines = 2
                )

                BodyMediumText(
                    text = DateUtil.formatTimestamp(
                        group.timestamp,
                        DateUtil.PATTERN_YMD_HMS
                    ),
                    color = ThemedColorSchemeKeyTokens.Outline.value,
                    maxLines = 1
                )

                Text(
                    text = stringResource(R.string.restore_snapshot_count, group.snapshotCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 右侧复选框：仅含 CONFIG 的分组可选（与 app 恢复侧一致）
            if (selectable && onSelectedChange != null) {
                Checkbox(checked = selected, onCheckedChange = onSelectedChange)
            }
        }
    }
}

/**
 * 聚合模式下的媒体行：同一 (mediaName, fullPath) 只显示一行，点开展示该媒体的多版本快照。
 * 与 app 恢复侧的 ResticAppGroupItem 保持同一交互语义。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResticFileAppGroupItem(
    snapshots: List<ResticFileBackupGroup>,
    expanded: Boolean,
    hasSelected: Boolean,
    onToggleExpand: () -> Unit,
    context: Context,
    selectedKeys: List<String>,
    onSnapshotSelect: (ResticFileBackupGroup, Boolean) -> Unit,
    onSnapshotClick: (ResticFileBackupGroup) -> Unit,
) {
    val first = snapshots.first()
    val hasConfigSnapshot = snapshots.any { g -> g.backups.any { it.dataType == DataType.PACKAGE_CONFIG } }

    val containerColor = Color(0xFFFF4D4F).copy(alpha = 0.12f)
    val contentColor = Color(0xFFD32F2F)
    val borderColor = Color(0xFFFFCCC7).copy(alpha = 0.5f)

    Surface(onClick = onToggleExpand) {
        Column {
            Row(
                modifier = Modifier
                    .height(IntrinsicSize.Min)
                    .padding(SizeTokens.Level16),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(SizeTokens.Level32),
                    tint = MaterialTheme.colorScheme.primary
                )

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TitleLargeText(
                            text = first.mediaName,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (!hasConfigSnapshot) {
                            Surface(
                                color = containerColor,
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(0.5.dp, borderColor)
                            ) {
                                Text(
                                    text = stringResource(R.string.restore_backup_incomplete),
                                    color = contentColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    BodyMediumText(
                        text = first.fullPath,
                        color = ThemedColorSchemeKeyTokens.Outline.value,
                        maxLines = 2
                    )

                    Text(
                        text = stringResource(R.string.restore_group_backup_count, snapshots.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier
                        .size(SizeTokens.Level24)
                        .rotate(if (expanded) 90f else 0f),
                    tint = ThemedColorSchemeKeyTokens.Outline.value
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)) {
                    snapshots.forEach { snapshot ->
                        val key = "${snapshot.mediaName}-${snapshot.fullPath}-${snapshot.timestamp}"
                        val selectable = snapshot.backups.any { it.dataType == DataType.PACKAGE_CONFIG }
                        ResticFileBackupGroupItem(
                            group = snapshot,
                            context = context,
                            selectable = selectable,
                            selected = selectedKeys.contains(key),
                            onSelectedChange = { checked ->
                                if (selectable) onSnapshotSelect(snapshot, checked)
                            },
                            onClick = { onSnapshotClick(snapshot) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun ResticFilesRestorePage(
    navController: NavController,
    viewModel: ResticFilesRestoreViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 列表聚合模式：true = 同一 (mediaName, fullPath) 只显示一行、点开展示多版本快照；false = 平铺
    val groupedMode by viewModel.groupedMode.collectAsStateWithLifecycle()
    // 聚合模式下各媒体组的展开状态：key = "${mediaName}-${fullPath}"
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val coroutineScope = rememberCoroutineScope()

    // 已选 group 的 key 集合：由 ViewModel 持有，详情页往返不清空
    val selectedKeys = viewModel.selectedKeys
    var isPreparing by remember { mutableStateOf(false) }

    fun groupKey(g: ResticFileBackupGroup): String = "${g.mediaName}-${g.fullPath}-${g.timestamp}"

    // 同媒体分组维度：(mediaName, fullPath)
    fun appKey(g: ResticFileBackupGroup): String = "${g.mediaName}-${g.fullPath}"

    // 互斥选择：勾选某版本前，先剔除已选集合里同一 (mediaName, fullPath) 的其它版本
    fun selectExclusive(g: ResticFileBackupGroup) {
        val key = groupKey(g)
        val prefix = "${g.mediaName}-${g.fullPath}-"
        selectedKeys.removeAll { it.startsWith(prefix) && it != key }
        if (!selectedKeys.contains(key)) selectedKeys.add(key)
    }

    fun exitSelection() {
        selectedKeys.clear()
    }

    // 仅含 PACKAGE_CONFIG 的 group 可参与批量选择
    fun selectableGroups(): List<ResticFileBackupGroup> =
        (uiState as? ResticFilesRestoreUiState.Success)?.groups
            ?.filter { g -> g.backups.any { it.dataType == DataType.PACKAGE_CONFIG } }
            ?: emptyList()

    // 是否存在同一媒体多个快照版本（决定是否需要提示）
    fun hasMultiSnapshotApp(): Boolean =
        selectableGroups().groupBy { appKey(it) }.values.any { it.size > 1 }

    // 全选：每组取 timestamp 最大的 group
    fun selectAllLatest() {
        selectableGroups().groupBy { appKey(it) }.values.forEach { list ->
            list.maxByOrNull { it.timestamp }?.let { selectExclusive(it) }
        }
    }

    // 反选：该组已有勾选则整组移除，否则勾选该组 timestamp 最大者（保证同媒体互斥）
    fun reverseAllLatest() {
        selectableGroups().groupBy { appKey(it) }.values.forEach { list ->
            val latest = list.maxByOrNull { it.timestamp } ?: return@forEach
            val prefix = "${latest.mediaName}-${latest.fullPath}-"
            if (selectedKeys.any { it.startsWith(prefix) }) {
                selectedKeys.removeAll { it.startsWith(prefix) }
            } else {
                selectedKeys.add(groupKey(latest))
            }
        }
    }

    // 待确认执行的批量操作（有多快照媒体时先弹提示框）
    var pendingBulkAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun runBulk(action: () -> Unit) {
        if (hasMultiSnapshotApp()) pendingBulkAction = action else action()
    }

    LaunchedEffect(Unit) {
        viewModel.loadBackedUpFiles()      // 首次/正常进入：守卫生效，秒开
    }

    // 删除返回时的强制刷新信号
    val needsRefresh = navController.currentBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow("restic_needs_refresh", false)
        ?.collectAsStateWithLifecycle()

    LaunchedEffect(needsRefresh?.value) {
        if (needsRefresh?.value == true) {
            viewModel.forceReload()       // 绕过守卫，重列 + 重建缓存
            exitSelection()               // 列表刷新后清空已选，避免残留脏选择
            navController.currentBackStackEntry
                ?.savedStateHandle
                ?.set("restic_needs_refresh", false)  // 复位，避免重复触发
        }
    }

    RestoreScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState()),
        title = if (selectedKeys.isNotEmpty())
            stringResource(R.string.restore_selected_count, selectedKeys.size)
        else
            stringResource(R.string.restore_restic_file_restore_title),
        topBarActions = {
            var checkListExpanded by remember { mutableStateOf(false) }

            // 聚合模式切换：切换 平铺/按媒体分组 两种列表形态
            IconButton(
                icon = if (groupedMode) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore
            ) {
                viewModel.toggleGroupedMode()
            }

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
                        val groups = (uiState as? ResticFilesRestoreUiState.Success)?.groups ?: emptyList()
                        val selectedGroups = groups.filter { selectedKeys.contains(groupKey(it)) }
                        if (selectedGroups.isEmpty()) return@ExtendedFloatingActionButton
                        isPreparing = true
                        coroutineScope.launch {
                            try {
                                // 与 app 恢复侧一致：列表页只做「准备」（解出中转文件 + 刷 DB 激活），
                                // 真正的恢复交给处理页的服务层执行
                                val success = viewModel.prepareBatchRestore(selectedGroups)
                                if (success) {
                                    val backupDir = "${viewModel.readBackupDirectory()}/restore/"
                                    // 多个媒体名以逗号拼接，处理页/服务层按逗号拆分过滤
                                    val mediaNames = selectedGroups.map { it.mediaName }.distinct().joinToString(",")
                                    val route = MainRoutes.MediumRestoreProcessingGraph.getRoute(
                                        cloudName = URLEncoder.encode("", "UTF-8"),
                                        backupDir = URLEncoder.encode(backupDir, "UTF-8"),
                                        mediaName = URLEncoder.encode(mediaNames, "UTF-8")
                                    )
                                    exitSelection()
                                    Log.d("ResticFilesRestorePage", "navigate MediumRestoreProcessingGraph: $route")
                                    navController.navigateSingle(route)
                                } else {
                                    Log.e("ResticFilesRestorePage", "prepareBatchRestore 返回 false，无可恢复项")
                                }
                            } catch (e: Exception) {
                                Log.e("ResticFilesRestorePage", "批量恢复准备异常: ${e.message}", e)
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
                    }
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SizeTokens.Level16)
        ) {
            when (val currentState = uiState) {
                is ResticFilesRestoreUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is ResticFilesRestoreUiState.Success -> {
                    val context = LocalContext.current

                    if (currentState.groups.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            TitleLargeText(text = stringResource(R.string.restore_no_file_backup))
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)
                        ) {
                            if (groupedMode) {
                                // ---- 聚合模式：同一 (mediaName, fullPath) 只显示一行 ----
                                val grouped = currentState.groups
                                    .groupBy { appKey(it) }
                                    .mapValues { entry -> entry.value.sortedByDescending { it.timestamp } }
                                    .values
                                    .sortedByDescending { it.first().timestamp }

                                items(
                                    items = grouped.toList(),
                                    key = { snapshots -> appKey(snapshots.first()) }
                                ) { snapshots ->
                                    val key = appKey(snapshots.first())
                                    val expanded = expandedGroups[key] == true
                                    val hasSelected = snapshots.any { selectedKeys.contains(groupKey(it)) }
                                    ResticFileAppGroupItem(
                                        snapshots = snapshots,
                                        expanded = expanded,
                                        hasSelected = hasSelected,
                                        onToggleExpand = { expandedGroups[key] = !expanded },
                                        context = context,
                                        selectedKeys = selectedKeys,
                                        onSnapshotSelect = { group, checked ->
                                            if (checked) selectExclusive(group)
                                            else selectedKeys.remove(groupKey(group))
                                        },
                                        onSnapshotClick = { group ->
                                            val groupJson = Json.encodeToString(group)
                                            val encodedJson = URLEncoder.encode(groupJson, "UTF-8")
                                            // isOtg 已废弃：详情侧复用 ResticFilesRestoreViewModel，
                                            // 读键统一走全局标记 readResticActiveIsOtg() + resolveRestoreRepo()，
                                            // 该导航参数不再有实际作用，传占位常量 false。
                                            val url = MainRoutes.ResticFilesBackupDetail.getRoute(
                                                groupJsonEncoded = encodedJson,
                                                isOtg = false
                                            )
                                            navController.navigateSingle(url)
                                        }
                                    )
                                }
                            } else {
                                // ---- 平铺模式：每个快照一行 ----
                                items(
                                    items = currentState.groups,
                                    key = { group: ResticFileBackupGroup -> groupKey(group) }
                                ) { group ->
                                    val key = groupKey(group)
                                    val selectable = group.backups.any { it.dataType == DataType.PACKAGE_CONFIG }
                                    ResticFileBackupGroupItem(
                                        group = group,
                                        context = context,
                                        selectable = selectable,
                                        selected = selectedKeys.contains(key),
                                        onSelectedChange = { checked ->
                                            if (!selectable) return@ResticFileBackupGroupItem
                                            if (checked) selectExclusive(group)
                                            else selectedKeys.remove(key)
                                        },
                                        onClick = {
                                            val groupJson = Json.encodeToString(group)
                                            val encodedJson = URLEncoder.encode(groupJson, "UTF-8")
                                            val url = MainRoutes.ResticFilesBackupDetail.getRoute(
                                                groupJsonEncoded = encodedJson,
                                                isOtg = false
                                            )
                                            navController.navigateSingle(url)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                is ResticFilesRestoreUiState.Error -> {
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
                            Button(onClick = { viewModel.loadBackedUpFiles() }) {
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
            text = { Text(stringResource(R.string.restore_multi_snapshot_hint_files)) },
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