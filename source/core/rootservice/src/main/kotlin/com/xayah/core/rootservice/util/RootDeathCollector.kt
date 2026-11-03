package com.xayah.core.rootservice.util

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream

/**
 * 守护进程死亡取证器。
 *
 * 定位思路：不指望崩溃进程自己上报，而是在 app 进程发现 binder 死亡后，
 * 以 root 身份把系统侧的「黑匣子」捞回来——logcat crash 缓冲区、DropBox 里的
 * SYSTEM_TOMBSTONE、`/data/tombstones` 里时间窗内的 tombstone、dmesg 里的 LMK 记录。
 *
 * 这套证据能区分 panic/abort、ART OOM、SIGSEGV、LMK 与「app 自己杀的」，
 * 而这四类在 Java 侧的表现完全一样（都是 DeadObjectException），
 * 只有系统记录能定性。
 *
 * 所有内容只写本机 `filesDir/root_death/`，不联网、不上传。
 */
object RootDeathCollector {
    private const val TAG = "RootDeath"
    private const val FILE_PREFIX = "root-death-"

    /**
     * debuggerd 生成 tombstone 并投递 DropBox 需要几十到几百毫秒，
     * 立刻抓取可能拿到空记录，因此先等一小会儿再取证。
     */
    private const val SETTLE_DELAY_MS = 450L

    /** tombstone 的 mtime 必须落在这个窗口内，避免把上一次崩溃的历史文件当成这次。 */
    private const val TOMBSTONE_WINDOW_MS = 15_000L

    private const val MAX_TOMBSTONE_BYTES = 200_000L
    private const val MAX_SECTION_CHARS = 120_000

    /**
     * 原始崩溃缓冲只保留「块头索引」：每个崩溃块压成一行，最多这么多行。
     *
     * crash buffer 全系统共用，`-t 400` 在这台机器上能回溯到两天前，其中绝大部分是与本次
     * 取证无关的历史崩溃（别的应用、甚至我们自己的旧会话）。早期这里存完整原文，
     * 单这一项就占掉报告 89% 的体积；改成一行一块后，仍能核对「缓冲区里有哪些崩溃」。
     */
    private const val MAX_CRASH_HEAD_LINES = 60

    /** 单条块头摘要的字符上限：够容纳一行 FATAL EXCEPTION 加进程名，再长也没信息量。 */
    private const val MAX_HEAD_CHARS = 200

    /** 同一次死亡可能被多个 RemoteRootService 实例各上报一次，这里再做一道时间窗去重。 */
    private const val MIN_INTERVAL_MS = 8_000L

    private const val SESSION_FILE = ".session_start"
    private const val DAEMON_ALIVE_FILE = ".daemon_alive"

    /** 超过这么久的历史会话不再补抓，避免用户隔几周打开 app 突然冒出一条旧报告。 */
    private const val MAX_RECOVERY_AGE_MS = 7L * 24 * 60 * 60 * 1000L

    /** tombstone 的 mtime 与「进程被杀」之间有毫秒级抖动，窗口起点往前让一点。 */
    private const val RECOVERY_MARGIN_MS = 5_000L

    private const val TRIGGER_STARTUP_RECOVERY = "startup-recovery"

    /** LMK 证据的取数命令：lmkd/AMS 的杀进程事件都落在 events buffer。 */
    private const val LMK_EVENTS_CMD =
        "logcat -b events -d -v threadtime -t 4000 2>/dev/null | grep -aiE 'lmk_kill|lmkd|am_kill'"

    private val running = AtomicBoolean(false)
    private val sessionStarted = AtomicBoolean(false)
    private val daemonAliveMarked = AtomicBoolean(false)

    @Volatile
    private var lastCaptureWallMs = 0L

