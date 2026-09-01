package com.xayah.feature.main.cloud.add

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.database.S3Protocol
import com.xayah.core.model.database.S3NetworkType  // 新增导入 / New import
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.confirm
import com.xayah.core.ui.component.confirmWithInput
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingStart
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.theme.withState
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.feature.main.cloud.AccountSetupScaffold
import com.xayah.feature.main.cloud.R
import com.xayah.feature.main.cloud.SetupTextField

@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageS3Setup() {
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val context = LocalContext.current
    val notSelectedText = stringResource(id = R.string.not_selected)
    val navController = LocalNavController.current!!
    val viewModel = hiltViewModel<IndexViewModel>()
    val s3ViewModel = hiltViewModel<S3ResticViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val scope = rememberCoroutineScope()

    var s3Password by rememberSaveable { mutableStateOf("") }
    var s3PasswordVisible by rememberSaveable { mutableStateOf(false) }

    val s3InitState by s3ViewModel.s3InitializationState.collectAsStateWithLifecycle()
    val s3PasswordState by s3ViewModel.s3PasswordState.collectAsStateWithLifecycle()

    LaunchedEffect(s3PasswordState) {
        s3Password = s3PasswordState
    }
    var name by rememberSaveable { mutableStateOf(uiState.currentName) }
    var remote by rememberSaveable(uiState.cloudEntity) {
        mutableStateOf(
            uiState.cloudEntity?.remote ?: ""
        )
    }
    var region by rememberSaveable(uiState.cloudEntity) {
        mutableStateOf(
            uiState.cloudEntity?.getExtraEntity<S3Extra>()?.region ?: ""
        )
    }
    var accessKeyId by rememberSaveable(uiState.cloudEntity) {
        mutableStateOf(
            uiState.cloudEntity?.user ?: ""
        )
    }
    var secretAccessKey by rememberSaveable(uiState.cloudEntity) {
        mutableStateOf(
            uiState.cloudEntity?.pass ?: ""
        )
    }
    var secretKeyVisible by rememberSaveable { mutableStateOf(false) }
    var bucket by rememberSaveable(uiState.cloudEntity) {
        mutableStateOf(
            uiState.cloudEntity?.getExtraEntity<S3Extra>()?.bucket ?: ""
        )
    }
    var endpoint by rememberSaveable(uiState.cloudEntity) {
        mutableStateOf(
            uiState.cloudEntity?.getExtraEntity<S3Extra>()?.endpoint ?: ""
        )
    }

    // 协议选择状态 / Protocol selection state
    val protocolOptions = listOf("HTTPS", "HTTP")
    var protocolIndex by rememberSaveable(uiState.cloudEntity) {
        mutableIntStateOf(
            when (uiState.cloudEntity?.getExtraEntity<S3Extra>()?.protocol) {
                S3Protocol.HTTP -> 1
                else -> 0
            }
        )
    }

    // 网络类型选择状态 / Network type selection state
    val networkTypeOptions = listOf(
        stringResource(id = R.string.network_type_public),  // 公网(公有云) / Public Cloud
        stringResource(id = R.string.network_type_private)  // 内网(自建S3) / Private Network
    )
    var networkTypeIndex by rememberSaveable(uiState.cloudEntity) {
        mutableIntStateOf(
            when (uiState.cloudEntity?.getExtraEntity<S3Extra>()?.networkType) {
                S3NetworkType.PRIVATE -> 1
                else -> 0
            }
        )
    }

    val allFilled by rememberSaveable(
        name,
        accessKeyId,
        secretAccessKey,
        bucket
    ) { mutableStateOf(name.isNotEmpty() && accessKeyId.isNotEmpty() && secretAccessKey.isNotEmpty() && bucket.isNotEmpty()) }

    LaunchedEffect(null) {
        viewModel.emitIntentOnIO(IndexUiIntent.Initialize)
    }

    // 编辑已有/导入的 S3 账户时，从账户 CloudEntity 恢复 restic 初始化状态与密码回填
    LaunchedEffect(uiState.cloudEntity) {
        uiState.cloudEntity?.let { entity ->
            s3ViewModel.restoreStateFromEntity(entity)
        }
    }

    AccountSetupScaffold(
        scrollBehavior = scrollBehavior,
        snackbarHostState = viewModel.snackbarHostState,
        title = stringResource(id = R.string.s3_setup),
        actions = {
            // 删除账户按钮 - 左侧红色
            if (uiState.currentName.isNotEmpty())
                TextButton(
                    enabled = uiState.isProcessing.not(),
                    onClick = {
                        viewModel.launchOnIO {
                            if (dialogState.confirmWithInput(
                                    title = "删除账户",
                                    message = "此操作不可撤销，请输入确认文本继续",
                                    confirmText = "确认删除",
                                    hint = "请输入确认删除"
                                )) {
                                viewModel.emitIntent(IndexUiIntent.DeleteAccount(navController = navController))
                            }
                        }
                    }
                ) {
                    Text(
                        text = stringResource(id = R.string.delete_account),
                        color = ThemedColorSchemeKeyTokens.Error.value
                    )
                }

            TextButton(
                enabled = allFilled && uiState.isProcessing.not(),
                onClick = {
                    viewModel.launchOnIO {
                        viewModel.updateS3Entity(
                            name = name,
                            remote = remote,
                            type = "S3",
                            region = region,
                            accessKeyId = accessKeyId,
                            secretAccessKey = secretAccessKey,
                            bucket = bucket,
                            endpoint = endpoint,
                            protocol = if (protocolIndex == 0) S3Protocol.HTTPS else S3Protocol.HTTP,
                            networkType = if (networkTypeIndex == 0) S3NetworkType.PUBLIC else S3NetworkType.PRIVATE,
                            resticPassword = s3Password,
                        )
                        viewModel.emitIntent(IndexUiIntent.TestConnection)
                    }
                }
            ) {
                Text(text = stringResource(id = R.string.test_connection))
            }
            Button(enabled = allFilled && remote.isNotEmpty() && uiState.isProcessing.not(), onClick = {
                viewModel.launchOnIO {
                    viewModel.updateS3Entity(
                        name = name,
                        remote = remote,
                        type = "S3",
                        region = region,
                        accessKeyId = accessKeyId,
                        secretAccessKey = secretAccessKey,
                        bucket = bucket,
                        endpoint = endpoint,
                        protocol = if (protocolIndex == 0) S3Protocol.HTTPS else S3Protocol.HTTP,
                        networkType = if (networkTypeIndex == 0) S3NetworkType.PUBLIC else S3NetworkType.PRIVATE,
                        resticPassword = s3Password,
                    )
                    viewModel.emitIntent(IndexUiIntent.CreateAccount(navController = navController))
                }
            }) {
                Text(text = stringResource(id = R.string._continue))
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
        ) {
            Title(
                enabled = uiState.isProcessing.not(),
                title = stringResource(id = R.string.server),
                verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
            ) {
                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.currentName.isEmpty() && uiState.isProcessing.not(),
                    value = name,
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_badge),
                    onValueChange = { name = it },
                    label = stringResource(id = R.string.name)
                )

                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = region,
                    leadingIcon = Icons.Rounded.Public,
                    onValueChange = { region = it },
                    label = stringResource(id = R.string.region)
                )

                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = bucket,
                    leadingIcon = Icons.Rounded.Folder,
                    onValueChange = { bucket = it },
                    label = stringResource(id = R.string.bucket)
                )
            }

            Title(
                enabled = uiState.isProcessing.not(),
                title = stringResource(id = R.string.account),
                verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
            ) {
                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = accessKeyId,
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_person),
                    onValueChange = { accessKeyId = it },
                    label = stringResource(id = R.string.access_key_id)
                )

                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = secretAccessKey,
                    visualTransformation = if (secretKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_key),
                    trailingIcon = if (secretKeyVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    onTrailingIconClick = {
                        secretKeyVisible = secretKeyVisible.not()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    onValueChange = { secretAccessKey = it },
                    label = stringResource(id = R.string.secret_access_key),
                )
            }

            Title(
                enabled = uiState.isProcessing.not(),
                title = stringResource(id = R.string.advanced)
            ) {
                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = endpoint,
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_link),
                    onValueChange = { endpoint = it },
                    label = stringResource(id = R.string.endpoint)
                )

                // 协议选择 - 使用分段按钮 / Protocol selection - using segmented buttons
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                ) {
                    protocolOptions.forEachIndexed { index, label ->
                        SegmentedButton(
                            enabled = uiState.isProcessing.not(),
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = protocolOptions.size
                            ),
                            onClick = {
                                protocolIndex = index
                            },
                            selected = index == protocolIndex
                        ) {
                            Text(label)
                        }
                    }
                }

                // 网络类型选择 - 使用分段按钮 / Network type selection - using segmented buttons
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                ) {
                    networkTypeOptions.forEachIndexed { index, label ->
                        SegmentedButton(
                            enabled = uiState.isProcessing.not(),
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = networkTypeOptions.size
                            ),
                            onClick = {
                                networkTypeIndex = index
                            },
                            selected = index == networkTypeIndex
                        ) {
                            Text(label)
                        }
                    }
                }

                Clickable(
                    enabled = allFilled && uiState.isProcessing.not(),
                    title = stringResource(id = R.string.remote_path),
                    value = remote.ifEmpty { notSelectedText },
                    desc = stringResource(id = R.string.remote_path_desc),
                ) {
                    viewModel.launchOnIO {
                        viewModel.updateS3Entity(
                            name = name,
                            remote = remote,
                            type = "S3",
                            region = region,
                            accessKeyId = accessKeyId,
                            secretAccessKey = secretAccessKey,
                            bucket = bucket,
                            endpoint = endpoint,
                            protocol = if (protocolIndex == 0) S3Protocol.HTTPS else S3Protocol.HTTP,
                            networkType = if (networkTypeIndex == 0) S3NetworkType.PUBLIC else S3NetworkType.PRIVATE,
                            resticPassword = s3Password,
                        )
                        viewModel.emitIntent(IndexUiIntent.SetRemotePath(context = context))
                        remote = uiState.cloudEntity!!.remote
                    }
                }

                Title(
                    enabled = uiState.isProcessing.not(),
                    title = stringResource(id = R.string.s3_restic_initialization)
                ) {
                    // S3 Restic密码设置
                    SetupTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .paddingHorizontal(SizeTokens.Level24),
                        enabled = uiState.isProcessing.not(),
                        value = s3Password,
                        visualTransformation = if (s3PasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_key),
                        trailingIcon = if (s3PasswordVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        onTrailingIconClick = {
                            s3PasswordVisible = s3PasswordVisible.not()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        onValueChange = {
                            s3Password = it
                            s3ViewModel.saveS3Password(it)
                        },
                        label = stringResource(id = R.string.s3_restic_password)
                    )

                    // 初始化状态显示
                    val initStatus = when (val state = s3InitState) {
                        is S3ResticViewModel.S3InitializationState.Idle ->
                            stringResource(id = R.string.s3_restic_not_initialized)
                        is S3ResticViewModel.S3InitializationState.Initializing ->
                            stringResource(id = R.string.s3_restic_initializing)
                        is S3ResticViewModel.S3InitializationState.Success ->
                            stringResource(id = R.string.s3_restic_initialized_at, state.repoPath)
                        is S3ResticViewModel.S3InitializationState.Error ->
                            stringResource(id = R.string.s3_restic_init_failed, state.message)
                    }

                    val statusColor = when (s3InitState) {
                        is S3ResticViewModel.S3InitializationState.Success -> MaterialTheme.colorScheme.primary
                        is S3ResticViewModel.S3InitializationState.Error -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    }

                    Clickable(
                        enabled = uiState.isProcessing.not(),
                        title = stringResource(id = R.string.s3_restic_init_status),
                        value = initStatus,
                        onClick = {
                            if (s3InitState is S3ResticViewModel.S3InitializationState.Idle && s3Password.isNotEmpty()) {
                                scope.launch {
                                    // 构建S3Extra对象
                                    val s3Extra = S3Extra(
                                        type = "S3",
                                        region = region,
                                        accessKeyId = accessKeyId,
                                        secretAccessKey = secretAccessKey,
                                        bucket = bucket,
                                        endpoint = endpoint,
                                        protocol = if (protocolIndex == 0) S3Protocol.HTTPS else S3Protocol.HTTP,
                                        networkType = if (networkTypeIndex == 0) S3NetworkType.PUBLIC else S3NetworkType.PRIVATE
                                    )
                                    s3ViewModel.initializeS3Repository(s3Extra, remote, s3Password)
                                }
                            }
                        }
                    )

                    // 初始化按钮
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .paddingHorizontal(SizeTokens.Level24),
                        enabled = uiState.isProcessing.not() &&
                                s3Password.isNotEmpty() &&
                                s3InitState !is S3ResticViewModel.S3InitializationState.Initializing,
                        onClick = {
                            scope.launch {
                                // 构建完整的S3Extra对象
                                val s3Extra = S3Extra(
                                    type = "S3",
                                    region = region,
                                    accessKeyId = accessKeyId,
                                    secretAccessKey = secretAccessKey,
                                    bucket = bucket,
                                    endpoint = endpoint,
                                    protocol = if (protocolIndex == 0) S3Protocol.HTTPS else S3Protocol.HTTP,
                                    networkType = if (networkTypeIndex == 0) S3NetworkType.PUBLIC else S3NetworkType.PRIVATE
                                )

                                // 调用初始化方法
                                s3ViewModel.initializeS3Repository(s3Extra, remote, s3Password)
                            }
                        }
                    ) {
                        if (s3InitState is S3ResticViewModel.S3InitializationState.Initializing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(text = stringResource(id = R.string.s3_restic_initialize))
                    }
                }  // 添加这个缺失的闭合大括号
            }  // Title 块在这里结束
        }
    }
}