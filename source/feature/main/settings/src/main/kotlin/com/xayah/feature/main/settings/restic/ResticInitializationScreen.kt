package com.xayah.feature.main.settings.restic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.BodySmallText
import com.xayah.core.ui.component.Card
import com.xayah.core.ui.component.LabelSmallText
import com.xayah.core.ui.material3.CardDefaults
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.model.util.formatSize
import com.xayah.libpickyou.PickYouLauncher
import com.xayah.libpickyou.ui.model.PickerType
import com.xayah.libpickyou.ui.model.PermissionType
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.settings.R
import java.io.File

@ExperimentalMaterial3Api
@Composable
fun ResticInitializationScreen(isOtg: Boolean = false, reinitialize: Boolean = false) {
    val viewModel = hiltViewModel<ResticViewModel>()
    val navController = LocalNavController.current!!
    val context = LocalContext.current

    // 按 isOtg 选择状态流：OTG 走 ViewModel 已提供的独立状态流，本地保持原状态流（零回归）
    val localInitializationState by viewModel.initializationState.collectAsStateWithLifecycle()
    val otgInitializationState by viewModel.otgInitializationState.collectAsStateWithLifecycle()
    val localResticInitialized by viewModel.resticInitializedState.collectAsStateWithLifecycle(initialValue = false)
    val otgResticInitialized by viewModel.otgInitializedState.collectAsStateWithLifecycle(initialValue = false)
    val localRepoPath by viewModel.repoPathState.collectAsStateWithLifecycle()
    val otgRepoPath by viewModel.otgRepoPathState.collectAsStateWithLifecycle()

    val initializationState = if (isOtg) otgInitializationState else localInitializationState
    val resticInitialized = if (isOtg) otgResticInitialized else localResticInitialized
    // 注意：otgRepoPathState 是非空 String（空串=未登记），本地 repoPathState 是 String?；
    //       统一收敛为 String?，把空串视作「未初始化」，保持下方 repoPath != null 判定语义一致
    val repoPath: String? = if (isOtg) otgRepoPath.ifEmpty { null } else localRepoPath

    // 纯 UI 状态：是否处于“重新初始化”模式（不触碰持久化数据）
    val isReinitializing by viewModel.isReinitializing.collectAsStateWithLifecycle(initialValue = false)

    // 进入 OTG 界面时触发一次 OTG 刷新，确保直接进入该页也能读到最新 OTG 状态
    LaunchedEffect(isOtg) {
        if (isOtg) viewModel.refreshOtgStatus()
    }

    // 从设置页/存储页跳转进来并要求“重新初始化”时，本页 ViewModel 是新实例，
    // 必须由路由参数在本页内部切到「选择目录」流程（与初次引导第二步完全一致）。
    LaunchedEffect(reinitialize) {
        if (reinitialize) viewModel.enterReinitializeMode()
    }

    var selectedPath by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    // 二次确认：真正的删除动作需要再确认一次，避免误触直接清库
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var repoPathToDelete by remember { mutableStateOf("") }

    var wasReinitializing by remember { mutableStateOf(false) }
    LaunchedEffect(isReinitializing) {
        if (isReinitializing) {
            wasReinitializing = true
        }
    }

    LaunchedEffect(initializationState) {
        if (initializationState is ResticViewModel.InitializationState.ReadyToUse && wasReinitializing) {
            wasReinitializing = false
            navController.popBackStack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.exitReinitializeMode()
        }
    }

    // 本地目录选择器：完全保持原样（零回归）。OTG 场景改为按分区卡片各自构造 launcher，不用这个。
    val directoryLauncher = PickYouLauncher(
        checkPermission = false,
        title = stringResource(id = R.string.select_directory),
        pickerType = PickerType.DIRECTORY,
        permissionType = PermissionType.ROOT,
    )

    // 带 reinitialize 参数进来时，不依赖 ViewModel 的标志位（首帧就可能仍显示已初始化页），
    // 直接由路由参数判定，保证「点胶囊 = 进入一整套选择仓库操作」。
    if (resticInitialized && repoPath != null && !isReinitializing && !reinitialize) {
        // 已初始化状态：显示当前信息和重新初始化按钮
        InitializedView(
            repoPath = repoPath!!,
            onReinitialize = {
                viewModel.enterReinitializeMode()
            }
        )
    } else {
        // 未初始化状态（或处于重新初始化模式）：显示初始化界面
        InitializationView(
            viewModel = viewModel,
            initializationState = initializationState,
            selectedPath = selectedPath,
            onPathSelected = { selectedPath = it },
            directoryLauncher = directoryLauncher,
            showDeleteDialog = showDeleteDialog,
            repoPathToDelete = repoPathToDelete,
            onDeleteDialogChange = { showDeleteDialog = it },
            onRepoPathToDeleteChange = { repoPathToDelete = it },
            showDeleteConfirmDialog = showDeleteConfirmDialog,
            onDeleteConfirmDialogChange = { showDeleteConfirmDialog = it },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun InitializedView(
    repoPath: String,
    onReinitialize: () -> Unit
) {
    val navController = LocalNavController.current!!

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.initialize_restic)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(stringResource(R.string.restic_initialized))
            Text(stringResource(R.string.repository_path, repoPath))

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onReinitialize,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.reinitialize))
            }

            // 重新初始化按钮下方：与「选择目录」UI 同一组说明卡片
            Spacer(modifier = Modifier.height(16.dp))
            ResticRepoGuideCards()
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun InitializationView(
    viewModel: ResticViewModel,
    initializationState: ResticViewModel.InitializationState,
    selectedPath: String,
    onPathSelected: (String) -> Unit,
    directoryLauncher: PickYouLauncher,
    showDeleteDialog: Boolean,
    repoPathToDelete: String,
    onDeleteDialogChange: (Boolean) -> Unit,
    onRepoPathToDeleteChange: (String) -> Unit,
    showDeleteConfirmDialog: Boolean,
    onDeleteConfirmDialogChange: (Boolean) -> Unit,
    isOtg: Boolean = false,
) {
    val context = LocalContext.current
    val navController = LocalNavController.current!!

    // 选目录器标题（在 composable 作用域取好，供 OTG 分区卡片点击时构造 launcher 使用）
    val directoryTitle = stringResource(id = R.string.select_directory)

    // OTG 分区列表：进入界面时异步加载
    var otgPartitions by remember { mutableStateOf<List<OtgPartition>>(emptyList()) }
    LaunchedEffect(isOtg) {
        if (isOtg) otgPartitions = viewModel.listOtgPartitions()
    }

    // OTG 模式下预览路径需异步解析（读取挂载点补全 UUID），用状态承接
    var resolvedRepoPath by remember { mutableStateOf("") }
    LaunchedEffect(selectedPath, isOtg) {
        resolvedRepoPath = when {
            selectedPath.isEmpty() -> ""
            isOtg -> viewModel.resolveOtgRepoParent(selectedPath)
                ?.let { File(it, "restic_repo").absolutePath } ?: ""
            else -> File(selectedPath, "restic_repo").absolutePath
        }
    }

    val pickerBusy = initializationState is ResticViewModel.InitializationState.Checking ||
            initializationState is ResticViewModel.InitializationState.Validating ||
            initializationState is ResticViewModel.InitializationState.Initializing

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.initialize_restic)) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.exitReinitializeMode()
                        navController.popBackStack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 状态显示
            when (val state = initializationState) {
                is ResticViewModel.InitializationState.Checking -> {
                    Text(stringResource(id = R.string.checking_path))
                }
                is ResticViewModel.InitializationState.Validating -> {
                    Text(stringResource(id = R.string.validating_repo_password))
                }
                is ResticViewModel.InitializationState.Initializing -> {
                    Text(stringResource(id = R.string.initializing_repository))
                }
                is ResticViewModel.InitializationState.Error -> {
                    Text(
                        text = stringResource(id = R.string.error_with_message, state.message),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {}
            }

            if (isOtg) {
                // ===== OTG：先用卡片选分区，点卡片后进入该分区内部的 libpickyou =====
                Text(
                    text = stringResource(id = R.string.otg_select_partition),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (otgPartitions.isEmpty()) {
                    // 空态：未检测到 OTG 存储
                    Text(
                        text = stringResource(id = R.string.otg_no_storage_detected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        otgPartitions.forEach { partition ->
                            ElevatedCard(
                                onClick = {
                                    // 以该分区路径为起始/边界锁定，一进 libpickyou 就在分区内部，
                                    // 无法上溯到 /mnt/media_rw 或 /
                                    val launcher = PickYouLauncher(
                                        checkPermission = false,
                                        title = directoryTitle,
                                        pickerType = PickerType.DIRECTORY,
                                        permissionType = PermissionType.ROOT,
                                        rootPathList = listOf(partition.path),
                                        defaultPathList = listOf(partition.path),
                                        // 用主工程 root 通道列目录，绕过 libpickyou binder 进程命名空间问题
                                        traverseBackend = { path -> viewModel.listOtgChildren(path) },
                                        // 同理用 root 通道创建目录，否则 checkPermission=false → sIsRootMode=false 会落到进程内无 root 的 PathUtil.mkdirs 而失败
                                        mkdirsBackend = { parent, child -> viewModel.mkdirsOtg(parent, child) },
                                    )
                                    launcher.launch(context) { pathString ->
                                        onPathSelected(pathString)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !pickerBusy
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = partition.uuid,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(
                                            id = R.string.otg_partition_capacity,
                                            partition.availableBytes.toDouble().formatSize(),
                                            partition.totalBytes.toDouble().formatSize()
                                        ),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (partition.fsType != null) {
                                        Text(
                                            text = stringResource(
                                                id = R.string.otg_partition_fs_type,
                                                partition.fsType.uppercase()
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // ===== 本地：保持原「选择目录」按钮完全不变（零回归） =====
                Button(
                    onClick = {
                        directoryLauncher.launch(context) { pathString ->
                            onPathSelected(pathString)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !pickerBusy
                ) {
                    Text(stringResource(id = R.string.select_directory))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 显示将要创建的仓库路径（OTG 模式下已补全 UUID）
            if (resolvedRepoPath.isNotEmpty()) {
                Text(
                    text = stringResource(id = R.string.restic_repo_will_be_created_at, resolvedRepoPath),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 初始化按钮
            Button(
                onClick = {
                    if (selectedPath.isNotEmpty()) {
                        viewModel.launchOnIO {
                            val parent = if (isOtg) {
                                viewModel.resolveOtgRepoParent(selectedPath)   // → /mnt/media_rw/<UUID>
                            } else {
                                selectedPath
                            }
                            if (!parent.isNullOrEmpty()) {
                                viewModel.initializeOrValidateRepository(parent)  // 内部再拼 restic_repo
                            }
                            // parent 为空表示未插盘/取不到 UUID：可在 ViewModel 侧置 Error 状态提示（TODO#3）
                        }
                    }
                },
                enabled = selectedPath.isNotEmpty() && !pickerBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(id = R.string.initialize))
            }
            if (isOtg) {
                Spacer(modifier = Modifier.height(16.dp))
                com.xayah.core.ui.component.Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = com.xayah.core.ui.material3.CardDefaults.cardColors(
                        containerColor = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.PrimaryContainer.value
                    ),
                ) {
                    Column(modifier = Modifier.padding(com.xayah.core.ui.token.SizeTokens.Level16)) {
                        Text(
                            text = stringResource(id = R.string.otg_storage_tips_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.OnPrimaryContainer.value
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        com.xayah.core.ui.component.BodyMediumText(
                            text = stringResource(id = R.string.otg_storage_tips_content),
                            color = com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens.OnPrimaryContainer.value
                        )
                    }
                }
            }

            // 说明卡片：最上方为密码提示，其后是场景说明（含可复制默认值）与注意事项
            Spacer(modifier = Modifier.height(16.dp))
            ResticRepoGuideCards()
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 第一层：密码校验失败提示，点「删除并重新初始化」不再直接执行，而是转入二次确认
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { onDeleteDialogChange(false) },
            title = { Text(stringResource(id = R.string.delete_existing_repository)) },
            text = {
                Text(stringResource(id = R.string.repo_password_validation_failed, repoPathToDelete))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteDialogChange(false)
                        onDeleteConfirmDialogChange(true)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(id = R.string.delete_and_reinitialize))
                }
            },
            dismissButton = {
                TextButton(onClick = { onDeleteDialogChange(false) }) {
                    Text(stringResource(id = R.string.cancel))
                }
            }
        )
    }

    // 第二层：真正的删除确认。按 M3E 危险操作样式 —— 红色「确认删除」在左，「取消」在右（与常规相反，避免误点）
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { onDeleteConfirmDialogChange(false) },
            title = { Text(stringResource(id = R.string.restic_confirm_delete_title)) },
            text = {
                Text(stringResource(id = R.string.restic_confirm_delete_message, repoPathToDelete))
            },
            // 交换槽位来实现左右互换：AlertDialog 固定 dismiss 在左、confirm 在右
            confirmButton = {
                TextButton(onClick = { onDeleteConfirmDialogChange(false) }) {
                    Text(stringResource(id = R.string.cancel))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onDeleteConfirmDialogChange(false)
                        viewModel.launchOnIO {
                            viewModel.deleteAndReinitializeRepository(repoPathToDelete)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(id = R.string.restic_confirm_delete_action))
                }
            }
        )
    }

    // 监听密码错误状态
    LaunchedEffect(initializationState) {
        when (val state = initializationState) {
            is ResticViewModel.InitializationState.PasswordError -> {
                onRepoPathToDeleteChange(state.repoPath)
                onDeleteDialogChange(true)
            }
            else -> {}
        }
    }
}

/**
 * 说明卡片组：密码提示（最上方）→ 场景说明（含可复制默认值）→ 注意事项。
 * 「选择目录」UI 与「已初始化 / 重新初始化」UI 复用同一组，保持提示一致。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ResticRepoGuideCards() {
    ResticPasswordFixHintCard()
    Spacer(modifier = Modifier.height(12.dp))
    ResticRepoSceneCard()
    Spacer(modifier = Modifier.height(12.dp))
    ResticRepoCautionCard()
}

/**
 * 场景说明卡片：讲清「全新仓库」与「已有仓库（旧设备复制过来）」两种场景，
 * 并单独列出默认仓库路径 / 默认密码供核对与复制。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ResticRepoSceneCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = ThemedColorSchemeKeyTokens.BluePrimaryContainer.value
        ),
    ) {
        Column(modifier = Modifier.padding(SizeTokens.Level16)) {
            BodyMediumText(
                text = stringResource(id = R.string.restic_repo_scene_note),
                color = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value
            )
            BodySmallText(
                modifier = Modifier.padding(top = SizeTokens.Level12),
                text = stringResource(id = R.string.restic_repo_default_values_note),
                color = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value
            )
            CopyableValueRow(
                modifier = Modifier.padding(top = SizeTokens.Level12),
                label = stringResource(id = R.string.restic_default_repo_path),
                value = ConstantUtil.DEFAULT_RUSTIC_REPO_ROOT + "/restic_repo",
                contentColor = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value,
            )
            CopyableValueRow(
                modifier = Modifier.padding(top = SizeTokens.Level4),
                label = stringResource(id = R.string.restic_default_password),
                value = ResticViewModel.DEFAULT_RESTIC_PASSWORD,
                contentColor = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value,
            )
        }
    }
}

/**
 * 密码处理提示卡片：选仓库校验失败时，先回设置页改对密码保存，再回来选目录初始化。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ResticPasswordFixHintCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = ThemedColorSchemeKeyTokens.BluePrimaryContainer.value
        ),
    ) {
        BodyMediumText(
            modifier = Modifier.padding(SizeTokens.Level16),
            text = stringResource(id = R.string.restic_password_fix_hint),
            color = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value
        )
    }
}

/**
 * 注意事项卡片：密码一致性、完整复制、目录选错会新建空仓库等风险提示。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ResticRepoCautionCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = ThemedColorSchemeKeyTokens.YellowPrimaryContainer.value
        ),
    ) {
        BodyMediumText(
            modifier = Modifier.padding(SizeTokens.Level16),
            text = stringResource(id = R.string.restic_repo_caution_note),
            color = ThemedColorSchemeKeyTokens.YellowOnPrimaryContainer.value
        )
    }
}

/**
 * M3E 风格的可复制数值行：小号标签 + 可长按选中的正文 + 右侧复制按钮。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CopyableValueRow(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    contentColor: Color,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LabelSmallText(text = label, color = contentColor)
            SelectionContainer {
                BodyMediumText(text = value, color = contentColor)
            }
        }
        Spacer(modifier = Modifier.width(SizeTokens.Level4))
        IconButton(onClick = { copyToClipboard(context = context, label = label, value = value) }) {
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = stringResource(id = R.string.copy),
                tint = contentColor,
            )
        }
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboardManager.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}