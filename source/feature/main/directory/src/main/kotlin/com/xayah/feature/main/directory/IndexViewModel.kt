package com.xayah.feature.main.directory

import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.model.StorageType
import com.xayah.core.model.database.DirectoryEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * 状态定义：仅保留更新加载状态
 */
data class IndexUiState(
    val updating: Boolean,
) : UiState

/**
 * 意图定义：
 * 1. Update: 刷新目录列表
 * 2. Select: 确认选择路径（需在 UI 层判断是否为 User 0）
 */
sealed class IndexUiIntent : UiIntent {
    data object Update : IndexUiIntent()
    data class Select(val entity: DirectoryEntity) : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    rootService: RemoteRootService,
    private val directoryRepo: DirectoryRepository,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(IndexUiState(updating = true)) {

    init {
        // 根服务错误监听
        rootService.onFailure = {
            val msg = it.message
            if (msg != null)
                emitEffectOnIO(IndexUiEffect.ShowSnackbar(message = msg))
        }
    }

    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.Update -> {
                // 触发仓库层的目录扫描逻辑
                emitState(uiState.value.copy(updating = true))
                directoryRepo.update()
                emitState(uiState.value.copy(updating = false))
            }

            is IndexUiIntent.Select -> {
                // 执行路径选择逻辑，保存至 DataStore 并标记已完成初始化
                directoryRepo.selectDir(entity = intent.entity)
            }
        }
    }

    /**
     * 数据流：
     * 仅保留内部存储（INTERNAL）的目录流。
     * UI 层将通过此流获取所有的 user 路径（如 user/0, user/10 等）。
     */
    private val _internalDirectories: Flow<List<DirectoryEntity>> =
        directoryRepo.queryActiveDirectoriesFlow(StorageType.INTERNAL).flowOnIO()

    val internalDirectoriesState: StateFlow<List<DirectoryEntity>> =
        _internalDirectories.stateInScope(listOf())
}