    /**
     * 守护进程死亡后异步取证。只允许一个实例在跑，避免同一次死亡重复抓取。
     */
    fun captureAsync(context: Context, trigger: String) {
        if (!running.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val deathWallMs = System.currentTimeMillis()
        if (deathWallMs - lastCaptureWallMs < MIN_INTERVAL_MS) {
            running.set(false)
            Log.i(TAG, "skip duplicate capture within ${MIN_INTERVAL_MS}ms")
            return
        }
        lastCaptureWallMs = deathWallMs
        Thread({
            try {
                Thread.sleep(SETTLE_DELAY_MS)
                write(appContext, trigger, deathWallMs, SystemClock.elapsedRealtime())
            } catch (t: Throwable) {
                Log.e(TAG, "capture failed", t)
            } finally {
                running.set(false)
            }
        }, "root-death-collector").start()
    }

    /**
     * 同步取证，供设置页「立即抓取」做自检用。
     *
     * @return 生成的文件；已有一次取证在跑时返回 null。
     */
    fun captureNow(context: Context, trigger: String): File? {
        if (!running.compareAndSet(false, true)) return null
        return try {
            val appContext = context.applicationContext
            write(appContext, trigger, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        } catch (t: Throwable) {
            Log.e(TAG, "capture failed", t)
            null
        } finally {
            running.set(false)
        }
    }

    /**
     * app 进程启动时调用（每个进程只生效一次，放在 `Application.onCreate`）。
     *
     * 补的是「整个 app 进程被 LMK / 用户划掉」这个洞：这种时候守护进程和 app 一起没了，
     * 进程内没有任何代码能写报告，只能等下次启动时按会话时间窗去系统侧找证据。
     *
     * 判据是上次会话里守护进程是否活过（[markDaemonAlive] 落的标记），
     * 所以非 root 用户、或上次没做过 root 操作的启动，不会触发任何 root 调用。
     */
    fun onAppStart(context: Context) {
        if (!sessionStarted.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread({
            try {
                val nowWall = System.currentTimeMillis()
                val sessionFile = File(dir(appContext), SESSION_FILE)
                val aliveFile = File(dir(appContext), DAEMON_ALIVE_FILE)
                val prevStart = readTimestamp(sessionFile)
                val daemonAliveAt = readTimestamp(aliveFile)
                writeTimestamp(sessionFile, nowWall)
                // 消费掉上一轮的标记：本次会话是否「守护进程活过」要重新累积。
                aliveFile.delete()
                recoverIfNeeded(appContext, prevStart, daemonAliveAt, nowWall)
            } catch (t: Throwable) {
                Log.e(TAG, "startup recovery hook failed", t)
            }
        }, "root-death-startup").start()
    }

    /** 守护进程连接成功时调用，标记「本次会话内 root 侧确实可用过」。 */
    fun markDaemonAlive(context: Context) {
        if (!daemonAliveMarked.compareAndSet(false, true)) return
        runCatching {
            writeTimestamp(File(dir(context.applicationContext), DAEMON_ALIVE_FILE), System.currentTimeMillis())
        }.onFailure { Log.w(TAG, "markDaemonAlive failed: ${it.message}") }
    }

    private fun recoverIfNeeded(context: Context, prevStart: Long, daemonAliveAt: Long, nowWall: Long) {
        if (prevStart <= 0L || nowWall <= prevStart) return
        if (nowWall - prevStart > MAX_RECOVERY_AGE_MS) return
        // 上次会话里守护进程没起来过，也就没有守护进程可死。
        if (daemonAliveAt < prevStart) return
        if (!running.compareAndSet(false, true)) return
        try {
            val windowStart = prevStart - RECOVERY_MARGIN_MS
            val tombstoneHit = probeTombstone(windowStart, nowWall)
            val probe = if (tombstoneHit) {
                KillProbe(emptyList(), null)
            } else {
                probeLmk(windowStart, nowWall, context.packageName)
            }
            if (!tombstoneHit && probe.daemonLmk.isEmpty()) {
                // 窗口内只有我们 app 自己被停（用户从最近任务划掉 / 系统 force-stop / 覆盖安装）时，
                // 属于正常退出：此时守护进程通常还活着，没有任何崩溃证据，不该产出一份「崩溃报告」。
                val why = probe.appStopReason
                if (why != null && NORMAL_STOP.containsMatchIn(why)) {
                    Log.i(TAG, "startup recovery: app was stopped normally ($why), no daemon crash")
                } else {
                    Log.i(TAG, "startup recovery: previous session left no crash evidence")
                }
                return
            }
            Log.i(TAG, "startup recovery: evidence found (tombstone=$tombstoneHit, lmk=${probe.daemonLmk.size}), capturing")
            write(
                context = context,
                trigger = "$TRIGGER_STARTUP_RECOVERY: app died with the daemon alive",
                deathWallMs = nowWall,
                deathElapsedMs = SystemClock.elapsedRealtime(),
                windowStartMs = windowStart,
                windowEndMs = nowWall,
            )
        } finally {
            running.set(false)
        }
    }

    /** 探针：先按 mtime 缩小到窗口内，再确认确实是我们的守护进程（app_process）崩的。 */
    private fun probeTombstone(windowStartMs: Long, windowEndMs: Long): Boolean {
        val listing = sh(
            "ls -t /data/tombstones 2>/dev/null | head -n 6 | while read f; do echo \"\$f \$(stat -c %Y /data/tombstones/\$f 2>/dev/null)\"; done"
        )
        // tombstone 目录是全系统共用的：同窗口内别的应用崩溃也会留下文件，必须按进程名筛掉。
        return listing.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(' ')
            val name = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val mtimeMs = parts.getOrNull(1)?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            name.takeIf { mtimeMs in windowStartMs..windowEndMs }
        }.any { name ->
            // grep -q 成功时没有输出，`sh()` 会回落到空串，所以用 echo 1 把命中显式表达出来。
            sh(
                "head -c $MAX_TOMBSTONE_BYTES /data/tombstones/$name 2>/dev/null | grep -aqiE 'app_process|$PROCESS_HINT' && echo 1"
            ).trim() == "1"
        }
    }

    /** [probeLmk] 的解析结果。 */
    private data class KillProbe(
        /** 能证明「守护进程」被内存回收打死的原始事件行；非空才判 LMK。 */
        val daemonLmk: List<String>,
        /** 窗口内我们 app 被杀的原因（`o-stop(40)`、`Cached(...)` 等）；非空说明死的是 app 不是守护进程。 */
        val appStopReason: String?,
    )

    /**
     * 探针：LMK 证据改从 events buffer 取，不再依赖 dmesg。
     *
     * dmesg 在新版 Android 上即使以 root 执行也常被 SELinux 挡成空串（Android 16 真机实测恒为空），
     * 而 lmkd 与 AMS 本来就会把杀进程事件写进 events buffer：
     * - `lmk_kill`：lmkd 直接杀，覆盖守护进程这类不由 AMS 托管的进程；
     * - `am_kill`：AMS 回收应用进程，带 oom_adj 与原因。
     * 两种事件的行首时间戳都是墙钟，不需要再用 /proc/uptime 换算开机秒。
     */
    private fun probeLmk(windowStartMs: Long, windowEndMs: Long, ownPackage: String): KillProbe =
        parseKillEvents(sh("$LMK_EVENTS_CMD | tail -n 200"), windowStartMs, windowEndMs, ownPackage)

    /**
     * 解析杀进程事件，只挑出真正能证明「守护进程被内存回收打死」的行。
     *
     * 这里有两道过滤，缺任何一道都会把正常退出误判成 LMK：
     * 1. **按进程名**：`am_kill` 记的是被杀进程的名字。app 叫 `com.xayah.databackup.*`，
     *    守护进程是 libsu 起的 `app_process`，两者不是一回事。用户从最近任务划掉 app 时，
     *    AMS 就会写一条带我们包名的 `am_kill`——老实现用 "databackup" 做关键词匹配，
     *    这条会被当成守护进程的 LMK 证据，于是「手滑退出」变成「内存不足」。
     * 2. **按 reason**：`am_kill` 第 5 段是原因，`o-stop(40)`（ColorOS 上滑清理）、
     *    `stop ... due to from pid N (system)`（force-stop）、`installPackageLI`（覆盖安装）
     *    都与内存压力无关；真正的内存回收是 `Cached(...)` / `Empty` 之类原因，
     *    而 lmkd 直接杀只有 `lmk_kill`（不带 reason，只能靠进程名认）。
     */
    private fun parseKillEvents(
        text: String,
        windowStartMs: Long,
        windowEndMs: Long,
        ownPackage: String,
    ): KillProbe {
        val daemonLmk = mutableListOf<String>()
        var appStopReason: String? = null
        text.lineSequence().forEach { line ->
            // dmesg 的行没有 logcat 时间戳，只能整体当作「当前时刻」，其余行必须落在窗口内。
            if (LOGCAT_TS.containsMatchIn(line) && lineTimeMs(line, windowEndMs) !in windowStartMs..windowEndMs) {
                return@forEach
            }
            val amKill = AM_KILL.find(line)
            if (amKill != null) {
                val processName = amKill.groupValues[2].trim()
                val reason = amKill.groupValues[4].trim()
                when {
                    // 死的是 app 自己：与守护进程无关，最多把原因记下来做日志区分。
                    processName == ownPackage || processName.startsWith("$ownPackage:") -> appStopReason = reason

                    DAEMON_PROCESS.containsMatchIn(processName) -> daemonLmk += line
                }
                return@forEach
            }
            // `lmk_kill` 与 dmesg 的 lowmemorykiller 行没有可靠的 reason 字段，只能按进程名认。
            if (DAEMON_PROCESS.containsMatchIn(line)) daemonLmk += line
        }
        return KillProbe(daemonLmk, appStopReason)
    }

    private fun dir(context: Context): File = RootDeathLogStore.dir(context).also { it.mkdirs() }

    private fun readTimestamp(file: File): Long = runCatching {
        if (file.isFile) file.readText().trim().toLongOrNull() ?: 0L else 0L
    }.getOrElse { 0L }

    private fun writeTimestamp(file: File, value: Long) {
        file.parentFile?.mkdirs()
        file.writeText(value.toString())
    }

    private fun write(
        context: Context,
        trigger: String,
        deathWallMs: Long,
        deathElapsedMs: Long,
        windowStartMs: Long = deathWallMs - TOMBSTONE_WINDOW_MS,
        windowEndMs: Long = deathWallMs + TOMBSTONE_WINDOW_MS,
    ): File? {
        val crashRaw = sh("logcat -b crash -d -v threadtime -t 400").cap()
        // crash 缓冲是全系统共用的，先切出「我们自己」的崩溃块：判定只看这部分，
        // 报告也只展示这部分；原始缓冲另存一份「块头索引」，便于核对过滤有没有漏判。
        val ourBlocks = oursCrashBlocks(crashRaw, windowStartMs, windowEndMs)
        val ourCrash = ourBlocks.joinToString("\n\n").ifBlank { "<no crash block for our process in window>" }
        val mainLog = sh(
            // `libc ` 后面必须带空格：不带空格会把 libcore 的整摞栈帧也抓进来。
            "logcat -d -v threadtime -t 2500 | grep -aE 'RootDeath|NullReply|CancelFallback|LoadDiag|libc |F DEBUG|Fatal signal|tombstone|Abort message' | tail -n 200"
        ).cap()
        val dropbox = sh("dumpsys dropbox --print SYSTEM_TOMBSTONE 2>/dev/null | tail -n 250").cap()
        val dropboxApp = sh("dumpsys dropbox --print data_app_native_crash 2>/dev/null | tail -n 120").cap()
        val lmkEvents = sh("$LMK_EVENTS_CMD | tail -n 200").cap()
        val dmesg = sh("dmesg 2>/dev/null | grep -aiE 'lowmemorykiller|lmkd|killed process|out of memory|oom-kill' | tail -n 60").cap()
        val memInfo = sh("head -n 12 /proc/meminfo").cap()
        val processes = sh("ps -A -o PID,PPID,UID,RSS,NAME 2>/dev/null | grep -aE 'app_process|databackup' | head -n 10").cap()
        val tombstones = collectTombstones(windowStartMs, windowEndMs)

        // dmesg 在新系统上多半为空，作为 events buffer 的补充一起看。
        // 判定必须走与 startup-recovery 完全相同的过滤：整段 events 里混着全系统的 am_kill，
        // 直接正则匹配会把这台机器上任何一次内存回收都算成守护进程的 LMK。
        val lmkLines = parseKillEvents(
            text = lmkEvents + "\n" + dmesg,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            ownPackage = context.packageName,
        ).daemonLmk

        val (verdict, reason) = classify(
            ourBlocks = ourBlocks,
            tombstoneText = tombstones,
            lmkLines = lmkLines,
        )

        val report = buildString {
            appendLine("# root-death report")
            appendLine("time=${timeFormat().format(Date(deathWallMs))}")
            appendLine("epoch_ms=$deathWallMs")
            appendLine("elapsed_ms=$deathElapsedMs")
            appendLine("window_start_ms=$windowStartMs")
            appendLine("window_end_ms=$windowEndMs")
            appendLine("trigger=${trigger.oneLine()}")
            appendLine("verdict=${verdict.name}")
            appendLine("verdict_reason=${reason.oneLine()}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("app=${appVersion(context)}")
            appendLine("# collected by RootDeathCollector, stays on device")
            appendLine()
            appendSection("logcat-crash-ours", ourCrash)
            appendSection("logcat-crash-heads", crashBlockHeads(crashRaw))
            appendSection("logcat-main", mainLog)
            appendSection("dropbox-system-tombstone", dropbox)
            appendSection("dropbox-data-app-native-crash", dropboxApp)
            appendSection("lmk-events", lmkEvents)
            appendSection("dmesg", dmesg)
            appendSection("meminfo", memInfo)
            appendSection("processes", processes)
            appendSection("tombstones", tombstones)
        }

        val dir = RootDeathLogStore.dir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "cannot create ${dir.absolutePath}")
            return null
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(deathWallMs))
        val file = File(dir, "$FILE_PREFIX$stamp.log${RootDeathLogStore.GZIP_SUFFIX}")
        // 落盘即 gzip：现场文本通常 20~60 KB，压缩后只剩几 KB，
        // 既不丢证据，也不会因为留着多份报告把 filesDir 撑大。
        GZIPOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
            out.write(report.toByteArray())
        }
        RootDeathLogStore.prune(context)
        Log.i(TAG, "report written: ${file.absolutePath} ${file.length()}B verdict=${verdict.name} ($reason)")
        return file
    }

