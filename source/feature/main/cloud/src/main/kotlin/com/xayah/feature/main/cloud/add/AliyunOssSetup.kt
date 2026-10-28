package com.xayah.feature.main.cloud.add

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.model.database.AliyunOssExtra
import com.xayah.core.model.database.AliyunOssProtocol
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.confirm
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
import kotlinx.coroutines.launch

@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageAliyunOssSetup() {
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val context = LocalContext.current
    val notSelectedText = stringResource(id = R.string.not_selected)
    val deleteAccountText = stringResource(id = R.string.delete_account)
    val deleteAccountDescText = stringResource(id = R.string.delete_account_desc)
    val navController = LocalNavController.current!!
    val viewModel = hiltViewModel<IndexViewModel>()
    val aliyunOssViewModel = hiltViewModel<AliyunOssResticViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val scope = rememberCoroutineScope()

    var ossPassword by rememberSaveable { mutableStateOf("") }
    var ossPasswordVisible by rememberSaveable { mutableStateOf(false) }

    val ossInitState by aliyunOssViewModel.ossInitializationState.collectAsStateWithLifecycle()
    val ossPasswordState by aliyunOssViewModel.ossPasswordState.collectAsStateWithLifecycle()

    LaunchedEffect(ossPasswordState) {
        ossPassword = ossPasswordState
    }
    var name by rememberSaveable { mutableStateOf(uiState.currentName) }
    var remote by rememberSaveable(uiState.cloudEntity) {
        mutableStateOf(uiState.cloudEntity?.remote ?: "")
    }
    var accessKeyId by rememberSaveable(uiState.cloudEntity) {
        mutableStateOf(uiState.cloudEntity?.user ?: "")
    }
    var accessKeySecret by rememberSaveable(uiState.cloudEntity) {
        mutableStateOf(uiState.cloudEntity?.pass ?: "")
    }
    var secretKeyVisible by rememberSaveable { mutableStateOf(false) }
    var bucket by rememberSaveable(uiState.cloudEntity) {
        mutableStateOf(uiState.cloudEntity?.getExtraEntity<AliyunOssExtra>()?.bucket ?: "")
    }
    var endpoint by rememberSaveable(uiState.cloudEntity) {
        mutableStateOf(uiState.cloudEntity?.getExtraEntity<AliyunOssExtra>()?.endpoint ?: "")
    }

    // 协议选择状态（HTTPS/HTTP）
    val protocolOptions = listOf("HTTPS", "HTTP")
    var protocolIndex by rememberSaveable(uiState.cloudEntity) {
        mutableIntStateOf(
            when (uiState.cloudEntity?.getExtraEntity<AliyunOssExtra>()?.protocol) {
                AliyunOssProtocol.HTTP -> 1
                else -> 0
            }
        )
    }

    val allFilled by rememberSaveable(
        name, accessKeyId, accessKeySecret, bucket, endpoint
    ) { mutableStateOf(name.isNotEmpty() && accessKeyId.isNotEmpty() && accessKeySecret.isNotEmpty() && bucket.isNotEmpty() && endpoint.isNotEmpty()) }

    LaunchedEffect(null) {
        viewModel.emitIntentOnIO(IndexUiIntent.Initialize)
    }

    LaunchedEffect(uiState.cloudEntity) {
        uiState.cloudEntity?.let { entity ->
            aliyunOssViewModel.restoreStateFromEntity(entity)
        }
    }

    AccountSetupScaffold(
        scrollBehavior = scrollBehavior,
        snackbarHostState = viewModel.snackbarHostState,
        title = stringResource(id = R.string.aliyun_oss_setup),
        actions = {
            TextButton(
                enabled = allFilled && uiState.isProcessing.not(),
                onClick = {
                    viewModel.launchOnIO {
                        viewModel.updateAliyunOssEntity(
                            name = name,
                            remote = remote,
                            type = "ALIYUNOSS",
                            accessKeyId = accessKeyId,
                            accessKeySecret = accessKeySecret,
                            bucket = bucket,
                            endpoint = endpoint,
                            protocol = if (protocolIndex == 0) AliyunOssProtocol.HTTPS else AliyunOssProtocol.HTTP,
                            resticPassword = ossPassword,
                        )
                        viewModel.emitIntent(IndexUiIntent.TestConnection)
                    }
                }
            ) {
                Text(text = stringResource(id = R.string.test_connection))
            }
            ContinueButton(
                viewModel = viewModel,
                navController = navController,
                enabled = allFilled && remote.isNotEmpty() && uiState.isProcessing.not()
                        && ossInitState is AliyunOssResticViewModel.OssInitializationState.Success,
                onUpdateEntity = {
                    viewModel.updateAliyunOssEntity(
                        name = name, remote = remote, type = "ALIYUNOSS",
                        accessKeyId = accessKeyId, accessKeySecret = accessKeySecret,
                        bucket = bucket, endpoint = endpoint,
                        protocol = if (protocolIndex == 0) AliyunOssProtocol.HTTPS else AliyunOssProtocol.HTTP,
                        resticPassword = ossPassword,
                    )
                },
                check = {
                    val entity = uiState.cloudEntity
                    entity != null && aliyunOssViewModel.checkAliyunOssRepository(entity, ossPassword)
                },
            )
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
        ) {
            // ===== server 区：name / bucket =====
            Title(
                enabled = uiState.isProcessing.not(),
                title = stringResource(id = R.string.server),
                verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
            ) {
                SetupTextField(
                    modifier = Modifier.fillMaxWidth().paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.currentName.isEmpty() && uiState.isProcessing.not(),
                    value = name,
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_badge),
                    onValueChange = { name = it },
                    label = stringResource(id = R.string.name)
                )
                SetupTextField(
                    modifier = Modifier.fillMaxWidth().paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = bucket,
                    leadingIcon = Icons.Rounded.Folder,
                    onValueChange = { bucket = it },
                    label = stringResource(id = R.string.bucket)
                )
            }

            // ===== account 区：accessKeyId / accessKeySecret =====
            Title(
                enabled = uiState.isProcessing.not(),
                title = stringResource(id = R.string.account),
                verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
            ) {
                SetupTextField(
                    modifier = Modifier.fillMaxWidth().paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = accessKeyId,
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_person),
                    onValueChange = { accessKeyId = it },
                    label = stringResource(id = R.string.aliyun_oss_access_key_id),
                )
                SetupTextField(
                    modifier = Modifier.fillMaxWidth().paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = accessKeySecret,
                    visualTransformation = if (secretKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_key),
                    trailingIcon = if (secretKeyVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    onTrailingIconClick = { secretKeyVisible = secretKeyVisible.not() },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    onValueChange = { accessKeySecret = it },
                    label = stringResource(id = R.string.aliyun_oss_access_key_secret),
                )
            }

            // ===== Advanced 区 =====
            Title(
                enabled = uiState.isProcessing.not(),
                title = stringResource(id = R.string.advanced)
            ) {
                // (a) 协议分段按钮（HTTPS/HTTP）
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().paddingHorizontal(SizeTokens.Level24),
                ) {
                    protocolOptions.forEachIndexed { index, label ->
                        SegmentedButton(
                            enabled = uiState.isProcessing.not(),
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = protocolOptions.size),
                            onClick = { protocolIndex = index },
                            selected = index == protocolIndex
                        ) { Text(label) }
                    }
                }

                // (b) 端点输入框（阿里云地域节点，必填）
                SetupTextField(
                    modifier = Modifier.fillMaxWidth().paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = endpoint,
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_link),
                    onValueChange = { endpoint = it },
                    label = stringResource(id = R.string.aliyun_oss_endpoint)
                )

                // (c) 示例说明卡片
                Card(
                    modifier = Modifier.fillMaxWidth().paddingHorizontal(SizeTokens.Level24).padding(top = SizeTokens.Level12),
                    colors = CardDefaults.cardColors(
                        containerColor = ThemedColorSchemeKeyTokens.BluePrimaryContainer.value
                    ),
                ) {
                    BodyMediumText(
                        modifier = Modifier.padding(SizeTokens.Level16),
                        text = stringResource(id = R.string.aliyun_oss_endpoint_example),
                        color = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value
                    )
                }

                // (d) 访问地址预览卡片
                val previewUrl = if (bucket.isNotBlank() && endpoint.isNotBlank()) {
                    val scheme = if (protocolIndex == 0) "https" else "http"
                    val host = endpoint.trim()
                        .removePrefix("https://")
                        .removePrefix("http://")
                        .removeSuffix("/")
                    "$scheme://${bucket.trim()}.$host"
                } else ""
                if (previewUrl.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().paddingHorizontal(SizeTokens.Level24).padding(top = SizeTokens.Level12),
                        colors = CardDefaults.cardColors(
                            containerColor = ThemedColorSchemeKeyTokens.SecondaryContainer.value
                        ),
                    ) {
                        BodyMediumText(
                            modifier = Modifier.padding(SizeTokens.Level16),
                            text = stringResource(id = R.string.aliyun_oss_url_preview, previewUrl),
                            color = ThemedColorSchemeKeyTokens.OnSecondaryContainer.value
                        )
                    }
                }

                // 远程路径
                Clickable(
                    enabled = allFilled && uiState.isProcessing.not(),
                    title = stringResource(id = R.string.remote_path),
                    value = remote.ifEmpty { notSelectedText },
                    desc = stringResource(id = R.string.remote_path_desc),
                ) {
                    viewModel.launchOnIO {
                        viewModel.updateAliyunOssEntity(
                            name = name, remote = remote, type = "ALIYUNOSS",
                            accessKeyId = accessKeyId, accessKeySecret = accessKeySecret,
                            bucket = bucket, endpoint = endpoint,
                            protocol = if (protocolIndex == 0) AliyunOssProtocol.HTTPS else AliyunOssProtocol.HTTP,
                            resticPassword = ossPassword,
                        )
                        viewModel.emitIntent(IndexUiIntent.SetRemotePath(context = context))
                        remote = uiState.cloudEntity!!.remote
                    }
                }

                if (uiState.currentName.isNotEmpty())
                    TextButton(
                        modifier = Modifier.paddingStart(SizeTokens.Level12).paddingTop(SizeTokens.Level12),
                        enabled = uiState.isProcessing.not(),
                        onClick = {
                            viewModel.launchOnIO {
                                if (dialogState.confirm(title = deleteAccountText, text = deleteAccountDescText)) {
                                    viewModel.emitIntent(IndexUiIntent.DeleteAccount(navController = navController))
                                }
                            }
                        }
                    ) {
                        Text(
                            text = stringResource(id = R.string.delete_account),
                            color = ThemedColorSchemeKeyTokens.Error.value.withState(uiState.isProcessing.not())
                        )
                    }

                // ===== restic 初始化区 =====
                Title(
                    enabled = uiState.isProcessing.not(),
                    title = stringResource(id = R.string.s3_restic_initialization)
                ) {
                    SetupTextField(
                        modifier = Modifier.fillMaxWidth().paddingHorizontal(SizeTokens.Level24),
                        enabled = uiState.isProcessing.not(),
                        value = ossPassword,
                        visualTransformation = if (ossPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_key),
                        trailingIcon = if (ossPasswordVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        onTrailingIconClick = { ossPasswordVisible = ossPasswordVisible.not() },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        onValueChange = {
                            ossPassword = it
                            aliyunOssViewModel.saveAliyunOssPassword(it)
                        },
                        label = stringResource(id = R.string.s3_restic_password)
                    )

                    val initStatus = when (val state = ossInitState) {
                        is AliyunOssResticViewModel.OssInitializationState.Idle ->
                            stringResource(id = R.string.s3_restic_not_initialized)
                        is AliyunOssResticViewModel.OssInitializationState.Initializing ->
                            stringResource(id = R.string.s3_restic_initializing)
                        is AliyunOssResticViewModel.OssInitializationState.Success ->
                            stringResource(id = R.string.s3_restic_initialized_at, state.repoPath)
                        is AliyunOssResticViewModel.OssInitializationState.Error ->
                            stringResource(id = R.string.s3_restic_init_failed, state.message)
                    }

                    Clickable(
                        enabled = uiState.isProcessing.not(),
                        title = stringResource(id = R.string.s3_restic_init_status),
                        value = initStatus,
                        onClick = {
                            if (ossInitState is AliyunOssResticViewModel.OssInitializationState.Idle && ossPassword.isNotEmpty()) {
                                scope.launch {
                                    val entity = viewModel.updateAliyunOssEntity(
                                        name = name, remote = remote, type = "ALIYUNOSS",
                                        accessKeyId = accessKeyId, accessKeySecret = accessKeySecret,
                                        bucket = bucket, endpoint = endpoint,
                                        protocol = if (protocolIndex == 0) AliyunOssProtocol.HTTPS else AliyunOssProtocol.HTTP,
                                        resticPassword = ossPassword,
                                    )
                                    aliyunOssViewModel.initializeAliyunOssRepository(entity, remote, ossPassword)
                                }
                            }
                        }
                    )

                    Button(
                        modifier = Modifier.fillMaxWidth().paddingHorizontal(SizeTokens.Level24),
                        enabled = uiState.isProcessing.not() && ossPassword.isNotEmpty() &&
                                ossInitState !is AliyunOssResticViewModel.OssInitializationState.Initializing,
                        onClick = {
                            scope.launch {
                                val entity = viewModel.updateAliyunOssEntity(
                                    name = name, remote = remote, type = "ALIYUNOSS",
                                    accessKeyId = accessKeyId, accessKeySecret = accessKeySecret,
                                    bucket = bucket, endpoint = endpoint,
                                    protocol = if (protocolIndex == 0) AliyunOssProtocol.HTTPS else AliyunOssProtocol.HTTP,
                                    resticPassword = ossPassword,
                                )
                                aliyunOssViewModel.initializeAliyunOssRepository(entity, remote, ossPassword)
                            }
                        }
                    ) {
                        if (ossInitState is AliyunOssResticViewModel.OssInitializationState.Initializing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(text = stringResource(id = R.string.s3_restic_initialize))
                    }
                }
            }
        }
    }
}
