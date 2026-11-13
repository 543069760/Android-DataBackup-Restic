package com.xayah.core.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider.getUriForFile
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.datastore.readCustomSUFile
import com.xayah.core.util.SymbolUtil.LF
import com.xayah.core.util.SymbolUtil.USD
import com.xayah.core.util.command.BaseUtil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter

object LogUtil {
    /** 日志目录，null 表示 [initialize] 尚未成功执行过。 */
    private var cacheDir: String? = null

    /** 追加写入的日志流，null 表示日志不可用（此时 [appendLine] 静默丢弃单行）。 */
    private var logStream: FileOutputStream? = null

    /** 保护 [logStream]：重新初始化与并发追加必须互斥。 */
    private val lock = Any()

    private val timestamp: Long = DateUtil.getTimestamp()
    private const val TAG = "LogUtil"
    private const val SEPARATOR = "    "
    private const val LOG_FILE_PREFIX = "log_"
    private const val TAG_COMMON = "Common    "
    const val TAG_SHELL_IN = "SHELL_IN  "
    const val TAG_SHELL_OUT = "SHELL_OUT "
    const val TAG_SHELL_CODE = "SHELL_CODE"

    private fun getLogFileName() = "$LOG_FILE_PREFIX$timestamp.txt"

    /**
     * 打开日志文件。
     *
     * 与旧实现（`FileChannel.map` 逐行追加）的三点差异：
     * 1. **不再 mmap**：向文件写映射页时，内核若分配不到数据块（ENOSPC / data quota / 介质错误）
     *    会直接向进程投递 SIGBUS，进程当场死亡；而写日志恰好发生在「记录守护进程死亡」这条
     *    错误处理路径上，等于记录故障的代码把进程带走。
     * 2. **不再有竞态**：旧实现 `size()` 与 `map()` 是两步，多线程并发会拿到同一偏移互相覆盖。
     * 3. **不再泄漏 fd**：重复初始化时先关旧流。
     *
     * 清理历史空日志只处理文件、并跳过本次正在写的那个：[FileUtil.listFilePaths] 默认也会
     * 返回子目录，对目录调 `readLines()` 会抛 `Is a directory`；旧实现整段 initialize 被一个
     * runCatching 包着，这一抛就会让日志流停在未创建状态，此后所有日志被静默丢弃。
     */
    fun initialize(context: Context, cacheDir: String) = runCatching {
        val dir = File(cacheDir)
        if (!dir.exists()) dir.mkdirs()

        val currentName = getLogFileName()
        FileUtil.listFilePaths(cacheDir, listDirs = false).forEach { path ->
            val file = File(path)
            if (file.name == currentName) return@forEach
            // 单个文件读失败不能连坐整个初始化（旧实现正是这么丢掉日志的）。
            runCatching {
                if (file.readLines().size <= 4) file.delete()
            }
        }

        synchronized(lock) {
            runCatching { logStream?.close() }
            logStream = FileOutputStream(File(dir, currentName), true)
            this.cacheDir = cacheDir
        }

        log("Version:    ${BuildConfigUtil.VERSION_NAME}")
        log("Model:      ${Build.MODEL}")
        log("ABIs:       ${Build.SUPPORTED_ABIS.firstOrNull() ?: ""}")
        log("SDK:        ${Build.VERSION.SDK_INT}")
        log("Global Namespace:     ${runBlocking { BaseUtil.readLink("1") }}")
        log("Namespace:            ${runBlocking { BaseUtil.readLink("self") }}")
        log("SU:                   ${runBlocking { BaseUtil.readSuVersion(context.readCustomSUFile().first()) }}")
        log("${USD}PATH:                ${runBlocking { BaseUtil.readVariable("PATH").trim() }}")
        log("${USD}HOME:                ${runBlocking { BaseUtil.readVariable("HOME").trim() }}")
    }.onFailure {
        // 旧实现这里是彻底的静默：初始化中途抛错后日志全丢且没有任何痕迹。
        Log.e(TAG, "initialize failed, file logging disabled", it)
    }

    /**
     * 追加一行。
     *
     * `FileOutputStream(append = true)` 以 O_APPEND 打开：每次 write 由内核追加到文件末尾，
     * 不依赖 fd 偏移，因此不会像旧实现那样「先取 size 再写回同一位置」；synchronized 保证
     * 多线程追加的行不交错。任何失败（流未就绪、ENOSPC 等）只丢这一行，绝不影响调用方。
     */
    private fun appendLine(msg: String) = runCatching {
        val bytes = (msg + LF).toByteArray()
        synchronized(lock) {
            logStream?.write(bytes)
        }
    }

    private fun appendWithTimestamp(tag: String, msg: String) = run {
        Log.d(tag, msg)
        appendLine("${DateUtil.formatTimestamp(DateUtil.getTimestamp())}$SEPARATOR$tag$SEPARATOR$msg")
    }

    fun log(content: () -> Pair<String, String>) {
        appendWithTimestamp(tag = content().first, msg = content().second)
    }

    fun log(msg: String) {
        appendWithTimestamp(tag = TAG_COMMON, msg = msg)
    }

    fun shareLog(context: Context, name: String) {
        val dir = cacheDir ?: run {
            Log.w(TAG, "shareLog ignored: log directory is not initialized")
            return
        }
        val sharingLog = File(dir, name)
        // 使用 context.packageName 获取运行时的应用包名
        val sharingUri = getUriForFile(
            context,
            "${context.packageName}.filesharingprovider",
            sharingLog
        )
        val sharingIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, sharingUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(sharingIntent)
    }
}

fun <T> Result<T>.withLog(): Result<T> {
    exceptionOrNull()?.let {
        val stringWriter = StringWriter()
        it.printStackTrace(PrintWriter(stringWriter))
        LogUtil.log { "Exception" to stringWriter.toString() }
    }
    return this
}