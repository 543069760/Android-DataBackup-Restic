package com.xayah.feature.benchmark

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.datastore.readCompressionBenchmark
import com.xayah.core.datastore.readResticCompressionLevel
import com.xayah.core.datastore.saveCompressionBenchmark
import com.xayah.core.datastore.saveResticCompressionLevel
import com.xayah.core.model.BenchmarkReport
import com.xayah.core.model.BenchmarkSample
import com.xayah.core.service.util.BENCHMARK_LEVELS
import com.xayah.core.service.util.CompressionBenchmarkUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class CompressionBenchmarkViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    private val benchmarkUtil: CompressionBenchmarkUtil,
) : ViewModel() {
    /**
     * 单一 UiState：running / report 两个字段足以派生三态
     * （运行中 / 从未测过 / 有结果），不再额外引入 sealed class。
     */
    data class UiState(
        val running: Boolean = false,
        val level: Int = -1,
        val index: Int = 0,
        val total: Int = BENCHMARK_LEVELS.size,
        val detail: String = "",
        val samples: List<BenchmarkSample> = emptyList(),
        val report: BenchmarkReport? = null,
        val currentLevel: Int = -1,
        val error: String? = null,
        val finished: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            context.readCompressionBenchmark().collect { report ->
                _uiState.update { it.copy(report = report) }
            }
        }
        viewModelScope.launch {
            context.readResticCompressionLevel().collect { level ->
                _uiState.update { it.copy(currentLevel = level) }
            }
        }
    }

    fun start() {
        if (job?.isActive == true) return
        _uiState.update {
            it.copy(
                running = true,
                finished = false,
                error = null,
                samples = emptyList(),
                level = -1,
                index = 0,
                detail = "",
            )
        }
        job = viewModelScope.launch {
            try {
                val report = benchmarkUtil.run { progress ->
                    _uiState.update { state ->
                        state.copy(
                            level = progress.level,
                            index = progress.index,
                            total = progress.total,
                            samples = progress.samples,
                            detail = progress.detail,
                        )
                    }
                }
                context.saveCompressionBenchmark(report)
                _uiState.update {
                    it.copy(
                        running = false,
                        finished = true,
                        report = report,
                        samples = report.samples,
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                _uiState.update { it.copy(running = false, finished = false) }
                throw e
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(running = false, error = e.message ?: e.toString())
                }
            }
        }
    }

    fun cancel() {
        benchmarkUtil.cancel()
        job?.cancel()
        _uiState.update { it.copy(running = false, finished = false) }
    }

    /**
     * 把推荐档位写回设置。已存在的仓库不会重写压缩设置，需重新初始化才生效。
     *
     * report.recommendedLevel 的 -1 表示「本机压缩不划算」，它的落地目标就是 0(OFF)，
     * 不是「没有推荐」。原来这里直接 return，等于把这条结论吞掉：界面写着「不建议压缩」，
     * 设置项却一直停在 AUTO，用户只能自己去拖滑块。
     */
    fun applyRecommendation() {
        val recommended = _uiState.value.report?.recommendedLevel ?: return
        val target = if (recommended < 0) 0 else recommended
        // 只写设置。「是否已应用」交给 UI 用 currentLevel == targetLevel 派生：
        // 单独记一次性标记的话，用户应用完再手动拖走，按钮会一直停在「已应用」。
        viewModelScope.launch {
            context.saveResticCompressionLevel(target)
        }
    }

    fun clearReport() {
        viewModelScope.launch {
            context.saveCompressionBenchmark(null)
            _uiState.update { it.copy(report = null, finished = false, samples = emptyList()) }
        }
    }
}