    private fun StringBuilder.appendSection(name: String, body: String) {
        appendLine("## $name")
        appendLine(body.ifBlank { "<empty>" })
        appendLine()
    }

    /** 只取时间窗内、且正文里确实是我们进程的 tombstone，避免把历史文件或别的应用当成证据。 */
    private fun collectTombstones(windowStartMs: Long, windowEndMs: Long): String {
        val names = sh("ls -t /data/tombstones 2>/dev/null | head -n 6")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(6)
            .toList()
        if (names.isEmpty()) return "<no tombstone directory entry>"

        val fresh = names.mapNotNull { name ->
            val stat = sh("stat -c '%Y %s' /data/tombstones/$name 2>/dev/null").trim().split(' ')
            val mtimeMs = stat.getOrNull(0)?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            val size = stat.getOrNull(1)?.toLongOrNull() ?: 0L
            Triple(name, mtimeMs, size)
        }.filter { (_, mtimeMs, _) -> mtimeMs in windowStartMs..windowEndMs }

        if (fresh.isEmpty()) {
            return "<no tombstone in [$windowStartMs, $windowEndMs]; candidates: ${names.joinToString()}>"
        }

        // tombstone 目录全系统共用，同窗口内别的应用崩溃也会留文件；正文里不含我们进程特征的
        // 一律不纳入，否则判定会把别人的 SIGSEGV 记到守护进程头上。
        val ours = fresh.mapNotNull { (name, mtimeMs, size) ->
            val body = sh("head -c $MAX_TOMBSTONE_BYTES /data/tombstones/$name 2>/dev/null")
            if (!PROCESS_HINT_REGEX.containsMatchIn(body)) {
                null
            } else {
                "--- /data/tombstones/$name (mtime=$mtimeMs, size=$size) ---\n${body.cap()}"
            }
        }
        if (ours.isEmpty()) {
            return "<no tombstone of our process in [$windowStartMs, $windowEndMs]; candidates: ${names.joinToString()}>"
        }
        return ours.take(2).joinToString("\n\n").cap()
    }

