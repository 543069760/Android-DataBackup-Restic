package com.xayah.feature.main.restore

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.restic.ResticRepository
import com.xayah.feature.main.restore.ResticFileBackupGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

sealed class ResticFilesRestoreUiState {
    object Loading : ResticFilesRestoreUiState()
    data class Success(val groups: List<ResticFileBackupGroup>) : ResticFilesRestoreUiState()
    data class Error(val message: String) : ResticFilesRestoreUiState()
}

@HiltViewModel
class ResticFilesRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepo: ResticRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ResticFilesRestoreUiState>(ResticFilesRestoreUiState.Loading)
    val uiState: StateFlow<ResticFilesRestoreUiState> = _uiState.asStateFlow()

    fun loadBackedUpFiles() {
        viewModelScope.launch {
            _uiState.value = ResticFilesRestoreUiState.Loading

            val repoPath = context.readResticRepoPath()
            val password = context.readResticPassword()

            if (repoPath.isNullOrEmpty() || password.isNullOrEmpty()) {
                _uiState.value = ResticFilesRestoreUiState.Error("Restic not configured")
                return@launch
            }

            try {
                val files = resticRepo.listBackedUpFiles(repoPath, password)

                // 按媒体名称+前缀路径+时间戳分组
                val groupedByPath = files
                    .groupBy {
                        // 提取前缀路径（去掉最后一层）
                        val prefixPath = it.fullPath.substringBeforeLast("/")
                        Triple(it.mediaName, prefixPath, it.timestamp)
                    }
                    .map { (groupKey, backups) ->
                        val (mediaName, prefixPath, timestamp) = groupKey
                        ResticFileBackupGroup(
                            mediaName = mediaName,
                            fullPath = prefixPath, // 使用前缀路径用于分组逻辑
                            timestamp = timestamp,
                            backups = backups, // 直接使用backups
                            mediaLabel = mediaName
                        )
                    }
                    .sortedByDescending { it.timestamp }

                _uiState.value = ResticFilesRestoreUiState.Success(groupedByPath)
            } catch (e: Exception) {
                _uiState.value = ResticFilesRestoreUiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}