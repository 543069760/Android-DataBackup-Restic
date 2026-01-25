package com.xayah.feature.setup.page.one

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import androidx.compose.material3.ExperimentalMaterial3Api
import com.topjohnwu.superuser.Shell
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.withLog
import com.xayah.feature.setup.EnvState
import com.xayah.feature.setup.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class IndexUiState(
    val abiErr: String
) : UiState

sealed class IndexUiIntent : UiIntent {
    data object ValidateRoot : IndexUiIntent()
    data object ValidateAbi : IndexUiIntent()
    data object OnResume : IndexUiIntent()
    data class ValidateNotification(val context: Context) : IndexUiIntent()
    // 新增：文件管理与电池优化验证意图
    data object ValidateManageExternalStorage : IndexUiIntent()
    data object ValidateBatteryOptimization : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(IndexUiState(abiErr = "")) {

    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.OnResume -> {
                mutex.withLock {
                    // 自动刷新所有状态
                    if (NotificationUtil.checkPermission(context)) _notificationState.value = EnvState.Succeed
                    _manageExternalStorageState.value = if (checkStorage()) EnvState.Succeed else EnvState.Idle
                    _batteryOptimizationState.value = if (checkBattery()) EnvState.Succeed else EnvState.Idle
                }
            }

            is IndexUiIntent.ValidateRoot -> {
                emitIntent(IndexUiIntent.ValidateAbi)
                mutex.withLock {
                    if (rootState.value != EnvState.Succeed) {
                        _rootState.value = EnvState.Processing
                        runCatching { BaseUtil.initializeEnvironment(context = context) }
                        runCatching { BaseUtil.kill(context, "${context.packageName}:root:daemon") }.withLog()
                        _rootState.value = if (runCatching { Shell.getShell().isRoot }.getOrElse { false }) EnvState.Succeed else EnvState.Failed
                    }
                }
            }

            is IndexUiIntent.ValidateAbi -> {
                mutex.withLock {
                    if (abiState.value != EnvState.Succeed) {
                        _abiState.value = EnvState.Processing
                        val buildABI = BuildConfigUtil.FLAVOR_abi
                        val deviceABI = Build.SUPPORTED_ABIS.firstOrNull().toString()
                        if (buildABI == deviceABI) {
                            _abiState.value = if (runCatching { BaseUtil.releaseBase(context = context) }.getOrElse { false }) {
                                emitState(state.copy(abiErr = ""))
                                EnvState.Succeed
                            } else EnvState.Failed
                        } else {
                            _abiState.value = EnvState.Failed
                            emitState(state.copy(abiErr = context.getString(R.string.this_version_only_supports_but_your_device_is_please_install_version, buildABI, deviceABI, deviceABI)))
                        }
                    }
                }
            }

            is IndexUiIntent.ValidateNotification -> {
                mutex.withLock {
                    if (notificationState.value != EnvState.Succeed)
                        NotificationUtil.requestPermissions(intent.context)
                }
            }

            is IndexUiIntent.ValidateManageExternalStorage -> {
                mutex.withLock { _manageExternalStorageState.value = if (checkStorage()) EnvState.Succeed else EnvState.Failed }
            }

            is IndexUiIntent.ValidateBatteryOptimization -> {
                mutex.withLock { _batteryOptimizationState.value = if (checkBattery()) EnvState.Succeed else EnvState.Failed }
            }
        }
    }

    private fun checkStorage(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    private fun checkBattery(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private val mutex = Mutex()

    // 显式指定类型为 <EnvState>，防止编译器锁死在 EnvState.Idle
    private val _rootState: MutableStateFlow<EnvState> = MutableStateFlow(EnvState.Idle)
    val rootState: StateFlow<EnvState> = _rootState.stateInScope(EnvState.Idle)

    private val _abiState: MutableStateFlow<EnvState> = MutableStateFlow(EnvState.Idle)
    val abiState: StateFlow<EnvState> = _abiState.stateInScope(EnvState.Idle)

    private val _notificationState: MutableStateFlow<EnvState> = MutableStateFlow(EnvState.Idle)
    val notificationState: StateFlow<EnvState> = _notificationState.stateInScope(EnvState.Idle)

    // 新增状态流也需要显式指定类型
    private val _manageExternalStorageState: MutableStateFlow<EnvState> = MutableStateFlow(EnvState.Idle)
    val manageExternalStorageState: StateFlow<EnvState> = _manageExternalStorageState.stateInScope(EnvState.Idle)

    private val _batteryOptimizationState: MutableStateFlow<EnvState> = MutableStateFlow(EnvState.Idle)
    val batteryOptimizationState: StateFlow<EnvState> = _batteryOptimizationState.stateInScope(EnvState.Idle)

    val allRequiredValidated: StateFlow<Boolean> = combine(_rootState, _abiState) { root, abi -> root == EnvState.Succeed && abi == EnvState.Succeed }.flowOnIO().stateInScope(false)

    // 聚合可选权限状态
    val allOptionalValidated: StateFlow<Boolean> = combine(_notificationState, _manageExternalStorageState, _batteryOptimizationState) { n, s, b ->
        n == EnvState.Succeed && s == EnvState.Succeed && b == EnvState.Succeed
    }.flowOnIO().stateInScope(false)
}