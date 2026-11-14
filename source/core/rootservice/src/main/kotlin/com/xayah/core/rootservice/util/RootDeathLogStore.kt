package com.xayah.core.rootservice.util

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 守护进程死因结论。
 *
 * 判定只看系统侧证据（tombstone / dropbox / logcat crash / dmesg），
 * 不依赖崩溃进程自己上报，因此对 SIGABRT / SIGSEGV / SIGKILL 都适用。
 */
enum class RootDeathVerdict {
    /** Rust panic 或分配失败触发的 abort。 */
    RUST_PANIC,

    /** ART 堆 OOM 触发的 abort（Java/Kotlin 侧内存耗尽）。 */
    ART_OOM,

    /** native 段错误。 */
    SIGSEGV,

    /**
     * SIGSEGV/SIGABRT 以外的 native 信号（SIGBUS/SIGILL/SIGFPE/SIGTRAP 等）。
     *
     * 这类死亡同样不带 abort message，也不走 ART 的异常处理：例如 mmap 写脏页时内核分配不到
     * 数据块会向进程投递 SIGBUS。只认 SIGSEGV/SIGABRT 的话会被误判成 [SELF_KILL]。
     */
    NATIVE_CRASH,

    /**
     * 守护进程里某个 Java 线程抛出未捕获异常，被 ART 的默认处理器直接杀掉整个进程。
     *
     * 这类死亡在系统里只有一条 `FATAL EXCEPTION: <thread>`，既没有 native signal
     * 也没有 abort message，因此必须单独识别——否则会被误判成 [SELF_KILL]。
     */
    DAEMON_JAVA_CRASH,

    /** 被系统 lmkd/lowmemorykiller 强杀，进程来不及留任何崩溃记录。 */
    LMK,

    /** 没有任何崩溃记录：被 app 自己或系统正常回收（取消兜底、NullReply 重载等）。 */
    SELF_KILL,

    /**
     * app 主进程自己崩溃了（tombstone 的 `Cmdline:` 就是本包名），而守护进程没有留下任何证据。
     *
     * 与 [SELF_KILL] 的区别：这不是「被正常回收」，而是一次用户可见的 app 崩溃——只是崩的
     * 不是守护进程。本次那份 SIGBUS 报告就属这一类（旧的判定把它报成了 [SELF_KILL]）。
     */
    APP_CRASH,

    /** 采集失败或证据不足以定性。 */
    UNKNOWN,
    ;

    companion object {
        fun of(raw: String?): RootDeathVerdict = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** 一份已经落盘的守护进程死亡报告。 */
data class RootDeathLog(
    val file: File,
    /** 死亡时刻（wall clock），取自报告头，缺失时退回文件写入时间。 */
    val timeMillis: Long,
    val sizeBytes: Long,
    val verdict: RootDeathVerdict,
    val trigger: String,
)

/**
 * 死亡报告仓库。
 *
 * 刻意放在 `filesDir` 而不是 `cacheDir`：cacheDir 正是系统在存储/内存吃紧时优先清理的目录，
 * 而死亡报告恰恰最可能在这样的时机产生，放 cache 等于让证据自己先被清掉。
 *
 * 目录结构：`filesDir/root_death/root-death-<yyyyMMdd-HHmmss>.log.gz`
 *
 * 报告正文是 gzip 的：一次取证的现场文本通常在 20~60 KB，压缩后只剩几 KB，
 * 这样既能保留完整证据，又不会把 filesDir 撑起来。
 */
object RootDeathLogStore {
    const val DIR_NAME = "root_death"
    const val DEFAULT_MAX_KEEP = 10

    /** 报告压缩后缀，[com.xayah.core.rootservice.util.RootDeathCollector] 落盘时也用这个。 */
    const val GZIP_SUFFIX = ".gz"

    private const val FILE_PREFIX = "root-death-"
    private const val HEADER_LINES = 16

    // 守护进程侧（libsu RootService 的 ContextWrapper）`applicationContext` 可能是 null，
    // 而心跳文件与报告必须落在同一个目录里，所以这里对 null 兜底。
    fun dir(context: Context): File = File((context.applicationContext ?: context).filesDir, DIR_NAME)

    /**
     * 取得并「开放」取证目录。
     *
     * 这个目录现在会被**两个 uid** 写：app 进程写报告与击杀日志，root 守护进程写心跳。
     * 谁先创建谁决定属主——如果守护进程先建（root:root 0755），app 之后连报告文件都建不出来。
     * 所以统一走这里：mkdirs 之后放宽到 0777，文件侧由写入方再调 [loosen]。
     */
    fun prepareDir(context: Context): File {
        val target = dir(context)
        if (!target.exists()) target.mkdirs()
        runCatching {
            target.setReadable(true, false)
            target.setWritable(true, false)
            target.setExecutable(true, false)
        }
        return target
    }

    /** 让 root 与 app 都能读写同一个文件（心跳、击杀日志都是双写）。 */
    fun loosen(file: File) {
        runCatching {
            file.setReadable(true, false)
            file.setWritable(true, false)
        }
    }

    fun list(context: Context): List<RootDeathLog> {
        val files = dir(context).listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) } ?: return emptyList()
        return files.map { of(it) }.sortedByDescending { it.timeMillis }
    }

    fun of(file: File): RootDeathLog {
        val header = header(file)
        return RootDeathLog(
            file = file,
            timeMillis = header["epoch_ms"]?.toLongOrNull() ?: file.lastModified(),
            sizeBytes = file.length(),
            verdict = RootDeathVerdict.of(header["verdict"]),
            trigger = header["trigger"].orEmpty(),
        )
    }

    fun delete(context: Context, log: RootDeathLog): Boolean = log.file.delete()

    fun clear(context: Context): Int {
        val files = dir(context).listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) } ?: return 0
        return files.count { it.delete() }
    }

    fun totalSize(context: Context): Long =
        (dir(context).listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) } ?: emptyArray()).sumOf { it.length() }

    /** 只保留最近 [keep] 份，防止长期使用把 filesDir 撑大。 */
    fun prune(context: Context, keep: Int = DEFAULT_MAX_KEEP) {
        if (keep <= 0) return
        list(context).drop(keep).forEach { runCatching { it.file.delete() } }
    }

    /**
     * 报告落盘是 gzip 的（现场文本动辄几十 KB，压缩后只占几 KB），
     * 但历史版本留下的是明文 `.log`，两种都要能读。
     */
    private fun header(file: File): Map<String, String> = runCatching {
        openStream(file).bufferedReader().use { reader ->
            val map = LinkedHashMap<String, String>()
            var remaining = HEADER_LINES
            while (remaining-- > 0) {
                val line = reader.readLine() ?: break
                if (line.startsWith("#")) continue
                val index = line.indexOf('=')
                if (index > 0) map[line.substring(0, index).trim()] = line.substring(index + 1).trim()
            }
            map
        }
    }.getOrElse { emptyMap<String, String>() }

    private fun openStream(file: File): InputStream =
        if (file.name.endsWith(GZIP_SUFFIX, ignoreCase = true)) GZIPInputStream(file.inputStream())
        else file.inputStream()
}
