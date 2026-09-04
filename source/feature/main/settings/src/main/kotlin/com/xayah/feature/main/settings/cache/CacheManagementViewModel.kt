package com.xayah.feature.main.settings.cache

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CacheManagementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
) : ViewModel() {

    data class CacheInfo(
        val restoreCacheSize: Long = 0L,
        val isCalculating: Boolean = false
    )

    private val _cacheInfo = MutableStateFlow(CacheInfo())
    val cacheInfo: StateFlow<CacheInfo> = _cacheInfo.asStateFlow()

    // 计算缓存大小
    fun calculateCacheSize() {
        viewModelScope.launch {
            _cacheInfo.update { it.copy(isCalculating = true) }

            // 计算恢复中转目录大小
            val restoreCacheSize = rootService.calculateSize("${context.localBackupSaveDir()}/restore")

            _cacheInfo.update {
                it.copy(
                    restoreCacheSize = restoreCacheSize,
                    isCalculating = false
                )
            }
        }
    }

    // 清除恢复中转缓存
    fun clearRestoreCache() {
        viewModelScope.launch {
            rootService.deleteRecursively("${context.localBackupSaveDir()}/restore")
            calculateCacheSize()
        }
    }
}