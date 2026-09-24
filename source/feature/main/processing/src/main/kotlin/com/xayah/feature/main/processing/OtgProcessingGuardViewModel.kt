package com.xayah.feature.main.processing

import android.util.Log
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
class OtgProcessingGuardViewModel @Inject constructor(
    private val resticRepoLocator: ResticRepoLocator,
    private val directoryRepo: DirectoryRepository,
) : ViewModel() {

    private val _otgDisconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val otgDisconnected: SharedFlow<Unit> = _otgDisconnected.asSharedFlow()

    // 记录上一次 OTG 盘在场状态，用于检测 true → false 的下降沿
    private var lastPresent: Boolean? = null

    init {
        viewModelScope.launch {
            resticRepoLocator.otgMountEvents().collect {
                val present = runCatching { directoryRepo.hasLiveExternalStorage() }
                    .getOrDefault(false)
                val prev = lastPresent
                lastPresent = present
                Log.d("CancelFallback", "OtgProcessingGuard: prev=$prev present=$present")
                if (prev == true && !present) {
                    _otgDisconnected.tryEmit(Unit)
                }
            }
        }
    }
}