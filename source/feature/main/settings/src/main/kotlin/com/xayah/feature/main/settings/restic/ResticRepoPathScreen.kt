package com.xayah.feature.main.settings.restic

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResticRepoPathScreen() {
    val context = LocalContext.current
    val navController = LocalNavController.current!!
    val viewModel = hiltViewModel<ResticViewModel>()
    val coroutineScope = rememberCoroutineScope()

    var repoPath by remember { mutableStateOf("") }

    // --- SAF 文件夹选择器逻辑 ---
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // 将选择的 Uri 转换为物理路径
            // 注意：getFullPathFromUri 是我们需要在 ViewModel 或工具类中实现的逻辑
            val physicalPath = viewModel.getFullPathFromUri(it)
            if (physicalPath != null) {
                repoPath = physicalPath
            }
        }
    }

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
                    // 添加 SAF 选择按钮
                    IconButton(onClick = { folderLauncher.launch(null) }) {
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
                    text = "提示：普通目录可点击图标选择；Root 目录（如 /data/adb）请手动输入路径。保存后程序将自动尝试修复路径权限。",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}