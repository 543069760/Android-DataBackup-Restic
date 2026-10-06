package com.xayah.feature.main.restore

import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.DropdownMenuItem
import com.xayah.core.ui.component.IconButton
import com.xayah.core.ui.component.ModalDropdownMenu
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.ui.component.SearchBar
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.DateUtil
import com.xayah.core.util.encodedURLWithSpace
import com.xayah.core.util.navigateSingle
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun ResticRestorePage(
    navController: NavController,
    isOtg: Boolean = false,
    viewModel: ResticRestoreViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val iconVersion by viewModel.iconVersion.collectAsStateWithLifecycle()
    // 列表聚合模式：true = 同 app 只显示一行、点开展示多版本快照；false = 平铺（原行为）
    val groupedMode by viewModel.groupedMode.collectAsStateWithLifecycle()
    // 聚合模式下各 app 组的展开状态：key = "${userId}-${packageName}"
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val coroutineScope = rememberCoroutineScope()

    // 已选 group 的 key 集合：提升为 ViewModel 持有，详情页往返不清空
    val selectedKeys = viewModel.selectedKeys
    var isPreparing by remember { mutableStateOf(false) }

    // 搜索关键字：仅用于过滤可见列表，绝不影响 selectedKeys 与互斥选择逻辑
    var searchText by remember { mutableStateOf("") }

    fun groupKey(g: ResticBackupGroup): String = "${g.userId}-${g.packageName}-${g.timestamp}"

    // 互斥选择：勾选某版本前，先剔除已选集合里同一 (packageName, userId) 的其它版本，再加入当前 key
    // 务必同时比较 packageName 与 userId，避免误伤分身(userId != 0)
    fun selectExclusive(g: ResticBackupGroup) {
        val key = groupKey(g)
        // 剔除同一 (packageName, userId) 的其它已选版本（同 packageName 同 userId 但不同 timestamp）
        val prefix = "${g.userId}-${g.packageName}-"
        selectedKeys.removeAll { it.startsWith(prefix) && it != key }
        if (!selectedKeys.contains(key)) selectedKeys.add(key)
    }

    fun exitSelection() {
        selectedKeys.clear()
    }

    // ---- 批量选择（全选/反选），与 selectExclusive 保持同一互斥语义 ----

    // 同 app 分组维度：(userId, packageName)
    fun appKey(g: ResticBackupGroup) = "${g.userId}-${g.packageName}"

    // 仅含 PACKAGE_CONFIG 的 group 可参与批量选择
    fun selectableGroups(): List<ResticBackupGroup> =
        (uiState as? ResticRestoreUiState.Success)?.groups
            ?.filter { g -> g.backups.any { it.dataType == DataType.PACKAGE_CONFIG } }
            ?: emptyList()

    // 是否存在同一 app 多个快照版本（决定是否需要提示）
    fun hasMultiSnapshotApp(): Boolean =
        selectableGroups().groupBy { appKey(it) }.values.any { it.size > 1 }

    // 全选：每组取 timestamp 最大的 group
    fun selectAllLatest() {
        selectableGroups().groupBy { appKey(it) }.values.forEach { list ->
            list.maxByOrNull { it.timestamp }?.let { selectExclusive(it) }
        }
    }

    // 反选：该组已有勾选则整组移除，否则勾选该组 timestamp 最大者（保证同 app 互斥）
    fun reverseAllLatest() {
        selectableGroups().groupBy { appKey(it) }.values.forEach { list ->
            val latest = list.maxByOrNull { it.timestamp } ?: return@forEach
            val prefix = "${latest.userId}-${latest.packageName}-"
            if (selectedKeys.any { it.startsWith(prefix) }) {
                selectedKeys.removeAll { it.startsWith(prefix) }
            } else {
                selectedKeys.add(groupKey(latest))
            }
        }
    }

    // 待确认执行的批量操作（有多快照 app 时先弹提示框）
    var pendingBulkAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun runBulk(action: () -> Unit) {
        if (hasMultiSnapshotApp()) pendingBulkAction = action else action()
    }

    LaunchedEffect(Unit) {
        viewModel.loadBackedUpApps()      // 首次/正常进入：守卫生效，秒开
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
            stringResource(R.string.restore_restic_restore_title),
        topBarActions = {
            var checkListExpanded by remember { mutableStateOf(false) }

            // 聚合模式切换：在多选按钮旁，切换 平铺/按 app 分组 两种列表形态
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
                        val groups = (uiState as? ResticRestoreUiState.Success)?.groups ?: emptyList()
                        val selectedGroups = groups.filter { selectedKeys.contains(groupKey(it)) }
                        if (selectedGroups.isEmpty()) return@ExtendedFloatingActionButton
                        isPreparing = true
                        coroutineScope.launch {
                            try {
                                // 仅写队列 + 解 config + 刷 DB（重型 tar 交给服务层解出）
                                val success = viewModel.prepareBatchRestore(selectedGroups)
                                if (success) {
                                    val backupDir = "${viewModel.readBackupDirectory()}/restore/"
                                    val route = MainRoutes.PackagesRestoreProcessingGraph.getRoute(
                                        cloudName = encodedURLWithSpace,
                                        backupDir = URLEncoder.encode(backupDir, "UTF-8"),
                                        packageName = "",
                                        isOtg = isOtg
                                    )
                                    exitSelection()
                                    navController.navigateSingle(route)
                                } else {
                                    Log.e("ResticRestorePage", "prepareBatchRestore 返回 false，无可恢复项")
                                }
                            } catch (e: Exception) {
                                Log.e("ResticRestorePage", "批量恢复准备异常: ${e.message}", e)
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
                is ResticRestoreUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is ResticRestoreUiState.Success -> {
                    if (currentState.groups.isEmpty()) {
                        // 原始数据为空：无任何备份
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            TitleLargeText(text = stringResource(R.string.restore_no_backup))
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
                                .padding(bottom = SizeTokens.Level12),
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
                                if (groupedMode) {
                                    // ---- 聚合模式：同一 (userId, packageName) 只显示一行 ----
                                    val grouped = filteredGroups
                                        .groupBy { "${it.userId}-${it.packageName}" }
                                        .mapValues { entry -> entry.value.sortedByDescending { it.timestamp } }
                                        .values
                                        .sortedByDescending { it.first().timestamp }

                                    items(
                                        items = grouped.toList(),
                                        key = { snapshots -> "${snapshots.first().userId}-${snapshots.first().packageName}" }
                                    ) { snapshots ->
                                        val appGroupKey = "${snapshots.first().userId}-${snapshots.first().packageName}"
                                        val expanded = expandedGroups[appGroupKey] == true
                                        val hasSelected = snapshots.any {
                                            selectedKeys.contains(groupKey(it))
                                        }
                                        ResticAppGroupItem(
                                            snapshots = snapshots,
                                            expanded = expanded,
                                            hasSelected = hasSelected,
                                            onToggleExpand = {
                                                expandedGroups[appGroupKey] = !expanded
                                            },
                                            context = LocalContext.current,
                                            accountId = "local",
                                            iconVersion = iconVersion,
                                            selectedKeys = selectedKeys,
                                            onSnapshotSelect = label@ { group, checked ->
                                                val selectable = group.backups.any { it.dataType == DataType.PACKAGE_CONFIG }
                                                if (!selectable) return@label
                                                if (checked) selectExclusive(group) else selectedKeys.remove(groupKey(group))
                                            },
                                            onSnapshotClick = { group ->
                                                val groupJson = Json.encodeToString(group)
                                                val encodedJson = URLEncoder.encode(groupJson, "UTF-8")
                                                val url = MainRoutes.ResticBackupDetail.getRoute(groupJsonEncoded = encodedJson)
                                                navController.navigateSingle(url)
                                            }
                                        )
                                    }
                                } else {
                                    // ---- 平铺模式：原行为，每个快照一行 ----
                                    items(
                                        filteredGroups,
                                        key = { item: ResticBackupGroup -> "${item.userId}-${item.packageName}-${item.timestamp}" }
                                    ) { group: ResticBackupGroup ->
                                        val key = groupKey(group)
                                        val selectable = group.backups.any { it.dataType == DataType.PACKAGE_CONFIG }
                                        ResticBackupGroupItem(
                                            group = group,
                                            selectable = selectable,
                                            selected = selectedKeys.contains(key),
                                            onSelectedChange = { checked ->
                                                // 仅可选（含 config）的分组可切换
                                                if (!selectable) return@ResticBackupGroupItem
                                                if (checked) {
                                                    // 互斥：同一 (packageName, userId) 只保留当前版本
                                                    selectExclusive(group)
                                                } else {
                                                    selectedKeys.remove(key)
                                                }
                                            },
                                            onClick = {
                                                // 行点击统一进入详情页（勾选交给右侧复选框）
                                                val groupJson = Json.encodeToString(group)
                                                Log.d("ResticRestorePage", "Navigating with groupJson: $groupJson")
                                                val encodedJson = URLEncoder.encode(groupJson, "UTF-8")
                                                val url = MainRoutes.ResticBackupDetail.getRoute(groupJsonEncoded = encodedJson)
                                                Log.d("ResticRestorePage", "Full URL: $url")
                                                navController.navigateSingle(url)
                                            },
                                            context = LocalContext.current,
                                            accountId = "local",
                                            iconVersion = iconVersion            // 新增：透传图标版本
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                is ResticRestoreUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)
                        ) {
                            TitleLargeText(text = stringResource(R.string.restore_load_failed))
                            BodyMediumText(text = currentState.message)
                            Button(onClick = { viewModel.loadBackedUpApps() }) {
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ResticBackupGroupItem(
    group: ResticBackupGroup,
    onClick: () -> Unit,
    context: Context,
    accountId: String? = null,
    iconVersion: Int = 0,                // 默认 0，兼容其它调用点
    selectable: Boolean = true,          // 是否可勾选（无 config 则不可）
    selected: Boolean = false,           // 当前是否已选
    onSelectedChange: (Boolean) -> Unit = {}   // 勾选切换回调
) {
    val hasConfigSnapshot = group.backups.any { it.dataType == DataType.PACKAGE_CONFIG }

    Surface(
        modifier = Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick     // 仅保留：点击整行导航到详情页
        )
    ) {
        Row(
            modifier = Modifier
                .padding(SizeTokens.Level16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
        ) {
            PackageIconImage(
                packageName = group.packageName,
                size = SizeTokens.Level32,
                accountId = accountId,
                iconVersion = iconVersion         // 新增：透传，触发解压后重新取图
            )

            Column(modifier = Modifier.weight(1f)) {
                TitleLargeText(
                    text = group.appLabel,
                    maxLines = 1
                )

                // 胶囊行：仅在分身或备份不完整时占位，插在名称与包名之间
                // 胶囊区：仅在分身或备份不完整时占位，插在名称与包名之间。
                // FlowRow 在空间不足时自动折行（英文长文案场景），不会让胶囊被裁剪。
                if (group.userId != 0 || !hasConfigSnapshot) {
                    FlowRow(
                        modifier = Modifier.padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (group.userId != 0) {
                            // 分身标识胶囊
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = stringResource(R.string.restore_clone_app),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            // 用户 ID 胶囊（按 userId 着色）
                            val (chipBg, chipFg) = userChipColors(group.userId)
                            Surface(
                                color = chipBg,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = stringResource(R.string.restore_user_id, group.userId),
                                    color = chipFg,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (!hasConfigSnapshot) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                )
                            ) {
                                Text(
                                    text = stringResource(R.string.restore_backup_incomplete),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                BodyMediumText(
                    text = group.packageName,
                    color = ThemedColorSchemeKeyTokens.Outline.value,
                    maxLines = 1
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

            // 复选框常驻右侧，前置分隔线；尾部结构与 ResticAppGroupItem 的箭头区对齐
            VerticalDivider(
                modifier = Modifier.height(SizeTokens.Level32)
            )
            Box(
                modifier = Modifier.size(SizeTokens.Level36),
                contentAlignment = Alignment.Center
            ) {
                Checkbox(
                    checked = selected && selectable,
                    onCheckedChange = { checked ->
                        if (selectable) onSelectedChange(checked)
                    },
                    enabled = selectable
                )
            }
        }
    }
}

/**
 * 聚合模式的 app 组行：同一 (userId, packageName) 的所有快照收敛为一行，
 * 点击行右侧箭头展开/收起；展开区域按 timestamp 降序列出所有快照（复用 ResticBackupGroupItem）。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ResticAppGroupItem(
    snapshots: List<ResticBackupGroup>,
    expanded: Boolean,
    hasSelected: Boolean,
    onToggleExpand: () -> Unit,
    context: Context,
    accountId: String? = null,
    iconVersion: Int = 0,
    selectedKeys: List<String>,
    onSnapshotSelect: (ResticBackupGroup, Boolean) -> Unit,
    onSnapshotClick: (ResticBackupGroup) -> Unit,
) {
    val latest = snapshots.first()
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f)

    Column {
        Surface(
            modifier = Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleExpand
            )
        ) {
            Row(
                modifier = Modifier
                    .padding(SizeTokens.Level16),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
            ) {
                PackageIconImage(
                    packageName = latest.packageName,
                    size = SizeTokens.Level32,
                    accountId = accountId,
                    iconVersion = iconVersion
                )

                Column(modifier = Modifier.weight(1f)) {
                    TitleLargeText(
                        text = latest.appLabel,
                        maxLines = 1
                    )

                    // 胶囊行（独立一行）：分身胶囊 + 用户ID胶囊（userId != 0 时），
                    // 以及"备份不完整"胶囊（该 app 全部快照都无 PACKAGE_CONFIG 时）
                    val userId = latest.userId
                    val allIncomplete = snapshots.none { g ->
                        g.backups.any { it.dataType == DataType.PACKAGE_CONFIG }
                    }
                    if (userId != 0 || allIncomplete) {
                        FlowRow(
                            modifier = Modifier.padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (userId != 0) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        text = stringResource(R.string.restore_clone_app),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(
                                            horizontal = 8.dp,
                                            vertical = 2.dp
                                        )
                                    )
                                }
                                // 用户 ID 胶囊：颜色随 userId 在 M3 容器色中轮换
                                val (chipBg, chipFg) = userChipColors(userId)
                                Surface(
                                    color = chipBg,
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        text = stringResource(R.string.restore_user_id, userId),
                                        color = chipFg,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(
                                            horizontal = 8.dp,
                                            vertical = 2.dp
                                        )
                                    )
                                }
                            }
                            if (allIncomplete) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        text = stringResource(R.string.restore_backup_incomplete),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(
                                            horizontal = 8.dp,
                                            vertical = 2.dp
                                        )
                                    )
                                }
                            }
                        }
                    }

                    BodyMediumText(
                        text = latest.packageName,
                        color = ThemedColorSchemeKeyTokens.Outline.value,
                        maxLines = 1
                    )

                    BodyMediumText(
                        text = DateUtil.formatTimestamp(
                            latest.timestamp,
                            DateUtil.PATTERN_YMD_HMS
                        ),
                        color = ThemedColorSchemeKeyTokens.Outline.value,
                        maxLines = 1
                    )

                    Text(
                        text = stringResource(R.string.restore_group_backup_count, snapshots.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                VerticalDivider(modifier = Modifier.height(SizeTokens.Level32))
                Box(
                    modifier = Modifier.size(SizeTokens.Level36),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.rotate(arrowRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 展开的快照列表：最新在上
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)) {
                snapshots.forEach { group ->
                    val key = "${group.userId}-${group.packageName}-${group.timestamp}"
                    val selectable = group.backups.any { it.dataType == DataType.PACKAGE_CONFIG }
                    ResticBackupGroupItem(
                        group = group,
                        selectable = selectable,
                        selected = selectedKeys.contains(key),
                        onSelectedChange = { checked -> onSnapshotSelect(group, checked) },
                        onClick = { onSnapshotClick(group) },
                        context = context,
                        accountId = accountId,
                        iconVersion = iconVersion
                    )
                }
            }
        }
    }
}
    /**
     * 分身用户 ID 胶囊配色：按 userId 从 M3 动态配色的容器色中轮换取色。
     * 同一 userId 恒同色；颜色全部来自 colorScheme，随 Material You 动态主题/深浅色自动适配。
     */
@Composable
fun userChipColors(userId: Int): Pair<Color, Color> {
        val cs = MaterialTheme.colorScheme
        // M3/M3E 容器色轮换池：色相差异尽量大，相邻 ID 颜色明显区分
        val palette = listOf(
            cs.primaryContainer to cs.onPrimaryContainer,
            cs.secondaryContainer to cs.onSecondaryContainer,
            cs.tertiaryContainer to cs.onTertiaryContainer,
            cs.errorContainer to cs.onErrorContainer,
            cs.surfaceContainerHighest to cs.onSurface,
            cs.surfaceVariant to cs.onSurfaceVariant,
        )
        return palette[(userId.toLong() and Int.MAX_VALUE.toLong()).toInt() % palette.size]
    }