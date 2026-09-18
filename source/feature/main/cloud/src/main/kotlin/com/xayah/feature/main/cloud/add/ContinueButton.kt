package com.xayah.feature.main.cloud.add

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.feature.main.cloud.R

/**
 * 云端账户配置页统一的"继续"按钮。
 * 点击后执行耗时的仓库三步校验（exists/validate/check），
 * 期间按钮内显示 M3 圆形进度圈，并弹出持久化 Loading 吐司提示。
 *
 * @param enabled       各页面自行算好（含 allFilled / remote / !isProcessing / xxxInitState is Success）
 * @param onUpdateEntity 封装各自的 updateXxxEntity(...)
 * @param check         封装各自的 checkXxxRepository(entity, password)，返回是否校验通过
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContinueButton(
    viewModel: IndexViewModel,
    navController: NavHostController,
    enabled: Boolean,
    onUpdateEntity: suspend () -> Unit,
    check: suspend () -> Boolean,
) {
    var isChecking by rememberSaveable { mutableStateOf(false) }
    val repositoryCheckingText = stringResource(id = R.string.repository_checking)
    val repositoryCheckFailedText = stringResource(id = R.string.repository_check_failed)

    Button(
        enabled = enabled && isChecking.not(),
        onClick = {
            viewModel.launchOnIO {
                isChecking = true
                // 先清掉可能存在的旧吐司，再弹出持久化 Loading 吐司
                viewModel.emitEffect(IndexUiEffect.DismissSnackbar)
                viewModel.emitEffectOnIO(
                    IndexUiEffect.ShowSnackbar(
                        type = SnackbarType.Loading,
                        message = repositoryCheckingText,
                        duration = SnackbarDuration.Indefinite,
                    )
                )
                runCatching {
                    onUpdateEntity()
                    val ok = check()
                    viewModel.emitEffect(IndexUiEffect.DismissSnackbar)
                    if (ok) {
                        viewModel.emitIntent(IndexUiIntent.CreateAccount(navController = navController))
                    } else {
                        viewModel.emitEffectOnIO(
                            IndexUiEffect.ShowSnackbar(
                                message = repositoryCheckFailedText,
                                type = SnackbarType.Error,
                            )
                        )
                    }
                }.onFailure {
                    viewModel.emitEffect(IndexUiEffect.DismissSnackbar)
                    viewModel.emitEffectOnIO(
                        IndexUiEffect.ShowSnackbar(
                            message = it.localizedMessage ?: repositoryCheckFailedText,
                            type = SnackbarType.Error,
                            duration = SnackbarDuration.Long,
                        )
                    )
                }
                isChecking = false
            }
        }
    ) {
        if (isChecking) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = stringResource(id = R.string._continue))
    }
}
