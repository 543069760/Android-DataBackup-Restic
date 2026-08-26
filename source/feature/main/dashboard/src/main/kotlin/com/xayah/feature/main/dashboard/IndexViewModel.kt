package com.xayah.feature.main.dashboard

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.datastore.readLastBackupTime
import com.xayah.core.datastore.readUpdateChannel
import com.xayah.core.model.database.DirectoryEntity
import com.xayah.core.network.model.Release
import com.xayah.core.network.retrofit.GitHubRepository
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.toBrowser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

data class IndexUiState(
    val latestRelease: Release? = null,
) : UiState

sealed class IndexUiIntent : UiIntent {
    data object Update : IndexUiIntent()
    data class ToBrowser(val context: Context, val url: String) : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val directoryRepo: DirectoryRepository,
    private val githubRepo: GitHubRepository,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(IndexUiState(latestRelease = null)) {
    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.Update -> {
                directoryRepo.updateSelected()
                runCatching {
                    // 0 = 正式版通道, 1 = 测试版通道
                    val channel = context.readUpdateChannel().first()
                    val release: Release? = if (channel == 1) {
                        // 测试版：全部 release（含 pre-release 与正式版）中取 build code 最大的
                        githubRepo.getReleases().maxByOrNull { parseReleaseCode(it) }
                    } else {
                        // 正式版：releases/latest 本身排除 pre-release
                        githubRepo.getLatestRelease()
                    }
                    val remoteCode = release?.let { parseReleaseCode(it) } ?: Long.MIN_VALUE
                    if (release != null && remoteCode > BuildConfigUtil.VERSION_CODE) {
                        emitState(state.copy(latestRelease = release))
                    } else {
                        emitState(state.copy(latestRelease = null))
                    }
                }
            }

            is IndexUiIntent.ToBrowser -> {
                runCatching { intent.context.toBrowser(intent.url) }.onFailure { emitEffect(IndexUiEffect.ShowSnackbar(message = context.getString(R.string.no_browser))) }
            }
        }
    }

    /**
     * 从 release 中解析出 CI 生成的 build code。
     * 优先取 tagName（形如 v3.0.0-S3-revived.30000）中最后一个 "." 之后的数字；
     * 否则从 name（形如 v3.0.0-S3-revived (Build 30000)）中用正则提取。
     * 解析失败返回 Long.MIN_VALUE，避免对不含 code 的旧 release 误报升级。
     */
    private fun parseReleaseCode(release: Release): Long {
        release.tagName.substringAfterLast('.', "").toLongOrNull()?.let { return it }
        Regex("Build\\s+(\\d+)").find(release.name)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
        return Long.MIN_VALUE
    }

    private val _lastBackupTime: Flow<Long> = context.readLastBackupTime().flowOnIO()
    val lastBackupTimeState: StateFlow<Long> = _lastBackupTime.stateInScope(0)

    private val _directory: Flow<DirectoryEntity?> = directoryRepo.querySelectedByDirectoryTypeFlow().flowOnIO()
    val directoryState: StateFlow<DirectoryEntity?> = _directory.stateInScope(null)
}