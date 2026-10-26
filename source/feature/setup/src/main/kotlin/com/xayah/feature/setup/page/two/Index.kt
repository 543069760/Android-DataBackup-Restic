package com.xayah.feature.setup.page.two

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.datastore.KeyLoadSystemApps
import com.xayah.core.datastore.readBackupSavePath
import com.xayah.core.datastore.readBackupSavePathSaved
import com.xayah.core.datastore.readResticCompressionLevel
import com.xayah.core.datastore.saveResticCompressionLevel
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.BodySmallText
import com.xayah.core.ui.component.Card
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.LabelSmallText
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.SecondaryLargeTopBar
import com.xayah.core.ui.component.Slideable
import com.xayah.core.ui.component.Switchable
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.material3.CardDefaults
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.getActivity
import com.xayah.core.util.navigateSingle
import com.xayah.feature.setup.R
import com.xayah.feature.setup.SetupRoutes
import com.xayah.feature.setup.SetupScaffold
import com.xayah.libpickyou.PickYouLauncher
import com.xayah.libpickyou.ui.model.PermissionType
import com.xayah.libpickyou.ui.model.PickerType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@ExperimentalAnimationApi
@ExperimentalFoundationApi
@ExperimentalMaterial3Api
@Composable
fun PageTwo() {
    val navController = LocalNavController.current!!
    val context = LocalContext.current
    val viewModel = hiltViewModel<IndexViewModel>()
    val backupSavePathSaved by context.readBackupSavePathSaved().collectAsStateWithLifecycle(initialValue = false)
    val initializationState by viewModel.initializationState.collectAsStateWithLifecycle()
    var password by rememberSaveable { mutableStateOf(IndexViewModel.DEFAULT_RESTIC_PASSWORD) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var isExistingRepo by rememberSaveable { mutableStateOf(false) }
    var selectedRepoPath by rememberSaveable { mutableStateOf("") }
    val isInitialized = initializationState is IndexViewModel.InitializationState.ReadyToUse
    val isInitializing = initializationState is IndexViewModel.InitializationState.Preparing ||
            initializationState is IndexViewModel.InitializationState.Initializing

    // 已有仓库模式下的目录选择器：复用 libpickyou 的 root 目录选择能力（与设置页用法一致）
    val directoryLauncher = PickYouLauncher(
        checkPermission = false,
        title = stringResource(id = R.string.select_directory),
        pickerType = PickerType.DIRECTORY,
        permissionType = PermissionType.ROOT,
    )

    SetupScaffold(
        topBar = {
            SecondaryLargeTopBar(
                scrollBehavior = null,
                title = stringResource(id = R.string.setup)
            )
        },
        actions = {
            val isError = initializationState is IndexViewModel.InitializationState.Error
            // 初始化按钮：必须先点，成功前不可点完成。已有仓库模式下只做校验并打开仓库，不重新初始化。
            // 前置图标：进行中=loading 圆圈，成功=对号，失败=错号并把按钮整体变成红色，文本始终保留避免闪烁。
            Button(
                // 已有仓库模式下必须先选中仓库目录，避免拿空路径去校验
                enabled = isInitialized.not() && isInitializing.not() &&
                        (isExistingRepo.not() || selectedRepoPath.isNotEmpty()),
                // 失败=红色；进行中/成功虽然禁用，但仍保持常规配色，避免点击瞬间按钮变灰闪一下
                colors = when {
                    isError -> ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        disabledContainerColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.onError,
                    )

                    isInitializing || isInitialized -> ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                    )

                    else -> ButtonDefaults.buttonColors()
                },
                onClick = {
                    viewModel.emitIntentOnIO(
                        if (isExistingRepo) IndexUiIntent.OpenExisting(repoPath = selectedRepoPath, password = password)
                        else IndexUiIntent.Initialize(password = password)
                    )
                }
            ) {
                when {
                    isInitializing -> CircularProgressIndicator(
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                        color = LocalContentColor.current,
                        strokeWidth = 2.dp
                    )

                    isError -> Icon(
                        imageVector = Icons.Rounded.Error,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )

                    isInitialized -> Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                }
                if (isInitializing || isError || isInitialized) {
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                }
                Text(
                    text = stringResource(
                        id = if (isExistingRepo) R.string.restic_open_existing else R.string.initialize
                    )
                )
            }
            // 完成按钮：仅在初始化成功后亮起
            Button(
                enabled = isInitialized,
                onClick = {
                    viewModel.emitIntentOnIO(IndexUiIntent.ToMain(context = context.getActivity()))
                }
            ) {
                Text(text = stringResource(id = R.string.finish))
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
        ) {

            // Restic 仓库路径 - 只读展示
            Clickable(
                title = stringResource(id = R.string.restic_repository),
                value = when (val s = initializationState) {
                    is IndexViewModel.InitializationState.ReadyToUse -> s.repoPath
                    // 详细错误已由下方红色卡片展示，此处只标注状态
                    is IndexViewModel.InitializationState.Error -> stringResource(id = R.string.error)
                    IndexViewModel.InitializationState.Preparing,
                    IndexViewModel.InitializationState.Initializing -> stringResource(id = R.string.initializing)
                    IndexViewModel.InitializationState.Idle -> "-"
                },
                // 初始化完成后 value 已是真实仓库路径，若再展示默认路径会变成两行重复内容
                desc = if (initializationState is IndexViewModel.InitializationState.ReadyToUse) null
                else ConstantUtil.DEFAULT_RUSTIC_REPO_ROOT + "/restic_repo",
                enabled = false
            ) {}

            // 仓库模式选择：全新仓库 / 已有仓库（从旧设备完整复制过来的仓库）
            Title(title = stringResource(id = R.string.restic_repo_mode)) {
                val modeOptions = listOf(
                    stringResource(id = R.string.restic_repo_mode_new),
                    stringResource(id = R.string.restic_repo_mode_existing),
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingHorizontal(SizeTokens.Level16),
                ) {
                    modeOptions.forEachIndexed { index, label ->
                        SegmentedButton(
                            enabled = isInitialized.not() && isInitializing.not(),
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modeOptions.size),
                            onClick = { isExistingRepo = index == 1 },
                            selected = isExistingRepo == (index == 1)
                        ) {
                            Text(text = label)
                        }
                    }
                }

                AnimatedVisibility(visible = isExistingRepo) {
                    Column {
                        // 选择已有仓库目录：复用 libpickyou 目录选择器，需选中仓库目录本身（内含 config/data/keys）
                        Clickable(
                            title = stringResource(id = R.string.select_directory),
                            // 未选择 / 未确认初始化前给出明确提示，避免误以为已经选好了默认路径
                            value = selectedRepoPath.ifEmpty { stringResource(id = R.string.restic_repo_path_not_selected) },
                            enabled = isInitialized.not() && isInitializing.not(),
                        ) {
                            directoryLauncher.launch(context) { pathString ->
                                selectedRepoPath = pathString
                            }
                        }

                        // 蓝色 M3 说明卡片：先讲清「必须选中实际复制过来的仓库目录」，再把默认值单独列出供复制
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = SizeTokens.Level24)
                                .padding(top = SizeTokens.Level12),
                            colors = CardDefaults.cardColors(
                                containerColor = ThemedColorSchemeKeyTokens.BluePrimaryContainer.value
                            ),
                        ) {
                            Column(modifier = Modifier.padding(SizeTokens.Level16)) {
                                BodyMediumText(
                                    text = stringResource(id = R.string.restic_existing_repo_hint),
                                    color = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value
                                )
                                BodySmallText(
                                    modifier = Modifier.padding(top = SizeTokens.Level12),
                                    text = stringResource(id = R.string.restic_default_values_note),
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
                                    value = IndexViewModel.DEFAULT_RESTIC_PASSWORD,
                                    contentColor = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value,
                                )
                            }
                        }

                        // 黄色 M3 警示卡片：密码一致性与误删风险
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = SizeTokens.Level24)
                                .padding(top = SizeTokens.Level12),
                            colors = CardDefaults.cardColors(
                                containerColor = ThemedColorSchemeKeyTokens.YellowPrimaryContainer.value
                            ),
                        ) {
                            BodyMediumText(
                                modifier = Modifier.padding(SizeTokens.Level16),
                                text = stringResource(id = R.string.restic_existing_repo_warning),
                                color = ThemedColorSchemeKeyTokens.YellowOnPrimaryContainer.value
                            )
                        }
                    }
                }

                // 红色 M3 错误卡片：任何模式下初始化 / 打开仓库失败都用它提示
                AnimatedVisibility(
                    visible = initializationState is IndexViewModel.InitializationState.Error
                ) {
                    val errorMsg = (initializationState as? IndexViewModel.InitializationState.Error)?.message.orEmpty()
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SizeTokens.Level24)
                            .padding(top = SizeTokens.Level12),
                        colors = CardDefaults.cardColors(
                            containerColor = ThemedColorSchemeKeyTokens.RedPrimaryContainer.value
                        ),
                    ) {
                        BodyMediumText(
                            modifier = Modifier.padding(SizeTokens.Level16),
                            text = stringResource(id = R.string.error) + ": " + errorMsg,
                            color = ThemedColorSchemeKeyTokens.RedOnPrimaryContainer.value
                        )
                    }
                }
            }

            // Restic 密码配置
            Title(title = stringResource(id = R.string.restic_password)) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SizeTokens.Level24),
                    value = password,
                    onValueChange = { password = it },
                    enabled = isInitialized.not() && isInitializing.not(),
                    label = { Text(text = stringResource(id = R.string.restic_password)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = passwordVisible.not() }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                contentDescription = null
                            )
                        }
                    }
                )

                // 黄色 M3 警示卡片
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SizeTokens.Level24)
                        .padding(top = SizeTokens.Level12),
                    colors = CardDefaults.cardColors(
                        containerColor = ThemedColorSchemeKeyTokens.YellowPrimaryContainer.value
                    ),
                ) {
                    BodyMediumText(
                        modifier = Modifier.padding(SizeTokens.Level16),
                        text = stringResource(id = R.string.restic_password_warning),
                        color = ThemedColorSchemeKeyTokens.YellowOnPrimaryContainer.value
                    )
                }
            }

            val scope = rememberCoroutineScope()
            val compressionLevel by context.readResticCompressionLevel().collectAsStateWithLifecycle(initialValue = -1)
            val currentLevelLabel = when (compressionLevel) {
                -1 -> "AUTO"
                0 -> "OFF"
                else -> "L$compressionLevel"
            }
            val currentLevelText = stringResource(R.string.args_current_level, currentLevelLabel)
            val compressionDescText = stringResource(R.string.restic_compression_level_desc)

            Slideable(
                title = stringResource(id = R.string.restic_compression_level),
                value = compressionLevel.toFloat(),
                valueRange = -1F..22F,
                steps = 22,
                desc = currentLevelText
            ) {
                val level = it.roundToInt()
                scope.launch { context.saveResticCompressionLevel(level) }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .paddingHorizontal(SizeTokens.Level24),
                colors = CardDefaults.cardColors(
                    containerColor = ThemedColorSchemeKeyTokens.BluePrimaryContainer.value
                ),
            ) {
                BodyMediumText(
                    modifier = Modifier.padding(SizeTokens.Level16),
                    text = compressionDescText,
                    color = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value
                )
            }

            Title(title = stringResource(id = R.string.optional)) {
                AnimatedVisibility(visible = backupSavePathSaved) {
                    Clickable(
                        title = stringResource(id = R.string.configurations),
                        value = stringResource(id = R.string.configurations_desc),
                    ) {
                        navController.navigateSingle(SetupRoutes.Configurations.route)
                    }
                }
                Switchable(
                    key = KeyLoadSystemApps,
                    defValue = false,
                    title = stringResource(id = R.string.load_system_apps),
                    checkedText = stringResource(id = R.string.enabled),
                    notCheckedText = stringResource(id = R.string.not_enabled),
                )
            }
        }
    }
}

/**
 * M3E 风格的可复制数值行：小号标题 + 可选中（长按复制）的正文，右侧提供复制按钮。
 * 用于把「默认仓库路径 / 默认密码」这类需要核对、粘贴的值单独展示出来。
 */
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