    /**
     * 从崩溃缓冲区里切出属于我们自己进程的崩溃块，并且只保留落在时间窗内的。
     *
     * crash buffer 全系统共用，不切块就会把别的应用崩溃（甚至上一次历史崩溃）记到守护进程头上。
     * 返回的块同时用于判定与报告展示，所以一段噪声都不会带进报告。
     */
    private fun oursCrashBlocks(crashText: String, windowStartMs: Long, windowEndMs: Long): List<String> {
        if (crashText.isBlank()) return emptyList()
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        var headMs = Long.MIN_VALUE

        fun flush() {
            if (current.isNotEmpty()) {
                val text = current.toString()
                if (headMs in windowStartMs..windowEndMs && OUR_PROCESS_REGEX.containsMatchIn(text)) {
                    blocks += text.trimEnd()
                }
            }
            current.setLength(0)
            headMs = Long.MIN_VALUE
        }

        crashText.lineSequence().forEach { line ->
            when {
                // 块头：Java 未捕获异常 / native 崩溃 / abort 摘要。
                CRASH_HEAD.containsMatchIn(line) -> {
                    flush()
                    headMs = lineTimeMs(line, windowEndMs)
                    current.appendLine(line)
                }

                line.startsWith(LOGCAT_BOUNDARY) -> flush()
                current.isNotEmpty() -> current.appendLine(line)
            }
        }
        flush()
        return blocks
    }

