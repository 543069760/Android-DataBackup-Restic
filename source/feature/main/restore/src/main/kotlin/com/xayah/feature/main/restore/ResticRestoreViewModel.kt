package com.xayah.feature.main.restore

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.restic.ResticRepository
import com.xayah.core.util.DateUtil
import com.xayah.feature.main.restore.ResticBackupGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@HiltViewModel
class ResticRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepo: ResticRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ResticRestoreUiState>(ResticRestoreUiState.Loading)
    val uiState: StateFlow<ResticRestoreUiState> = _uiState.asStateFlow()

    fun loadBackedUpApps() {
        viewModelScope.launch {
            _uiState.value = ResticRestoreUiState.Loading

            val repoPath = context.readResticRepoPath()
            val password = context.readResticPassword()

            if (repoPath.isNullOrEmpty() || password.isNullOrEmpty()) {
                _uiState.value = ResticRestoreUiState.Error("Restic not configured")
                return@launch
            }

            try {
                val apps = resticRepo.listBackedUpApps(repoPath, password)
                // 按 (userId, packageName, timestamp) 分组
                val groupedBackups = apps
                    .groupBy { "${it.userId}-${it.packageName}-${it.timestamp}" }
                    .values
                    .map { backups ->
                        val first = backups.first()
                        ResticBackupGroup(
                            packageName = first.packageName,
                            userId = first.userId,
                            timestamp = first.timestamp,
                            backups = backups.sortedBy { it.dataType.type },
                            appLabel = backups.firstOrNull()?.let { backup ->
                                // 通过 PackageManager 获取应用标签
                                try {
                                    val pm = context.packageManager
                                    val packageInfo = pm.getPackageInfo(backup.packageName, 0)
                                    packageInfo.applicationInfo?.loadLabel(pm)?.toString() ?: backup.packageName
                                } catch (e: Exception) {
                                    backup.packageName
                                }
                            } ?: first.packageName
                        )
                    }
                    .sortedByDescending { it.timestamp }

                _uiState.value = ResticRestoreUiState.Success(groupedBackups)
            } catch (e: Exception) {
                _uiState.value = ResticRestoreUiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}
    // 修改 UI 状态
    sealed interface ResticRestoreUiState {
        object Loading : ResticRestoreUiState
        data class Success(val groups: List<ResticBackupGroup>) : ResticRestoreUiState
        data class Error(val message: String) : ResticRestoreUiState
    }