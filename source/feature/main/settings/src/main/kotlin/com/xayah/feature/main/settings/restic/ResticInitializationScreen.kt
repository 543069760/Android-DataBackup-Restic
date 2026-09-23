package com.xayah.feature.main.settings.restic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
    val initializationState by viewModel.initializationState.collectAsStateWithLifecycle()
    val resticInitialized by viewModel.resticInitializedState.collectAsStateWithLifecycle(initialValue = false)
    val repoPath by viewModel.repoPathState.collectAsStateWithLifecycle()
    // 纯 UI 状态：是否处于“重新初始化”模式（不触碰持久化数据）
    val isReinitializing by viewModel.isReinitializing.collectAsStateWithLifecycle(initialValue = false)

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

    // 按 OTG/本地分别构造目录选择器
    val directoryLauncher = if (isOtg) {
        // OTG 场景：起始/根路径放开到 /mnt/media_rw，用户在该目录下选择 <UUID> 子目录
        PickYouLauncher(
            checkPermission = false,
            title = stringResource(id = R.string.select_directory),
            pickerType = PickerType.DIRECTORY,
            permissionType = PermissionType.ROOT,
            rootPathList = listOf("/mnt/media_rw"),
            defaultPathList = listOf("/mnt/media_rw"),
        )
    } else {
        // 本地场景：完全保持原样，起始路径不变
        PickYouLauncher(
            checkPermission = false,
            title = stringResource(id = R.string.select_directory),
            pickerType = PickerType.DIRECTORY,
            permissionType = PermissionType.ROOT,
        )
    }

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

@OptIn(ExperimentalMaterial3Api::class)
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

@OptIn(ExperimentalMaterial3Api::class)
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

            // 文件选择器
            Button(
                onClick = {
                    directoryLauncher.launch(context) { pathString ->
                        onPathSelected(pathString)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = initializationState !is ResticViewModel.InitializationState.Checking &&
                        initializationState !is ResticViewModel.InitializationState.Validating &&
                        initializationState !is ResticViewModel.InitializationState.Initializing
            ) {
                Text(stringResource(id = R.string.select_directory))
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
                enabled = selectedPath.isNotEmpty() &&
                        initializationState !is ResticViewModel.InitializationState.Checking &&
                        initializationState !is ResticViewModel.InitializationState.Validating &&
                        initializationState !is ResticViewModel.InitializationState.Initializing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(id = R.string.initialize))
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