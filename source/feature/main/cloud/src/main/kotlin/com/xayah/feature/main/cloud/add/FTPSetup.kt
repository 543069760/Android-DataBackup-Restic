package com.xayah.feature.main.cloud.add

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.model.database.FTPExtra
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.confirm
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingStart
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.theme.withState
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.feature.main.cloud.AccountSetupScaffold
import com.xayah.feature.main.cloud.R
import com.xayah.feature.main.cloud.SetupTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageFTPSetup() {
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val context = LocalContext.current
    val notSelectedText = stringResource(id = R.string.not_selected)
    val deleteAccountText = stringResource(id = R.string.delete_account)
    val deleteAccountDescText = stringResource(id = R.string.delete_account_desc)
    val navController = LocalNavController.current!!
    val viewModel = hiltViewModel<IndexViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val ftpViewModel = hiltViewModel<FtpResticViewModel>()
    val scope = rememberCoroutineScope()

    var ftpPassword by rememberSaveable { mutableStateOf("") }
    var ftpPasswordVisible by rememberSaveable { mutableStateOf(false) }

    val ftpInitState by ftpViewModel.ftpInitializationState.collectAsStateWithLifecycle()
    val ftpPasswordState by ftpViewModel.ftpPasswordState.collectAsStateWithLifecycle()

    LaunchedEffect(ftpPasswordState) {
        ftpPassword = ftpPasswordState
    }
    var name by rememberSaveable { mutableStateOf(uiState.currentName) }
    var remote by rememberSaveable(uiState.cloudEntity) { mutableStateOf(uiState.cloudEntity?.remote ?: "") }
    var url by rememberSaveable(uiState.cloudEntity) { mutableStateOf(uiState.cloudEntity?.host ?: "") }
    var port by rememberSaveable(uiState.cloudEntity) { mutableStateOf(uiState.cloudEntity?.getExtraEntity<FTPExtra>()?.port?.toString() ?: "21") }
    var username by rememberSaveable(uiState.cloudEntity) { mutableStateOf(uiState.cloudEntity?.user ?: "") }
    val modeOptions = stringArrayResource(id = R.array.ftp_auth_mode).toList()
    var modeIndex by rememberSaveable(uiState.cloudEntity) { mutableIntStateOf(if ((uiState.cloudEntity?.user ?: "") == ConstantUtil.FTP_ANONYMOUS_USERNAME) 1 else 0) }
    var password by rememberSaveable(uiState.cloudEntity) { mutableStateOf(uiState.cloudEntity?.pass ?: "") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val allFilled by rememberSaveable(
        name,
        url,
        port,
        username,
        password
    ) { mutableStateOf(name.isNotEmpty() && url.isNotEmpty() && port.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()) }

    LaunchedEffect(null) {
        viewModel.emitIntentOnIO(IndexUiIntent.Initialize)
    }

    AccountSetupScaffold(
        scrollBehavior = scrollBehavior,
        snackbarHostState = viewModel.snackbarHostState,
        title = stringResource(id = R.string.ftp_setup),
        actions = {
            TextButton(
                enabled = allFilled && uiState.isProcessing.not(),
                onClick = {
                    viewModel.launchOnIO {
                        viewModel.updateFTPEntity(
                            name = name, remote = remote, url = url,
                            username = username, password = password, port = port,
                            resticPassword = ftpPassword,
                        )
                        viewModel.emitIntent(IndexUiIntent.TestConnection)
                    }
                }
            ) {
                Text(text = stringResource(id = R.string.test_connection))
            }

            Button(enabled = allFilled && remote.isNotEmpty() && uiState.isProcessing.not(), onClick = {
                viewModel.launchOnIO {
                    viewModel.updateFTPEntity(
                        name = name, remote = remote, url = url,
                        username = username, password = password, port = port,
                        resticPassword = ftpPassword,
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

                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = url,
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_link),
                    prefix = "ftp://",
                    onValueChange = { url = it },
                    label = stringResource(id = R.string.url)
                )

                // 检测到公网 IPv4 时显示黄色警示卡片
                if (isPublicIpv4(url)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .paddingHorizontal(SizeTokens.Level24)
                            .background(
                                color = ThemedColorSchemeKeyTokens.YellowPrimaryContainer.value,
                                shape = RoundedCornerShape(SizeTokens.Level12)
                            )
                            .padding(SizeTokens.Level16)
                    ) {
                        BodyMediumText(
                            text = stringResource(id = R.string.ftp_public_ip_warning),
                            color = ThemedColorSchemeKeyTokens.YellowOnPrimaryContainer.value
                        )
                    }
                }

                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = port,
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_lan),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    onValueChange = { port = it },
                    label = stringResource(id = R.string.port)
                )
            }

            Title(enabled = uiState.isProcessing.not(), title = stringResource(id = R.string.account), verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                ) {
                    modeOptions.forEachIndexed { index, label ->
                        SegmentedButton(
                            enabled = uiState.isProcessing.not(),
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modeOptions.size),
                            onClick = {
                                if (index == 0) {
                                    // Password
                                    if (modeIndex != 0) {
                                        username = ""
                                        password = ""
                                    }
                                } else {
                                    // Anonymous
                                    username = ConstantUtil.FTP_ANONYMOUS_USERNAME
                                    password = ConstantUtil.FTP_ANONYMOUS_PASSWORD
                                }
                                modeIndex = index
                            },
                            selected = index == modeIndex
                        ) {
                            Text(label)
                        }
                    }
                }

                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not() && modeIndex == 0,
                    value = username,
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_person),
                    onValueChange = { username = it },
                    label = stringResource(id = R.string.username)
                )

                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not() && modeIndex == 0,
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
                        viewModel.updateFTPEntity(
                            name = name, remote = remote, url = url,
                            username = username, password = password, port = port,
                            resticPassword = ftpPassword,
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
            Title(
                enabled = uiState.isProcessing.not(),
                title = stringResource(id = R.string.s3_restic_initialization)
            ) {
                // FTP Restic 密码设置
                SetupTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level24),
                    enabled = uiState.isProcessing.not(),
                    value = ftpPassword,
                    visualTransformation = if (ftpPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_key),
                    trailingIcon = if (ftpPasswordVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    onTrailingIconClick = {
                        ftpPasswordVisible = ftpPasswordVisible.not()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    onValueChange = {
                        ftpPassword = it
                        ftpViewModel.saveFtpPassword(it)
                    },
                    label = stringResource(id = R.string.s3_restic_password)
                )

                // 初始化状态显示
                val initStatus = when (val state = ftpInitState) {
                    is FtpResticViewModel.FtpInitializationState.Idle ->
                        stringResource(id = R.string.s3_restic_not_initialized)
                    is FtpResticViewModel.FtpInitializationState.Initializing ->
                        stringResource(id = R.string.s3_restic_initializing)
                    is FtpResticViewModel.FtpInitializationState.Success ->
                        stringResource(id = R.string.s3_restic_initialized_at, state.repoPath)
                    is FtpResticViewModel.FtpInitializationState.Error ->
                        stringResource(id = R.string.s3_restic_init_failed, state.message)
                }

                val statusColor = when (ftpInitState) {
                    is FtpResticViewModel.FtpInitializationState.Success -> MaterialTheme.colorScheme.primary
                    is FtpResticViewModel.FtpInitializationState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }

                Clickable(
                    enabled = uiState.isProcessing.not(),
                    title = stringResource(id = R.string.s3_restic_init_status),
                    value = initStatus,
                    onClick = {
                        if (ftpInitState is FtpResticViewModel.FtpInitializationState.Idle && ftpPassword.isNotEmpty()) {
                            scope.launch {
                                ftpViewModel.initializeFtpRepository(
                                    name = name,
                                    host = url,
                                    port = port.toIntOrNull() ?: 21,
                                    username = username,
                                    pass = password,
                                    remotePath = remote,
                                    password = ftpPassword
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
                            ftpPassword.isNotEmpty() &&
                            ftpInitState !is FtpResticViewModel.FtpInitializationState.Initializing,
                    onClick = {
                        scope.launch {
                            ftpViewModel.initializeFtpRepository(
                                name = name,
                                host = url,
                                port = port.toIntOrNull() ?: 21,
                                username = username,
                                pass = password,
                                remotePath = remote,
                                password = ftpPassword
                            )
                        }
                    }
                ) {
                    if (ftpInitState is FtpResticViewModel.FtpInitializationState.Initializing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(text = stringResource(id = R.string.s3_restic_initialize))
                }
            }
        }
    }
}

/**
 * 判断输入字符串是否为「公网 IPv4」。
 * - 必须是标准 4 段点分十进制（每段 0-255），否则（含域名）返回 false。
 * - 私有/保留段返回 false：10/8、172.16/12、192.168/16、127/8、169.254/16、100.64/10。
 */
private fun isPublicIpv4(input: String): Boolean {
    val host = input.trim().removePrefix("ftp://").substringBefore('/').substringBefore(':')
    val parts = host.split(".")
    if (parts.size != 4) return false
    val octets = parts.map { seg ->
        val n = seg.toIntOrNull() ?: return false
        if (n < 0 || n > 255) return false
        n
    }
    val (a, b) = octets[0] to octets[1]
    // 私有 / 保留段一律视为内网，不提示
    val isPrivate = when {
        a == 10 -> true                                  // 10.0.0.0/8
        a == 172 && b in 16..31 -> true                  // 172.16.0.0/12
        a == 192 && b == 168 -> true                     // 192.168.0.0/16
        a == 127 -> true                                 // 127.0.0.0/8 回环
        a == 169 && b == 254 -> true                     // 169.254.0.0/16 link-local
        a == 100 && b in 64..127 -> true                 // 100.64.0.0/10 CGNAT
        else -> false
    }
    return isPrivate.not()
}