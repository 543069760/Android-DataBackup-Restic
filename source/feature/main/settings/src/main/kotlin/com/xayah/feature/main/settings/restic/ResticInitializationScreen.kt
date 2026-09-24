package com.xayah.feature.main.settings.restic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.xayah.core.ui.theme.value
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
fun ResticInitializationScreen(isOtg: Boolean = false) {
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

    var selectedPath by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
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

    if (resticInitialized && repoPath != null && !isReinitializing) {
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
            isOtg = isOtg,
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
                                                partition.fsType
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
        }
    }

    // 删除确认对话框
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
                        viewModel.launchOnIO {
                            viewModel.deleteAndReinitializeRepository(repoPathToDelete)
                        }
                        onDeleteDialogChange(false)
                    }
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