package com.xayah.feature.main.settings.restic

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xayah.core.ui.util.LocalNavController
import com.xayah.feature.main.settings.R
import com.xayah.libpickyou.PickYouLauncher
import com.xayah.libpickyou.ui.model.PickerType
import com.xayah.libpickyou.ui.model.PermissionType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResticRepoPathScreen() {
    val context = LocalContext.current
    val navController = LocalNavController.current!!
    val viewModel = hiltViewModel<ResticViewModel>()
    val coroutineScope = rememberCoroutineScope()

    var repoPath by remember { mutableStateOf("") }

    // --- Root 目录选择器（libpickyou）---
    // 与 ResticInitializationScreen 中已稳定运行的用法保持一致，
    // 通过 root 直接遍历文件系统，避免 SAF Uri→路径转换在不同 ROM 下崩溃。
    val directoryLauncher = PickYouLauncher(
        checkPermission = true,
        title = stringResource(id = R.string.select_directory),
        pickerType = PickerType.DIRECTORY,
        permissionType = PermissionType.ROOT,
    )

    LaunchedEffect(Unit) {
        repoPath = viewModel.getRepoPath()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.restic_repo_path)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                // 保存路径，内部会触发 libsu 的权限准备
                                viewModel.saveRepoPath(repoPath)
                                navController.navigateUp()
                            }
                        }
                    ) {
                        Text(stringResource(id = R.string.save))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = repoPath,
                onValueChange = { repoPath = it },
                label = { Text(stringResource(id = R.string.restic_repo_path)) },
                placeholder = { Text("/sdcard/restic_repo") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                trailingIcon = {
                    // 使用 libpickyou 的 root 目录选择器，回调直接返回物理路径
                    IconButton(onClick = {
                        directoryLauncher.launch(context) { pathString ->
                            repoPath = pathString
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = "Select Folder",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                singleLine = true
            )

            Text(
                text = stringResource(id = R.string.restic_repo_path_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 针对 Root 用户的提示
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "提示：点击图标可直接浏览包括 Root 目录（如 /data/adb）在内的路径；也可手动输入。保存后程序将自动尝试修复路径权限。",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}