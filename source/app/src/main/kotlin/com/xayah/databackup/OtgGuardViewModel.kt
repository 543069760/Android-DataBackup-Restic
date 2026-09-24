package com.xayah.databackup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.data.repository.ResticRepoLocator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OtgGuardViewModel @Inject constructor(
    private val resticRepoLocator: ResticRepoLocator,
    private val directoryRepo: DirectoryRepository,
) : ViewModel() {
    private val _otgDisconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val otgDisconnected: SharedFlow<Unit> = _otgDisconnected.asSharedFlow()

    init {
        viewModelScope.launch {
            // 记录上一次在场状态，首个事件建立基线不误报
            var lastPresent = runCatching { directoryRepo.hasLiveExternalStorage() }.getOrDefault(false)
            resticRepoLocator.otgMountEvents().collect {
                val present = runCatching { directoryRepo.hasLiveExternalStorage() }.getOrDefault(false)
                if (lastPresent && !present) {
                    _otgDisconnected.emit(Unit) // 仅 true→false（拔出）时发信号
                }
                lastPresent = present
            }
        }
    }
}