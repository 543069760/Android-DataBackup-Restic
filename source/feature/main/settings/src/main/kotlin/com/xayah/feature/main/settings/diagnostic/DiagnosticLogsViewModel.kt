package com.xayah.feature.main.settings.diagnostic

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.rootservice.util.RootDeathCollector
import com.xayah.core.rootservice.util.RootDeathLog
import com.xayah.core.rootservice.util.RootDeathLogStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 刷新按钮的最小转圈时长：读目录快到看不见，补到这么久才像一个正常的加载反馈。 */
private const val MIN_SPIN_MS = 400L

@HiltViewModel
class DiagnosticLogsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _logs = MutableStateFlow<List<RootDeathLog>>(emptyList())
    val logs: StateFlow<List<RootDeathLog>> = _logs.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _refreshing.value = true
            try {
                // 读目录通常只要几毫秒，短到根本看不见；补足到最小可见时长，
                // 否则按钮看起来像没响应，用户会以为点击丢失。
                val startedAt = System.currentTimeMillis()
                _logs.value = RootDeathLogStore.list(context)
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed < MIN_SPIN_MS) delay(MIN_SPIN_MS - elapsed)
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun delete(log: RootDeathLog) {
        viewModelScope.launch(Dispatchers.IO) {
            RootDeathLogStore.delete(context, log)
            _logs.value = RootDeathLogStore.list(context)
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            RootDeathLogStore.clear(context)
            _logs.value = RootDeathLogStore.list(context)
        }
    }

    /** 立刻抓一份现场，用于验证取证链路（不需要真的让守护进程崩一次）。 */
    fun captureNow() {
        if (_busy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                RootDeathCollector.captureNow(context, "manual")
            } finally {
                _busy.value = false
            }
            _logs.value = RootDeathLogStore.list(context)
        }
    }
}
