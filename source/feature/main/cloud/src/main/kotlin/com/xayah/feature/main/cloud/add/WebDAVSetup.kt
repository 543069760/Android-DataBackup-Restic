package com.xayah.feature.main.cloud.add

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.layout.padding
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.Card
import com.xayah.core.ui.material3.CardDefaults
import com.xayah.core.model.database.WebDAVExtra
import com.xayah.core.model.database.WebDAVProtocol
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.network.util.WebDAVCertUtil
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.confirm
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingStart
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.theme.withState
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.feature.main.cloud.AccountSetupScaffold
import com.xayah.feature.main.cloud.R
import com.xayah.feature.main.cloud.SetupTextField
import kotlinx.coroutines.launch

@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@ExperimentalFoundationApi
@Composable
fun PageWebDAVSetup() {
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val context = LocalContext.current
    val notSelectedText = stringResource(id = R.string.not_selected)
    val deleteAccountText = stringResource(id = R.string.delete_account)
    val deleteAccountDescText = stringResource(id = R.string.delete_account_desc)
    val nonPublicCaUnsupportedText = stringResource(id = R.string.webdav_https_non_public_ca_unsupported)
    val navController = LocalNavController.current!!
    val viewModel = hiltViewModel<IndexViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // ===== 第 2 项：注入 restic 初始化 ViewModel、协程作用域、restic 密码/初始化状态 =====
    val webdavViewModel = hiltViewModel<WebdavResticViewModel>()
    val scope = rememberCoroutineScope()

    var webdavPassword by rememberSaveable { mutableStateOf("") }
    var webdavPasswordVisible by rememberSaveable { mutableStateOf(false) }

    val webdavInitState by webdavViewModel.webdavInitializationState.collectAsStateWithLifecycle()
    val webdavPasswordState by webdavViewModel.webdavPasswordState.collectAsStateWithLifecycle()

    LaunchedEffect(webdavPasswordState) {
        webdavPassword = webdavPasswordState
    }
    // ===== 第 2 项结束 =====

    var name by rememberSaveable { mutableStateOf(uiState.currentName) }
    var remote by rememberSaveable(uiState.cloudEntity) { mutableStateOf(uiState.cloudEntity?.remote ?: "") }

    // ===== 第 3 项：url 回显剥离 scheme，只保留纯主机 =====
    var url by rememberSaveable(uiState.cloudEntity) {
        mutableStateOf(
            uiState.cloudEntity?.host
                ?.removePrefix("https://")
                ?.removePrefix("http://")
                ?.removeSuffix("/")
                ?: ""
        )
    }
    var username by rememberSaveable(uiState.cloudEntity) { mutableStateOf(uiState.cloudEntity?.user ?: "") }
    var password by rememberSaveable(uiState.cloudEntity) { mutableStateOf(uiState.cloudEntity?.pass ?: "") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var insecure by rememberSaveable(uiState.cloudEntity) { mutableStateOf(uiState.cloudEntity?.getExtraEntity<WebDAVExtra>()?.insecure ?: false) }

    // ===== 第 4 项：协议选择状态（HTTPS=0 / HTTP=1），优先按已存 host 的 scheme 回显 =====
    val protocolOptions = listOf("HTTPS", "HTTP")
    var protocolIndex by rememberSaveable(uiState.cloudEntity) {
        mutableIntStateOf(
            when {
                uiState.cloudEntity?.host?.startsWith("http://") == true -> 1
                uiState.cloudEntity?.host?.startsWith("https://") == true -> 0
                uiState.cloudEntity?.getExtraEntity<WebDAVExtra>()?.protocol == WebDAVProtocol.HTTP -> 1
                else -> 0
            }
        )
    }
    // ===== 第 4 项结束 =====

    val allFilled by rememberSaveable(
        name,
        url,
        username,
        password,
        insecure
    ) { mutableStateOf(name.isNotEmpty() && url.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()) }

    LaunchedEffect(null) {
        viewModel.emitIntentOnIO(IndexUiIntent.Initialize)
    }

    // 编辑已有/导入的 WebDAV 账户时，从账户 CloudEntity 恢复 restic 初始化状态与密码回填
    LaunchedEffect(uiState.cloudEntity) {
        uiState.cloudEntity?.let { entity ->
            webdavViewModel.restoreStateFromEntity(entity)
        }
    }

    AccountSetupScaffold(
        scrollBehavior = scrollBehavior,
        snackbarHostState = viewModel.snackbarHostState,
        title = stringResource(id = R.string.webdav_setup),
        actions = {
            TextButton(
                enabled = allFilled && uiState.isProcessing.not(),
                onClick = {
                    viewModel.launchOnIO {
                        // ===== 第 6 项：HTTPS 公共 CA 预校验 =====
                        val cleanHost = url.trim()
                            .removePrefix("https://")
                            .removePrefix("http://")
                            .removeSuffix("/")
                        if (protocolIndex == 0) {
                            // 依赖步骤 6 的 WebDAVCertUtil（同包）
                            val caResult = WebDAVCertUtil.verifyPublicCa(cleanHost)
                            if (caResult.isFailure) {
                                viewModel.emitEffect(
                                    IndexUiEffect.ShowSnackbar(
                                        message = nonPublicCaUnsupportedText,
                                        type = SnackbarType.Error,
                                    )
                                )
                                return@launchOnIO
                            }
                        }
                        // ===== 第 6 项结束 =====

                        // ===== 第 7 项：测试连接调用补传 protocol / resticPassword =====
                        viewModel.updateWebDAVEntity(
                            name = name, remote = remote, url = url,
                            username = username, password = password, insecure = false,
                            protocol = if (protocolIndex == 0) WebDAVProtocol.HTTPS else WebDAVProtocol.HTTP,
                            resticPassword = webdavPassword,
                        )
                        viewModel.emitIntent(IndexUiIntent.TestConnection)
                    }
                }
            ) {
                Text(text = stringResource(id = R.string.test_connection))
            }

            Button(enabled = allFilled && remote.isNotEmpty() && uiState.isProcessing.not(), onClick = {
                viewModel.launchOnIO {
                    // ===== 第 7 项：创建账户调用补传 protocol / resticPassword =====
                    viewModel.updateWebDAVEntity(
                        name = name, remote = remote, url = url,
                        username = username, password = password, insecure = false,
                        protocol = if (protocolIndex == 0) WebDAVProtocol.HTTPS else WebDAVProtocol.HTTP,
                        resticPassword = webdavPassword,
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
            Title(enabled = uiState.isProcessing.not(), title = stringResource(id = R.string.server), verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)) {
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

                // ===== 第 8 项：协议选择分段按钮（HTTPS / HTTP） =====
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
                // ===== 第 8 项结束 =====
                if (protocolIndex == 0) {   // 0 = HTTPS，按你本地实际变量名/索引确认
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .paddingHorizontal(SizeTokens.Level24),
                        colors = CardDefaults.cardColors(
                            containerColor = ThemedColorSchemeKeyTokens.YellowPrimaryContainer.value
                        ),
                    ) {
                        BodyMediumText(
                            modifier = Modifier.padding(SizeTokens.Level16),
                            text = stringResource(id = R.string.webdav_https_non_public_ca_unsupported),
                            color = ThemedColorSchemeKeyTokens.YellowOnPrimaryContainer.value
                        )
                    }
                }
                // ===== 第 9 项：url 输入框，只填纯主机（不含 scheme） =====
                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = url,
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_link),
                    prefix = if (protocolIndex == 0) "https://" else "http://",
                    onValueChange = { url = it },
                    label = stringResource(id = R.string.url)
                )
                // ===== 第 9 项结束 =====
            }

            Title(enabled = uiState.isProcessing.not(), title = stringResource(id = R.string.account), verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)) {
                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = username,
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_person),
                    onValueChange = { username = it },
                    label = stringResource(id = R.string.username)
                )

                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = password,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_key),
                    trailingIcon = if (passwordVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    onTrailingIconClick = {
                        passwordVisible = passwordVisible.not()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    onValueChange = { password = it },
                    label = stringResource(id = R.string.password),
                )
            }

            Title(enabled = uiState.isProcessing.not(), title = stringResource(id = R.string.advanced)) {
                Clickable(
                    enabled = allFilled && uiState.isProcessing.not(),
                    title = stringResource(id = R.string.remote_path),
                    value = remote.ifEmpty { notSelectedText },
                    desc = stringResource(id = R.string.remote_path_desc),
                ) {
                    viewModel.launchOnIO {
                        // ===== 第 10 项：SetRemotePath 调用补传 protocol / resticPassword =====
                        viewModel.updateWebDAVEntity(
                            name = name, remote = remote, url = url,
                            username = username, password = password, insecure = false,
                            protocol = if (protocolIndex == 0) WebDAVProtocol.HTTPS else WebDAVProtocol.HTTP,
                            resticPassword = webdavPassword,
                        )
                        viewModel.emitIntent(IndexUiIntent.SetRemotePath(context = context))
                        remote = uiState.cloudEntity!!.remote
                    }
                }

                if (uiState.currentName.isNotEmpty())
                    TextButton(
                        modifier = Modifier
                            .paddingStart(SizeTokens.Level12)
                            .paddingTop(SizeTokens.Level12),
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
            }

            // ===== 第 11 项：restic 初始化子块（对照 FTPSetup） =====
            Title(
                enabled = uiState.isProcessing.not(),
                title = stringResource(id = R.string.s3_restic_initialization)
            ) {
                // WebDAV Restic 密码设置
                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = webdavPassword,
                    visualTransformation = if (webdavPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_key),
                    trailingIcon = if (webdavPasswordVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    onTrailingIconClick = {
                        webdavPasswordVisible = webdavPasswordVisible.not()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    onValueChange = {
                        webdavPassword = it
                        webdavViewModel.saveWebdavPassword(it)
                    },
                    label = stringResource(id = R.string.s3_restic_password)
                )

                // 初始化状态显示
                val initStatus = when (val state = webdavInitState) {
                    is WebdavResticViewModel.WebdavInitializationState.Idle ->
                        stringResource(id = R.string.s3_restic_not_initialized)
                    is WebdavResticViewModel.WebdavInitializationState.Initializing ->
                        stringResource(id = R.string.s3_restic_initializing)
                    is WebdavResticViewModel.WebdavInitializationState.Success ->
                        stringResource(id = R.string.s3_restic_initialized_at, state.repoPath)
                    is WebdavResticViewModel.WebdavInitializationState.Error ->
                        stringResource(id = R.string.s3_restic_init_failed, state.message)
                }

                val statusColor = when (webdavInitState) {
                    is WebdavResticViewModel.WebdavInitializationState.Success -> MaterialTheme.colorScheme.primary
                    is WebdavResticViewModel.WebdavInitializationState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }

                Clickable(
                    enabled = uiState.isProcessing.not(),
                    title = stringResource(id = R.string.s3_restic_init_status),
                    value = initStatus,
                    onClick = {
                        if (webdavInitState is WebdavResticViewModel.WebdavInitializationState.Idle && webdavPassword.isNotEmpty()) {
                            scope.launch {
                                val cleanHost = url.trim()
                                    .removePrefix("https://")
                                    .removePrefix("http://")
                                    .removeSuffix("/")
                                val scheme = if (protocolIndex == 0) "https" else "http"
                                val fullUrl = "$scheme://$cleanHost"
                                webdavViewModel.initializeWebdavRepository(
                                    name = name,
                                    host = fullUrl,
                                    username = username,
                                    pass = password,
                                    insecure = insecure,
                                    protocol = if (protocolIndex == 0) WebDAVProtocol.HTTPS else WebDAVProtocol.HTTP,
                                    remotePath = remote,
                                    password = webdavPassword
                                )
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
                            webdavPassword.isNotEmpty() &&
                            webdavInitState !is WebdavResticViewModel.WebdavInitializationState.Initializing,
                    onClick = {
                        scope.launch {
                            val cleanHost = url.trim()
                                .removePrefix("https://")
                                .removePrefix("http://")
                                .removeSuffix("/")
                            val scheme = if (protocolIndex == 0) "https" else "http"
                            val fullUrl = "$scheme://$cleanHost"
                            webdavViewModel.initializeWebdavRepository(
                                name = name,
                                host = fullUrl,
                                username = username,
                                pass = password,
                                insecure = insecure,
                                protocol = if (protocolIndex == 0) WebDAVProtocol.HTTPS else WebDAVProtocol.HTTP,
                                remotePath = remote,
                                password = webdavPassword
                            )
                        }
                    }
                ) {
                    if (webdavInitState is WebdavResticViewModel.WebdavInitializationState.Initializing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(text = stringResource(id = R.string.s3_restic_initialize))
                }
            }
            // ===== 第 11 项结束 =====
        }
    }
}