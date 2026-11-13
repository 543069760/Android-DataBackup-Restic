package com.xayah.feature.main.settings.diagnostic

import android.content.Context
import android.content.Intent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.rootservice.util.RootDeathLog
import com.xayah.core.rootservice.util.RootDeathVerdict
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.LabelLargeText
import com.xayah.core.ui.component.TitleSmallText
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.statusColors
import com.xayah.core.ui.theme.value
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun PageDiagnosticLogs() {
    val viewModel: DiagnosticLogsViewModel = hiltViewModel()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    SettingsScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.diagnostic_logs),
        actions = {
            // 刷新只重新读目录，不会落盘任何东西；抓取才产生新报告，两者必须分开。
            IconButton(onClick = { viewModel.refresh() }, enabled = !refreshing) {
                val refreshDesc = stringResource(id = R.string.diagnostic_logs_refresh)
                if (refreshing) {
                    // 读目录可能只要几毫秒，图标不变的话用户无法判断是否点中，所以这里换成转圈。
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = refreshDesc },
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = refreshDesc
                    )
                }
            }
            IconButton(onClick = { viewModel.captureNow() }, enabled = !busy) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = stringResource(id = R.string.diagnostic_capture_now)
                )
            }
            IconButton(onClick = { viewModel.clearAll() }, enabled = logs.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(id = R.string.diagnostic_logs_clear_all)
                )
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            BodyMediumText(
                modifier = Modifier.padding(top = 8.dp),
                text = stringResource(id = R.string.diagnostic_logs_hint),
                color = ThemedColorSchemeKeyTokens.Outline.value
            )
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BodyMediumText(
                        text = stringResource(id = R.string.diagnostic_logs_empty),
                        color = ThemedColorSchemeKeyTokens.Outline.value,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = innerPadding.calculateBottomPadding() + 24.dp
                    )
                ) {
                    items(items = logs, key = { it.file.absolutePath }) { log ->
                        DiagnosticLogItem(
                            log = log,
                            onShare = { shareLog(context, log) },
                            onDelete = { viewModel.delete(log) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLogItem(log: RootDeathLog, onShare: () -> Unit, onDelete: () -> Unit) {
    val timeText = remember(log.timeMillis) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(log.timeMillis))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 第一行：时间
                TitleSmallText(text = timeText, maxLines = 1)
                // 第二行：死因标签（正常绿 / 崩溃红）+ 手动抓取的自检标记
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VerdictChip(verdict = log.verdict)
                    if (log.trigger == MANUAL_TRIGGER) ManualChip()
                }
                // 第三行：报告体积
                BodyMediumText(
                    text = formatBytes(log.sizeBytes),
                    color = ThemedColorSchemeKeyTokens.Outline.value,
                    maxLines = 1
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = stringResource(id = R.string.diagnostic_log_share)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(id = R.string.diagnostic_log_delete)
                )
            }
        }
    }
}

/**
 * 死因标签：崩溃类走 M3 的 error 角色（红色系），其余走语义绿。
 *
 * 两种颜色都不是写死的 hex：红色来自 [MaterialTheme.colorScheme] 的 errorContainer，
 * 绿色来自 [statusColors]，后者已按明暗模式取 tone 并向动态 primary 做过 harmonize。
 */
@Composable
private fun VerdictChip(verdict: RootDeathVerdict) {
    val crash = verdict.isCrash
    val ok = statusColors()
    val container = if (crash) MaterialTheme.colorScheme.errorContainer else ok.okContainer
    val content = if (crash) MaterialTheme.colorScheme.onErrorContainer else ok.onOkContainer
    Surface(shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
        LabelLargeText(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            text = stringResource(id = verdict.titleRes),
            fontWeight = FontWeight.Medium
        )
    }
}

/** 手动抓取得到的自检报告，用中性色与真实死因区分，避免被当成一次崩溃。 */
@Composable
private fun ManualChip() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        LabelLargeText(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            text = stringResource(id = R.string.diagnostic_log_manual),
            fontWeight = FontWeight.Medium
        )
    }
}

private const val MANUAL_TRIGGER = "manual"

private val RootDeathVerdict.isCrash: Boolean
    get() = this == RootDeathVerdict.RUST_PANIC ||
            this == RootDeathVerdict.ART_OOM ||
            this == RootDeathVerdict.SIGSEGV ||
            this == RootDeathVerdict.NATIVE_CRASH ||
            this == RootDeathVerdict.DAEMON_JAVA_CRASH ||
            this == RootDeathVerdict.APP_CRASH

private val RootDeathVerdict.titleRes: Int
    get() = when (this) {
        RootDeathVerdict.RUST_PANIC -> R.string.diagnostic_log_verdict_rust_panic
        RootDeathVerdict.ART_OOM -> R.string.diagnostic_log_verdict_art_oom
        RootDeathVerdict.SIGSEGV -> R.string.diagnostic_log_verdict_sigsegv
        RootDeathVerdict.NATIVE_CRASH -> R.string.diagnostic_log_verdict_native_crash
        RootDeathVerdict.DAEMON_JAVA_CRASH -> R.string.diagnostic_log_verdict_daemon_java_crash
        RootDeathVerdict.LMK -> R.string.diagnostic_log_verdict_lmk
        RootDeathVerdict.SELF_KILL -> R.string.diagnostic_log_verdict_self_kill
        RootDeathVerdict.APP_CRASH -> R.string.diagnostic_log_verdict_app_crash
        RootDeathVerdict.UNKNOWN -> R.string.diagnostic_log_verdict_unknown
    }

private fun shareLog(context: Context, log: RootDeathLog) {
    runCatching {
        val authority = "${context.packageName}.provider"
        val uri = FileProvider.getUriForFile(context, authority, log.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            // 报告是 gzip 的，用 text/plain 分享出去接收方会按文本打开看到乱码。
            type = if (log.file.name.endsWith(".gz", ignoreCase = true)) "application/gzip" else "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, log.file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