    /**
     * 原始崩溃缓冲的「块头索引」：每个崩溃块压成一行，格式为
     * `MM-DD HH:MM:SS.mmm 等级 标签: 首行  [Process: 包名, PID: n]`。
     *
     * 这里刻意**不做**窗口过滤——它的职责是让排查者看到「缓冲里还躺着哪些崩溃」，
     * 从而判断 [oursCrashBlocks] 的过滤是否漏判。完整栈一律不进报告：crash buffer 里
     * 绝大多数是几天前别的应用的栈，对本次死因没有任何信息量，却能占掉九成体积。
     */
    private fun crashBlockHeads(crashText: String): String {
        if (crashText.isBlank()) return "<empty>"
        val heads = mutableListOf<String>()
        var head: String? = null
        var who: String? = null

        fun flush() {
            val h = head ?: return
            heads += if (who != null) "$h  [$who]" else h
            head = null
            who = null
        }

        crashText.lineSequence().forEach { line ->
            when {
                CRASH_HEAD.containsMatchIn(line) -> {
                    flush()
                    head = crashHeadSummary(line)
                }

                line.startsWith(LOGCAT_BOUNDARY) -> flush()

                // `Process:`（Java 崩溃）或 `>>> pkg <<<`（native 崩溃）通常紧跟在块头后面。
                head != null && who == null ->
                    CRASH_PROCESS.find(line)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
                        ?.let { who = it.trim().take(120) }
            }
        }
        flush()

        if (heads.isEmpty()) return "<no crash block in buffer>"
        val shown = heads.takeLast(MAX_CRASH_HEAD_LINES)
        return buildString {
            appendLine("<${heads.size} block(s) in crash buffer, heads only>")
            shown.forEach { appendLine(it) }
            if (heads.size > shown.size) appendLine("... ${heads.size - shown.size} earlier block(s) omitted")
        }.cap()
    }

