package com.xayah.feature.setup.page.two

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.xayah.core.ui.component.Card
import com.xayah.core.ui.component.Clickable
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
    val backupSavePath by context.readBackupSavePath().collectAsStateWithLifecycle(initialValue = "")
    val initializationState by viewModel.initializationState.collectAsStateWithLifecycle()
    val notSelectedText = stringResource(id = R.string.not_selected)

    var password by rememberSaveable { mutableStateOf(IndexViewModel.DEFAULT_RESTIC_PASSWORD) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    val isInitialized = initializationState is IndexViewModel.InitializationState.ReadyToUse
    val isInitializing = initializationState is IndexViewModel.InitializationState.Preparing ||
            initializationState is IndexViewModel.InitializationState.Initializing

    SetupScaffold(
        topBar = {
            SecondaryLargeTopBar(
                scrollBehavior = null,
                title = stringResource(id = R.string.setup)
            )
        },
        actions = {
            // 初始化按钮：必须先点，成功前不可点完成
            Button(
                enabled = isInitialized.not() && isInitializing.not(),
                onClick = {
                    viewModel.emitIntentOnIO(IndexUiIntent.Initialize(password = password))
                }
            ) {
                Text(text = stringResource(id = R.string.initialize))
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
            // 备份目录 - 只读展示
            Clickable(
                title = stringResource(id = R.string.backup_dir),
                value = if (backupSavePathSaved) backupSavePath else notSelectedText,
                desc = if (backupSavePathSaved) null else stringResource(id = R.string.setup_backup_dir_desc),
                enabled = false
            ) {}

            // Restic 仓库路径 - 只读展示
            Clickable(
                title = stringResource(id = R.string.restic_repository),
                value = when (val s = initializationState) {
                    is IndexViewModel.InitializationState.ReadyToUse -> s.repoPath
                    is IndexViewModel.InitializationState.Error -> stringResource(id = R.string.error) + ": " + s.message
                    IndexViewModel.InitializationState.Preparing,
                    IndexViewModel.InitializationState.Initializing -> stringResource(id = R.string.initializing)
                    IndexViewModel.InitializationState.Idle -> "-"
                },
                desc = ConstantUtil.DEFAULT_RUSTIC_REPO_ROOT + "/restic_repo",
                enabled = false
            ) {}

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