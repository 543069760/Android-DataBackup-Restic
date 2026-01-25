package com.xayah.feature.setup.page.one

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.datastore.readCustomSUFile
import com.xayah.core.datastore.saveCustomSUFile
import com.xayah.core.ui.component.AppIcon
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.HeadlineMediumText
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.Section
import com.xayah.core.ui.component.SetOnResume
import com.xayah.core.ui.component.edit
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.navigateSingle
import com.xayah.feature.setup.PermissionButton
import com.xayah.feature.setup.R
import com.xayah.feature.setup.SetupRoutes
import com.xayah.feature.setup.SetupScaffold
import kotlinx.coroutines.flow.first

@ExperimentalFoundationApi
@ExperimentalMaterial3Api
@Composable
fun PageOne() {
    val navController = LocalNavController.current!!
    val context = LocalContext.current
    val viewModel = hiltViewModel<IndexViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val rootState by viewModel.rootState.collectAsStateWithLifecycle()
    val abiState by viewModel.abiState.collectAsStateWithLifecycle()
    val notificationState by viewModel.notificationState.collectAsStateWithLifecycle()
    val manageExternalStorageState by viewModel.manageExternalStorageState.collectAsStateWithLifecycle()
    val batteryOptimizationState by viewModel.batteryOptimizationState.collectAsStateWithLifecycle()
    val allRequiredValidated by viewModel.allRequiredValidated.collectAsStateWithLifecycle()
    val allOptionalValidated by viewModel.allOptionalValidated.collectAsStateWithLifecycle()
    val dialogState = LocalSlotScope.current!!.dialogSlot

    SetOnResume {
        viewModel.emitIntentOnIO(IndexUiIntent.OnResume)
    }

    SetupScaffold(
        actions = {
            AnimatedVisibility(visible = allRequiredValidated.not() || allOptionalValidated.not()) {
                OutlinedButton(
                    onClick = {
                        viewModel.launchOnIO {
                            viewModel.emitIntent(IndexUiIntent.ValidateRoot)
                            viewModel.emitIntent(IndexUiIntent.ValidateAbi)
                            viewModel.emitIntent(IndexUiIntent.ValidateNotification(context = context))
                            viewModel.emitIntent(IndexUiIntent.ValidateManageExternalStorage)
                            viewModel.emitIntent(IndexUiIntent.ValidateBatteryOptimization)
                        }
                    }
                ) {
                    Text(text = stringResource(id = R.string.grant_all))
                }
            }
            Button(enabled = allRequiredValidated, onClick = { navController.navigateSingle(SetupRoutes.Two.route) }) {
                Text(text = stringResource(id = R.string._continue))
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
        ) {
            Column(
                modifier = Modifier.paddingTop(SizeTokens.Level100),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AppIcon()
                HeadlineMediumText(modifier = Modifier.paddingTop(SizeTokens.Level12), text = stringResource(id = R.string.welcome_to_use))
                BodyMediumText(text = stringResource(id = R.string.app_short_desc), color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value)
            }

            Spacer(modifier = Modifier.size(SizeTokens.Level24))

            Section(title = stringResource(id = R.string.required)) {
                PermissionButton(
                    title = stringResource(id = R.string.root_permission),
                    desc = stringResource(id = R.string.root_permission_desc),
                    envState = rootState,
                    onSetting = {
                        viewModel.launchOnIO {
                            val (state, su) = dialogState.edit(
                                title = context.getString(R.string.custom_su_file),
                                defValue = context.readCustomSUFile().first(),
                                label = context.getString(R.string.name),
                                desc = context.getString(R.string.restart_to_take_effect)
                            )
                            if (state.isConfirm) context.saveCustomSUFile(su)
                        }
                    }
                ) {
                    viewModel.launchOnIO { viewModel.emitIntent(IndexUiIntent.ValidateRoot) }
                }
                PermissionButton(
                    title = stringResource(id = R.string.abi_validation),
                    desc = uiState.abiErr.ifEmpty { context.getString(R.string.abi_validation_desc) },
                    envState = abiState,
                ) {
                    viewModel.launchOnIO { viewModel.emitIntent(IndexUiIntent.ValidateAbi) }
                }
            }

            Section(title = stringResource(id = R.string.optional)) {
                PermissionButton(
                    title = stringResource(id = R.string.notification_permission),
                    desc = stringResource(id = R.string.notification_permission_desc),
                    envState = notificationState,
                ) {
                    viewModel.launchOnIO { viewModel.emitIntent(IndexUiIntent.ValidateNotification(context = context)) }
                }

                PermissionButton(
                    title = stringResource(id = R.string.manage_external_storage_permission),
                    desc = stringResource(id = R.string.manage_external_storage_permission_desc),
                    envState = manageExternalStorageState,
                    onSetting = {
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            // 这种方式会直接打开属于你应用的那个开关页，而不是列表页
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        } else {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        }
                        runCatching { context.startActivity(intent) }.onFailure {
                            // 备用方案：万一某些魔改系统不支持直达，回退到列表页
                            val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            context.startActivity(fallbackIntent)
                        }
                    }
                ) {
                    viewModel.launchOnIO { viewModel.emitIntent(IndexUiIntent.ValidateManageExternalStorage) }
                }

                PermissionButton(
                    title = stringResource(id = R.string.battery_optimization_permission),
                    desc = stringResource(id = R.string.battery_optimization_permission_desc),
                    envState = batteryOptimizationState,
                    onSetting = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${context.packageName}") }
                        context.startActivity(intent)
                    }
                ) {
                    viewModel.launchOnIO { viewModel.emitIntent(IndexUiIntent.ValidateBatteryOptimization) }
                }
            }
        }
    }
}