    /** 摘掉行首时间戳后的 pid/tid，只留「时间 + 等级 标签: 首行」。 */
    private fun crashHeadSummary(line: String): String {
        val parts = CRASH_HEAD_PARTS.find(line) ?: return line.trim().take(MAX_HEAD_CHARS)
        return "${parts.groupValues[1]} ${parts.groupValues[2].trim()}".take(MAX_HEAD_CHARS)
    }

    /**
     * 把 logcat 行首的 `MM-DD HH:MM:SS.mmm` 换算成墙钟毫秒，解析失败返回 [Long.MIN_VALUE]。
     *
     * logcat 不带年份，因此以窗口终点 [anchorMs] 为锚，在「前一年 / 今年 / 后一年」三个候选里
     * 取离锚点最近的一个，跨年时也不会整段证据被判成不在窗口内。
     */
    private fun lineTimeMs(line: String, anchorMs: Long): Long {
        val groups = LOGCAT_TS.find(line)?.groupValues ?: return Long.MIN_VALUE
        return runCatching {
            val cal = Calendar.getInstance()
            cal.clear()
            cal.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
            cal.set(Calendar.MONTH, groups[1].toInt() - 1)
            cal.set(Calendar.DAY_OF_MONTH, groups[2].toInt())
            cal.set(Calendar.HOUR_OF_DAY, groups[3].toInt())
            cal.set(Calendar.MINUTE, groups[4].toInt())
            cal.set(Calendar.SECOND, groups[5].toInt())
            cal.set(Calendar.MILLISECOND, groups[6].toInt())
            val base = cal.timeInMillis
            listOf(base - ONE_YEAR_MS, base, base + ONE_YEAR_MS)
                .minByOrNull { kotlin.math.abs(it - anchorMs) } ?: base
        }.getOrElse { Long.MIN_VALUE }
    }

