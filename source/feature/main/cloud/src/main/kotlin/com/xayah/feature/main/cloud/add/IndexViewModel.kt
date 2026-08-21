package com.xayah.feature.main.cloud.add

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.model.CloudType
import com.xayah.core.model.SFTPAuthMode
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.FTPExtra
import com.xayah.core.model.database.SFTPExtra
import com.xayah.core.model.database.WebDAVExtra
import com.xayah.core.model.database.WebDAVProtocol
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.database.S3Protocol
import com.xayah.core.network.client.getCloud
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.decodeURL
import com.xayah.feature.main.cloud.R
import com.xayah.core.model.database.S3NetworkType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class IndexUiState(
    val currentName: String,
    val cloudEntity: CloudEntity?,
    val isProcessing: Boolean,
) : UiState

sealed class IndexUiIntent : UiIntent {
    data object Initialize : IndexUiIntent()

    data class UpdateEntity(
        val name: String,
        val remote: String,
        val type: CloudType,
        val url: String,
        val username: String,
        val password: String,
        val extra: String,
    ) : IndexUiIntent()

    data class CreateAccount(val navController: NavHostController) : IndexUiIntent()

    data object TestConnection : IndexUiIntent()
    data class DeleteAccount(val navController: NavHostController) : IndexUiIntent()
    data class SetRemotePath(val context: Context) : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    private val cloudRepo: CloudRepository,
    args: SavedStateHandle,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(
    IndexUiState(
        currentName = args.get<String>(MainRoutes.ARG_ACCOUNT_NAME)?.decodeURL()?.trim() ?: "",
        cloudEntity = null,
        isProcessing = false,
    )
) {

    suspend fun updateFTPEntity(
        name: String, remote: String, url: String,
        username: String, password: String, port: String,
        resticPassword: String = "",          // 新增：账户级 restic 仓库密码
    ) {
        val extra = GsonUtil().toJson(
            FTPExtra(
                port = port.toIntOrNull() ?: 21,
                resticPassword = resticPassword, // 新增
            )
        )
        emitIntent(
            IndexUiIntent.UpdateEntity(
                name = name,
                type = CloudType.FTP,
                url = url,
                username = username,
                password = password,
                extra = extra,
                remote = remote,
            )
        )
    }

    suspend fun updateSFTPEntity(
        name: String, remote: String, url: String,
        username: String, password: String, port: String,
        mode: SFTPAuthMode, privateKey: String,
        resticPassword: String = "",          // 新增：账户级 restic 仓库密码
    ) {
        val extra = GsonUtil().toJson(
            SFTPExtra(
                port = port.toIntOrNull() ?: 22,
                privateKey = privateKey,
                mode = mode,
                resticPassword = resticPassword, // 新增
            )
        )
        emitIntent(
            IndexUiIntent.UpdateEntity(
                name = name,
                type = CloudType.SFTP,
                url = url,
                username = username,
                password = password,
                extra = extra,
                remote = remote,
            )
        )
    }

    suspend fun updateWebDAVEntity(
        name: String,
        remote: String,
        url: String,
        username: String,
        password: String,
        insecure: Boolean,
        protocol: WebDAVProtocol,               // 新增
        resticPassword: String = "",            // 新增
    ) {
        val extra = GsonUtil().toJson(
            WebDAVExtra(
                insecure = insecure,
                protocol = protocol,
                resticPassword = resticPassword,
            )
        )
        // 入参 url 是"纯主机地址"。先剥离用户可能残留的 scheme，再按协议拼完整 URL 落库。
        val cleanHost = url.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")
        val scheme = if (protocol == WebDAVProtocol.HTTPS) "https" else "http"
        val fullUrl = "$scheme://$cleanHost"
        emitIntent(
            IndexUiIntent.UpdateEntity(
                name = name,
                type = CloudType.WEBDAV,
                url = fullUrl,                  // 最终落 CloudEntity.host
                username = username,
                password = password,
                extra = extra,
                remote = remote,
            )
        )
    }

    suspend fun updateS3Entity(
        name: String, remote: String, type: String,
        region: String, accessKeyId: String, secretAccessKey: String,
        bucket: String, endpoint: String,
        protocol: S3Protocol, networkType: S3NetworkType,
        resticPassword: String,               // 新增
    ) {
        val extra = GsonUtil().toJson(
            S3Extra(
                type = type, region = region, accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey, bucket = bucket, endpoint = endpoint,
                protocol = protocol, networkType = networkType,
                resticPassword = resticPassword, // 新增
            )
        )
        emitIntent(
            IndexUiIntent.UpdateEntity(
                name = name,
                type = CloudType.S3,
                url = bucket,
                username = accessKeyId,
                password = secretAccessKey,
                extra = extra,
                remote = remote,
            )
        )
    }

    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.Initialize -> {
                if (uiState.value.currentName.isNotEmpty()) {
                    emitState(state.copy(cloudEntity = cloudRepo.queryByName(uiState.value.currentName)))
                }
            }

            is IndexUiIntent.CreateAccount -> {
                cloudRepo.upsert(state.cloudEntity!!)
                withMainContext {
                    intent.navController.popBackStack()
                    if (state.currentName.isEmpty()) {
                        intent.navController.popBackStack()
                    }
                }
            }

            is IndexUiIntent.UpdateEntity -> {
                emitState(
                    state.copy(
                        cloudEntity = CloudEntity(
                            name = intent.name,
                            type = intent.type,
                            host = intent.url,
                            user = intent.username,
                            pass = intent.password,
                            remote = intent.remote,
                            extra = intent.extra,
                            activated = false,
                        )
                    )
                )
            }

            is IndexUiIntent.TestConnection -> {
                emitState(state.copy(isProcessing = true))
                emitEffect(IndexUiEffect.DismissSnackbar)
                emitEffectOnIO(
                    IndexUiEffect.ShowSnackbar(
                        type = SnackbarType.Loading,
                        message = cloudRepo.getString(R.string.processing),
                        duration = SnackbarDuration.Indefinite,
                    )
                )
                runCatching {
                    // 明确指定 skipRemoteCheck = true,测试连接不需要远程路径
                    cloudRepo.withClient(state.cloudEntity!!, skipRemoteCheck = true) { client, _ ->
                        client.testConnection()
                    }
                    emitEffect(IndexUiEffect.DismissSnackbar)
                    emitEffectOnIO(IndexUiEffect.ShowSnackbar(type = SnackbarType.Success, message = cloudRepo.getString(R.string.connection_established)))
                }.onFailure {
                    emitEffect(IndexUiEffect.DismissSnackbar)
                    if (it.localizedMessage != null)
                        emitEffectOnIO(IndexUiEffect.ShowSnackbar(type = SnackbarType.Error, message = it.localizedMessage!!, duration = SnackbarDuration.Long))
                }
                emitState(state.copy(isProcessing = false))
            }

            is IndexUiIntent.DeleteAccount -> {
                cloudRepo.delete(cloudRepo.queryByName(uiState.value.currentName)!!)
                withMainContext {
                    intent.navController.popBackStack()
                }
            }

            is IndexUiIntent.SetRemotePath -> {
                emitState(uiState.value.copy(isProcessing = true))
                val context = intent.context
                emitEffect(IndexUiEffect.DismissSnackbar)
                emitEffectOnIO(
                    IndexUiEffect.ShowSnackbar(
                        message = cloudRepo.getString(R.string.processing),
                        duration = SnackbarDuration.Indefinite,
                        type = SnackbarType.Loading,
                    )
                )
                runCatching {
                    // 使用新的重载方法,直接传入 CloudEntity,跳过远程路径检查
                    cloudRepo.withClient(uiState.value.cloudEntity!!, skipRemoteCheck = true) { client, entity ->
                        client.setRemote(context) { remote, extraString ->
                            emitState(uiState.value.copy(cloudEntity = entity.copy(remote = remote, extra = extraString)))
                            emitEffect(IndexUiEffect.DismissSnackbar)
                        }
                    }
                }.onFailure {
                    emitEffect(IndexUiEffect.DismissSnackbar)
                    if (it.localizedMessage != null)
                        emitEffectOnIO(IndexUiEffect.ShowSnackbar(type = SnackbarType.Error, message = it.localizedMessage!!, duration = SnackbarDuration.Long))
                }
                emitState(uiState.value.copy(isProcessing = false))
            }
        }
    }
}
