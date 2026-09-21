package com.xayah.feature.main.settings.restic

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
fun ResticInitializationScreen() {
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

    // 记录本次是否曾进入过“重新初始化”模式。
    // 由于 ViewModel 在成功分支会把 _isReinitializing 置为 false，
    // 且与 ReadyToUse 几乎同时发生，直接读 isReinitializing 可能已为 false，
    // 因此用本地标志捕获“曾经进入过重新初始化”，成功后据此决定是否回退。
    var wasReinitializing by remember { mutableStateOf(false) }
    LaunchedEffect(isReinitializing) {
        if (isReinitializing) {
            wasReinitializing = true
        }
    }

    // 成功后自动回退上一页：仅当此前处于重新初始化模式时才 popBackStack，
    // 避免影响首次引导初始化流程（首次流程不会进入 reinit 模式）。
    LaunchedEffect(initializationState) {
        if (initializationState is ResticViewModel.InitializationState.ReadyToUse && wasReinitializing) {
            wasReinitializing = false
            navController.popBackStack()
        }
    }

    // 离开屏幕时统一重置“重新初始化”模式标志，
    // 确保中途返回后下次进入仍能正确显示已初始化界面且原路径保留。
    DisposableEffect(Unit) {
        onDispose {
            viewModel.exitReinitializeMode()
        }
    }

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
                // 进入“重新初始化”模式：仅切换 UI 显示，不清空已保存的路径/密码
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
            onRepoPathToDeleteChange = { repoPathToDelete = it }
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
    onRepoPathToDeleteChange: (String) -> Unit
) {
    val context = LocalContext.current
    val navController = LocalNavController.current!!

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.initialize_restic)) },
                navigationIcon = {
                    IconButton(onClick = {
                        // 中途返回：先退出“重新初始化”模式再回退，保留原路径
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

            // 显示选中的路径
            if (selectedPath.isNotEmpty()) {
                Text(
                    text = stringResource(id = R.string.restic_repo_will_be_created_at,
                        File(selectedPath, "restic_repo").absolutePath),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 初始化按钮
            Button(
                onClick = {
                    if (selectedPath.isNotEmpty()) {
                        viewModel.launchOnIO {
                            viewModel.initializeOrValidateRepository(selectedPath)
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