    /**
     * 判定顺序：崩溃证据（我们自己进程的崩溃块 + 我们自己的 tombstone）优先，其次 LMK，
     * 两者都没有才归「被外部正常杀掉」。
     *
     * 前提：传入的两份证据都已按进程名筛过——crash 缓冲与 tombstone 目录都是全系统共用的。
     */
    private fun classify(
        ourBlocks: List<String>,
        tombstoneText: String,
        lmkLines: List<String>,
    ): Pair<RootDeathVerdict, String> {
        // 只有命中守护进程特征（app_process / rootservice / 我们的线程名）的块才算它的崩溃。
        // app 自身的 Java 崩溃同样在 crash 缓冲里，绝不能混进守护进程的死因。
        val ownBlocks = ourBlocks.filter { DAEMON_HINT_REGEX.containsMatchIn(it) }
        val daemonCrash = buildString {
            ownBlocks.forEach { appendLine(it) }
            appendLine(tombstoneText)
        }
        return when {
            SIGSEGV.containsMatchIn(daemonCrash) ->
                RootDeathVerdict.SIGSEGV to "native SIGSEGV"

            SIGABRT.containsMatchIn(daemonCrash) && ART_OOM.containsMatchIn(daemonCrash) ->
                RootDeathVerdict.ART_OOM to "ART heap exhausted -> abort"

            SIGABRT.containsMatchIn(daemonCrash) && PANIC.containsMatchIn(daemonCrash) ->
                RootDeathVerdict.RUST_PANIC to "rust panic -> abort"

            SIGABRT.containsMatchIn(daemonCrash) ->
                RootDeathVerdict.RUST_PANIC to "SIGABRT without abort message (panic or alloc failure)"

            JAVA_OOM.containsMatchIn(daemonCrash) ->
                RootDeathVerdict.ART_OOM to "uncaught OutOfMemoryError in the daemon"

            ownBlocks.isNotEmpty() ->
                RootDeathVerdict.DAEMON_JAVA_CRASH to "uncaught Java exception in the daemon (${ownBlocks.size} block(s))"

            lmkLines.isNotEmpty() ->
                RootDeathVerdict.LMK to "low memory killer (${lmkLines.size} event(s))"

            else ->
                RootDeathVerdict.SELF_KILL to "no crash record (killed by app or expected teardown)"
        }
    }

    private fun sh(script: String): String = runCatching {
        val result = Shell.cmd(script).exec()
        val out = result.out.joinToString("\n")
        if (out.isNotBlank()) out else result.err.joinToString("\n")
    }.getOrElse { "<shell failed: ${it.message}>" }

    private fun String.cap(max: Int = MAX_SECTION_CHARS): String =
        if (length <= max) this else take(max) + "\n... [truncated ${length - max} chars]"

    private fun String.oneLine(): String = replace('\n', ' ').replace('\r', ' ').take(200)

