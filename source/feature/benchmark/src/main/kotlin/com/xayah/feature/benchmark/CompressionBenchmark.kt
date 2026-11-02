package com.xayah.feature.benchmark

import android.content.Context
import android.os.Build
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.model.BenchmarkReport
import com.xayah.core.model.BenchmarkSample
import com.xayah.feature.benchmark.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 压缩档位基准的可嵌入内容：三态（未测过 / 运行中 / 有结果）。
 *
 * 只做展示与转发意图，判定与探测全在 CompressionBenchmarkViewModel + CompressionBenchmarkUtil。
 * 设置页与首次引导页共用这一份 UI，避免两处各写一套表格与推荐卡片。
 *
 * @param viewModel 允许外部传入实例：引导页需要同时观察 [CompressionBenchmarkViewModel.uiState]，
 *                  在测试产出可靠报告之前锁住压缩等级滑块与「完成」按钮。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun CompressionBenchmarkSection(
    modifier: Modifier = Modifier,
    viewModel: CompressionBenchmarkViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.error?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = stringResource(id = R.string.benchmark_error, message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        when {
            state.running -> RunningSection(state = state, onCancel = viewModel::cancel)
            state.report != null -> ResultSection(
                state = state,
                onRetest = viewModel::start,
                onApply = viewModel::applyRecommendation,
            )
            else -> IdleSection(onStart = viewModel::start)
        }

        Text(
            text = stringResource(id = R.string.benchmark_battery_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IdleSection(onStart: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(id = R.string.benchmark_intro_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(id = R.string.benchmark_intro_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(id = R.string.benchmark_start))
            }
        }
    }
}

@Composable
private fun RunningSection(
    state: CompressionBenchmarkViewModel.UiState,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    // level < 0 表示还在备料/预热，没有档位号可报
                    text = if (state.level >= 0) {
                        stringResource(
                            id = R.string.benchmark_progress,
                            state.index + 1,
                            state.total,
                            levelLabel(state.level),
                        )
                    } else {
                        phaseLabel(state.detail)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (state.level >= 0) {
                Text(
                    text = phaseLabel(state.detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 与结果页一致：只拿可靠样本当基准，否则进行中会算出离谱的比值
            val off = state.samples.firstOrNull { it.level == 0 && it.reliable }
            state.samples.forEach { sample ->
                SampleRow(sample = sample, offSample = off)
                Spacer(modifier = Modifier.height(2.dp))
            }
            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(id = R.string.benchmark_cancel))
            }
        }
    }
}

@Composable
private fun ResultSection(
    state: CompressionBenchmarkViewModel.UiState,
    onRetest: () -> Unit,
    onApply: () -> Unit,
) {
    val report = state.report ?: return
    val recommendedLevel = report.recommendedLevel
    // -1「不值得压缩」的落地目标就是 OFF(0)，与 ViewModel.applyRecommendation 保持一致
    val targetLevel = if (recommendedLevel < 0) 0 else recommendedLevel
    // 「应用」的唯一含义是「回到推荐档位」，与滑块的手动调整完全解耦：
    // 是否已应用直接由「当前档位 == 推荐档位」派生，而不是记一个一次性标记。
    // 这样用户手动拖到别的档位后按钮会重新亮起，再点一次就跳回推荐档位。
    val applied = state.currentLevel == targetLevel
    val targetLabel = levelLabel(targetLevel)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = report.deviceModel, style = MaterialTheme.typography.titleMedium)
            if (report.socModel.isNotBlank()) {
                Text(
                    text = stringResource(id = R.string.benchmark_soc, report.socModel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(id = R.string.benchmark_measured_at, formatTime(report.measuredAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            TableHeader()
            val off = report.samples.firstOrNull { it.level == 0 && it.reliable }
            report.samples.forEach { sample ->
                SampleRow(
                    sample = sample,
                    offSample = off,
                    // 推荐档位整行用 M3 container 色标记：五行的数字常常只差几百 KB，
                    // 不标出来用户根本找不到「推荐的是哪一行」。
                    highlighted = sample.reliable && sample.level == recommendedLevel,
                )
            }
        }
    }

    // 绝对吞吐含 ~600 ms 固定开销（建库 open、snapshot finalize、tar spawn），
    // 对低档位是系统性低估；相对值以 OFF 为 1.00，固定开销在比值里部分抵消但仍会放大高档位优势。
    Text(
        text = stringResource(id = R.string.benchmark_footnote),
        modifier = Modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ReportWarnings(report = report)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (recommendedLevel >= 0) {
                Text(
                    text = stringResource(id = R.string.benchmark_recommend_title, levelLabel(recommendedLevel)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = stringResource(id = R.string.benchmark_recommend_none),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            RecommendBasis(report = report, recommended = recommendedLevel)
            Text(
                text = stringResource(id = R.string.benchmark_current_level, levelLabel(state.currentLevel)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RecommendationHint()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 推荐 -1 也要给按钮：结论是「关掉压缩」，同样需要一键落到设置里。
                // 只要当前档位不是推荐档位就允许点（包括「已应用过、又被手动拖走」），
                // 点下去永远写推荐档位，滑块随之跳回推荐值。
                Button(
                    onClick = onApply,
                    modifier = Modifier.weight(1f),
                    enabled = !applied,
                ) {
                    Text(
                        stringResource(
                            id = when {
                                applied -> R.string.benchmark_applied
                                recommendedLevel < 0 -> R.string.benchmark_apply_off
                                else -> R.string.benchmark_apply
                            },
                            targetLabel,
                        )
                    )
                }
                OutlinedButton(onClick = onRetest, modifier = Modifier.weight(1f)) {
                    Text(stringResource(id = R.string.benchmark_retest))
                }
            }
        }
    }
}

/**
 * 生效条件说明 + 危险提示。
 *
 * 「重新初始化后才会改变」决定了这次推荐的生效时机，是用户最容易忽略的一句；
 * 而重新初始化会清空仓库、已有备份永久丢失。把它加粗，并把后果单独用 error 色
 * 配警示图标列出来，避免用户以为「应用」只是切了个开关。
 */
@Composable
private fun RecommendationHint() {
    val hint = stringResource(id = R.string.benchmark_recommend_hint)
    val emphasis = stringResource(id = R.string.benchmark_recommend_hint_emphasis)
    val emphasisColor = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = buildAnnotatedString {
                append(hint)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = emphasisColor)) {
                    append(emphasis)
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(id = R.string.benchmark_recommend_danger),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 与 SampleRow 的 horizontal padding 保持一致，否则高亮行的圆角背景会让列看起来错位
            .padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell(R.string.benchmark_table_level, 0.95f)
        HeaderCell(R.string.benchmark_table_speed, 0.9f)
        HeaderCell(R.string.benchmark_table_volume, 0.9f)
        HeaderCell(R.string.benchmark_table_ratio, 0.8f)
        HeaderCell(R.string.benchmark_table_time, 0.85f)
        HeaderCell(R.string.benchmark_table_size, 0.85f)
    }
}

@Composable
private fun RowScope.HeaderCell(resId: Int, weight: Float) {
    Text(
        text = stringResource(id = resId),
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SampleRow(
    sample: BenchmarkSample,
    offSample: BenchmarkSample?,
    highlighted: Boolean = false,
) {
    val hasOff = offSample != null && offSample.elapsedMs > 0 && offSample.dataBytes > 0
    val timeVsOff = if (sample.reliable && hasOff) {
        "%.2f×".format(sample.elapsedMs.toDouble() / offSample!!.elapsedMs)
    } else {
        "-"
    }
    val sizeVsOff = if (sample.reliable && hasOff) {
        // 3 位小数：低档位之间的体积差常在 0.1%~1%（实测 L1/L3/L6 只差几百 KB），
        // 2 位会被四舍五入抹平成完全相同的 「0.40×」，看不出档位之间到底有没有区别。
        "%.3f×".format(sample.dataBytes.toDouble() / offSample!!.dataBytes)
    } else {
        "-"
    }
    // 推荐档位用 M3 container 配对色（随动态取色变蓝/青系），不可靠样本仍以 error 色优先。
    val contentColor = if (highlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowCell(
            text = levelLabel(sample.level),
            weight = 0.95f,
            color = if (sample.reliable) contentColor else MaterialTheme.colorScheme.error,
            fontWeight = if (highlighted) FontWeight.Bold else null,
        )
        RowCell(if (sample.reliable) "%.2f".format(sample.throughputMBps) else "-", 0.9f, contentColor)
        RowCell(if (sample.reliable) formatVolume(sample.dataBytes) else "-", 0.9f, contentColor)
        RowCell(if (sample.reliable) "%.3f".format(sample.ratio) else "-", 0.8f, contentColor)
        RowCell(timeVsOff, 0.85f, contentColor)
        RowCell(sizeVsOff, 0.85f, contentColor)
    }
    if (!sample.reliable && sample.note.isNotEmpty()) {
        Text(
            text = stringResource(id = R.string.benchmark_unreliable, sample.note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun RowScope.RowCell(
    text: String,
    weight: Float,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontWeight: FontWeight? = null,
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
    )
}

/**
 * 压缩后体积：表格窄，用 M 作单位省宽度。
 *
 * 保留 2 位小数：48 MiB 不可压随机数据占了主体，档位之间的差别只体现在残余的
 * 可压部分上，常常只有几百 KB（52.61M vs 52.48M）。1 位小数会把它们显示成同一个
 * 52.6M，让人误以为「高档位完全没效果」。
 */
private fun formatVolume(bytes: Long): String = "%.2fM".format(bytes / 1024.0 / 1024.0)

/**
 * 推荐依据：只给结论不给理由时，用户看到 L1/L3/L6 三档的数字几乎一样，自然会问
 * 「凭什么选 L1」。这里把两个决策输入直接摊开——相对 OFF 的体积变化与耗时倍数，
 * 并把实测等价（体积差 ≤1% 且耗时差 ≤5%）的档位一并列出。
 */
@Composable
private fun RecommendBasis(report: BenchmarkReport, recommended: Int) {
    if (recommended < 0) return
    val off = report.samples.firstOrNull { it.level == 0 && it.reliable } ?: return
    val rec = report.samples.firstOrNull { it.level == recommended && it.reliable } ?: return
    if (off.dataBytes <= 0 || off.elapsedMs <= 0 || rec.dataBytes <= 0 || rec.elapsedMs <= 0) return

    val volumeDelta = (rec.dataBytes.toDouble() / off.dataBytes - 1.0) * 100.0
    val timeFactor = rec.elapsedMs.toDouble() / off.elapsedMs
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(
                id = R.string.benchmark_recommend_basis,
                "%+.1f%%".format(volumeDelta),
                "%.2f".format(timeFactor),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val equivalents = report.samples.filter { sample ->
            sample.reliable && sample.level != recommended &&
                kotlin.math.abs(sample.dataBytes - rec.dataBytes).toDouble() / rec.dataBytes <= 0.01 &&
                kotlin.math.abs(sample.elapsedMs - rec.elapsedMs).toDouble() / rec.elapsedMs <= 0.05
        }
        if (equivalents.isNotEmpty()) {
            val offLabel = stringResource(id = R.string.benchmark_level_off)
            val levelPattern = stringResource(id = R.string.benchmark_level_value)
            Text(
                text = stringResource(
                    id = R.string.benchmark_recommend_equivalent,
                    equivalents.joinToString(" / ") {
                        if (it.level == 0) offLabel else levelPattern.format(it.level)
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReportWarnings(report: BenchmarkReport) {
    val context = LocalContext.current
    val warnings = remember(report) { reportWarnings(context, report) }
    if (warnings.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            warnings.forEach { resId ->
                Text(
                    text = stringResource(id = resId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private const val NINETY_DAYS_MS = 90L * 24 * 60 * 60 * 1000

/**
 * 报告失效判定（方案 §9）：
 * - 机型/芯片变化 → 结果不可用
 * - 应用主版本变化 → 建议重测（不强制）
 * - 超过 90 天 → 结果较旧
 */
@Suppress("DEPRECATION")
private fun reportWarnings(context: Context, report: BenchmarkReport): List<Int> {
    val warnings = mutableListOf<Int>()
    val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
    val deviceChanged = (report.deviceModel.isNotBlank() && report.deviceModel != model) ||
        (report.socModel.isNotBlank() && report.socModel != soc)
    if (deviceChanged) warnings += R.string.benchmark_warn_device

    val currentVersion = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
    if (report.appVersionName.isNotBlank() && currentVersion.isNotBlank() &&
        majorVersion(report.appVersionName) != majorVersion(currentVersion)
    ) {
        warnings += R.string.benchmark_warn_version
    }
    if (report.measuredAt > 0 && System.currentTimeMillis() - report.measuredAt > NINETY_DAYS_MS) {
        warnings += R.string.benchmark_warn_age
    }
    return warnings
}

private fun majorVersion(version: String): String = version.substringBefore('.').trim()

@Composable
private fun levelLabel(level: Int): String = when {
    level < 0 -> "-"
    level == 0 -> stringResource(id = R.string.benchmark_level_off)
    else -> stringResource(id = R.string.benchmark_level_value, level)
}

@Composable
private fun phaseLabel(detail: String): String = when {
    detail.startsWith("generat") -> stringResource(id = R.string.benchmark_phase_corpus)
    detail.startsWith("warm") -> stringResource(id = R.string.benchmark_phase_warm)
    detail.startsWith("init") -> stringResource(id = R.string.benchmark_phase_init)
    detail.startsWith("writing") -> stringResource(id = R.string.benchmark_phase_write)
    else -> stringResource(id = R.string.benchmark_phase_corpus)
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