    private fun timeFormat(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrElse { "unknown" }

    private val SIGSEGV = Regex("""Fatal signal 11|signal 11 \(SIGSEGV\)""")
    private val SIGABRT = Regex("""Fatal signal 6|signal 6 \(SIGABRT\)|Abort message""")
    private val PANIC = Regex("""panicked at|thread '.*' panicked""")
    private val ART_OOM = Regex(
        """Out of memory|art::gc::Heap|Could not allocate|Failed to allocate|std::bad_alloc""",
        RegexOption.IGNORE_CASE,
    )
    /** Java 未捕获的 OOM：它不会是 native signal，必须单独认。 */
    private val JAVA_OOM = Regex("""java\.lang\.OutOfMemoryError""")

    /**
     * `am_kill : [user,pid,processName,oomAdj,reason]`。
     *
     * reason 里可能还带逗号（如 `stop xxx due to o-stop(40),341248`），所以最后一段用非贪婪
     * 一路取到收尾的 `]`，避免把尾部字段吞进原因里。
     */
    private val AM_KILL = Regex(
        """am_kill\s*:\s*\[\s*\d+\s*,\s*(\d+)\s*,\s*([^,\]]+)\s*,\s*(-?\d+)\s*,\s*(.+?)\s*\]"""
    )

    /** 用户清理 / 系统强停 / 覆盖安装：与内存压力无关，不能当作 LMK 证据。 */
    private val NORMAL_STOP = Regex(
        """o-stop|force-stop|due to from pid|installPackageLI|user-request|remove ?task""",
        RegexOption.IGNORE_CASE,
    )

    /** 守护进程在系统侧的名字：libsu 用 `app_process` 起 root 进程，命令行里带我们的包名。 */
    private val DAEMON_PROCESS = Regex("""app_process|core\.rootservice""", RegexOption.IGNORE_CASE)

    /**
     * 崩溃块的起点：Java 未捕获异常与 native 信号。
     *
     * 不含 `Abort message`——它是块内部的续行，把它当块头会把同一个 native 块劈成两段，
     * 前半段只剩一行 signal，容易丢进程特征。
     */
    private val CRASH_HEAD = Regex("""FATAL EXCEPTION|Fatal signal \d+""")

    /**
     * `MM-DD HH:MM:SS.mmm  pid  tid  等级 标签: 正文`。
     *
     * 块头摘要里 pid/tid 对排查没意义（同一进程内每次崩溃都不同），去掉后一行更短，
     * 但时间必须留下——它决定这个块是否落在本次窗口内。
     */
    private val CRASH_HEAD_PARTS = Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+\d+\s+\d+\s+(.*)$""")

    /**
     * 崩溃块里的进程标识：
     * - Java 崩溃：`Process: com.xayah.databackup, PID: 1234`
     * - native 崩溃：`pid: 1234, tid: 5678, name: xxx  >>> com.xayah.databackup <<<`
     * - 缓冲区被 `-t` 从块中间截断时只剩 `PID: 1234`，用它至少能和 `ps` 段对上。
     */
    private val CRASH_PROCESS = Regex("""(?:Process|Cmdline):\s*(.+)$|>>>\s*([^<\s]+)\s*<<<|(PID:\s*\d+)""")

    /** logcat `-v threadtime` 的行首时间戳 `MM-DD HH:MM:SS.mmm`（不带年份）。 */
    private val LOGCAT_TS = Regex("""^(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})""")

    /** 缓冲区滚动分隔行，出现即截断当前崩溃块。 */
    private const val LOGCAT_BOUNDARY = "--------- beginning of"

    /** 我们自己的进程特征（app 与守护进程都算），用于判断崩溃块是否与我们有关。 */
    private val OUR_PROCESS_REGEX = Regex("""app_process|databackup|core\.rootservice""", RegexOption.IGNORE_CASE)

    /** 守护进程专属特征：app 进程的栈里不会出现 rootservice / 这两条线程名。 */
    private val DAEMON_HINT_REGEX = Regex(
        """app_process|core\.rootservice|tar-fd-writer|fd-perf""",
        RegexOption.IGNORE_CASE,
    )

    /** 守护进程在系统侧的进程名特征（libsu 以 app_process 运行 + 我们的包名）。 */
    private const val PROCESS_HINT = "databackup"
    private val PROCESS_HINT_REGEX = Regex("""app_process|databackup""", RegexOption.IGNORE_CASE)

    private const val ONE_YEAR_MS = 365L * 24 * 60 * 60 * 1000L
}
