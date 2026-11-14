package com.xayah.core.rootservice.util

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.xayah.core.util.LogUtil
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
            val tombstoneHit = probeTombstone(windowStart, nowWall, context.packageName)
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

    /**
     * 探针：先按 mtime 缩小到窗口内，再确认崩的是**守护进程**。
     *
     * 归属只看 `Cmdline:` 行（见 [ownerOf]）：`Executable:` 一律是 `/system/bin/app_process64`，
     * app 主进程的 tombstone 同样如此，用 `app_process|包名` 做关键词会把 app 自己的崩溃
     * 当成守护进程的死因。
     */
    private fun probeTombstone(windowStartMs: Long, windowEndMs: Long, ownPackage: String): Boolean {
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
            // 只取 Cmdline 行：整个 tombstone 几十 KB，传回进程只为了判断归属。
            val cmdline = sh(
                "head -c $MAX_TOMBSTONE_BYTES /data/tombstones/$name 2>/dev/null | grep -a '^Cmdline:' | head -n 1"
            )
            ownerOf(cmdline, ownPackage) == ProcessOwner.DAEMON
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
        windowStartMs: Long? = null,
        windowEndMs: Long? = null,
    ): File? {
        val ownPackage = context.packageName

        // ---- 把「发现死亡」收敛到「真实死亡」----
        // 守护进程每 3s 覆写一次心跳，最后一次心跳之后它就没再动过；因此证据窗口应该围绕
        // 「心跳停摆时刻」开，而不是围绕「发现时刻」。本次那份 root-death-20261113 的
        // elapsed_ms 显示发现时距上次取证已 23 分钟，对称窗根本没有覆盖死亡时刻。
        val heartbeatNow = RootDeathHeartbeat.read(context)
        // libsu 复活的新实例会把主心跳覆写成本身，上一份被轮转成 `.prev`——
        // 真正死掉的那个往往在 `.prev` 里，所以两份都要读。
        val heartbeatPrev = RootDeathHeartbeat.readPrevious(context)
        // 保留旧的「围绕发现时刻」窗口，作为心跳不可信时的兜底与人工对照。
        val discoveryFrom = deathWallMs - TOMBSTONE_WINDOW_MS
        val discoveryTo = deathWallMs + TOMBSTONE_WINDOW_MS

        // 必须**最先**扫：下面任何一条 sh() 都会经 libsu 重新拉起 root 服务，
        // 于是守护进程会以新 pid 复活，之后再扫 /proc 就只能看到「活着的新进程」，
        // 把「进程已死」误读成「binder 单独断了」。
        val procScan = scanProcesses()

        // 选当事人：优先「已经不在 /proc 里」的那份心跳——那才是本次死掉的实例。
        val heartbeat = pickHeartbeat(listOfNotNull(heartbeatNow, heartbeatPrev), procScan.daemonPids, deathWallMs)
        val heartbeatWindow = heartbeatWindow(heartbeat, deathWallMs)
        val from = windowStartMs ?: heartbeatWindow?.first ?: discoveryFrom
        val to = windowEndMs ?: heartbeatWindow?.second ?: discoveryTo
        val windowSource = when {
            windowStartMs != null || windowEndMs != null -> "caller"
            heartbeatWindow != null -> "heartbeat"
            else -> "default"
        }
        val beatSource = when {
            heartbeat == null -> "none"
            heartbeat === heartbeatNow -> "current"
            else -> "previous"
        }
        val crashRaw = sh("logcat -b crash -d -v threadtime -t 400").cap()
        // crash 缓冲是全系统共用的，先切出「我们自己」的崩溃块：判定只看这部分，
        // 报告也只展示这部分；原始缓冲另存一份「块头索引」，便于核对过滤有没有漏判。
        val ourBlocks = oursCrashBlocks(crashRaw, from, to)
        val ourCrash = ourBlocks.joinToString("\n\n").ifBlank { "<no crash block for our process in window>" }
        val mainLog = sh(
            // `libc ` 后面必须带空格：不带空格会把 libcore 的整摞栈帧也抓进来。
            "logcat -d -v threadtime -t 2500 | grep -aE 'RootDeath|NullReply|CancelFallback|LoadDiag|libc |F DEBUG|Fatal signal|tombstone|Abort message' | tail -n 200"
        ).cap()
        val lmkEvents = sh("$LMK_EVENTS_CMD | tail -n 200").cap()
        // 三路 logcat 全空时无法区分「这段时间真的没有日志」与「采集本身失效」。
        // 探针只取 1 行：能取到任何一行就说明 logd 可读，全空则是采集侧问题。
        val logcatProbe = sh(
            "logcat -b crash -d -t 1 2>&1 | head -n 1; echo '---'; " +
                "logcat -d -t 1 2>&1 | head -n 1; echo '---'; " +
                "logcat -b events -d -t 1 2>&1 | head -n 1",
        ).cap()
        val logcatEmpty = crashRaw.isBlank() && mainLog.isBlank() && lmkEvents.isBlank()
        val logcatHealth = if (logcatEmpty) "all-three-buffers-empty" else "ok"

        // dropbox 是 protobuf tombstone 设备上唯一可读的文本证据，只能用 grep 抽头（见
        // [parseDropboxEntries]）；抽不到时退回整段落，避免格式变化导致整段丢失。
        val dropboxRaw = sh(DROPBOX_HEAD_CMD.format("SYSTEM_TOMBSTONE")).let {
            if (it.isBlank()) sh("dumpsys dropbox --print SYSTEM_TOMBSTONE 2>/dev/null | head -n 200").cap() else it.cap()
        }
        val dropboxAppRaw = sh(DROPBOX_HEAD_CMD.format("data_app_native_crash")).let {
            if (it.isBlank()) sh("dumpsys dropbox --print data_app_native_crash 2>/dev/null | head -n 120").cap() else it.cap()
        }
        val dropbox = parseDropboxEntries(dropboxRaw + "\n" + dropboxAppRaw, ownPackage, from, to)

        val dmesg = sh("dmesg 2>/dev/null | grep -aiE 'lowmemorykiller|lmkd|killed process|out of memory|oom-kill' | tail -n 60").cap()
        val memInfo = sh("head -n 12 /proc/meminfo").cap()
        // 存储剩余空间：mmap 写日志时若 /data 或 app data quota 耗尽，内核会直接向进程投递
        // SIGBUS；这条是区分「空间不足」与「介质/文件系统错误」的唯一现场证据。
        // stat -f 给精确块计数（部分 toybox 的 df 输出格式不稳），两者一起采。
        val disk = sh("stat -f /data 2>/dev/null; echo; df -h /data 2>/dev/null | tail -n 2").cap()

        // 只有心跳窗生效时才需要「发现窗对照」；caller 显式指定窗口时不要再掺第二套。
        val tombstones = if (windowSource == "heartbeat") {
            collectTombstones(from, to, ownPackage, discoveryFrom, discoveryTo)
        } else {
            collectTombstones(from, to, ownPackage)
        }

        // dmesg 在新系统上多半为空，作为 events buffer 的补充一起看。
        // 判定必须走与 startup-recovery 完全相同的过滤：整段 events 里混着全系统的 am_kill，
        // 直接正则匹配会把这台机器上任何一次内存回收都算成守护进程的 LMK。
        val lmkLines = parseKillEvents(
            text = lmkEvents + "\n" + dmesg,
            windowStartMs = from,
            windowEndMs = to,
            ownPackage = ownPackage,
        ).daemonLmk

        // app 进程自己的现场：被系统回收前的 adj、cgroup、以及 system_server 侧的视图。
        // DeadObjectException 只说明「对端死了」，区分不了「app 先死、守护进程连带」
        // 与「守护进程单独死」，这一段就是用来分开这两种情形的。
        val appProc = sh(
            "cat /proc/$SELF_PID/oom_score_adj 2>/dev/null | sed 's/^/oom_score_adj=/'; " +
                "awk '{print \"state=\" \$3}' /proc/$SELF_PID/stat 2>/dev/null; " +
                "grep -a '^cgroup' /proc/$SELF_PID/cgroup 2>/dev/null | head -n 2; " +
                "echo '--- dumpsys activity services ---'; " +
                "dumpsys activity services $ownPackage 2>/dev/null | head -n 20; " +
                "echo '--- dumpsys activity lru ---'; " +
                "dumpsys activity lru 2>/dev/null | grep -aE '$ownPackage|app_process' | head -n 5",
        ).cap()

        // ---- 「谁杀的」：三条旁证 ----
        // 1) 我们自己决定杀守护进程时写下的击杀日志（app 侧 destroyService/orNullReply、
        //    守护进程侧 forceStopSelf）。只认与「最后一次心跳」对齐的那些：上一次会话留下的
        //    旧记录时间对不上，天然被排除，所以这里判的是「这个时刻有没有人下手」。
        val journalAll = KillJournal.read(context)
        // 心跳停摆时刻是「谁杀的」的对齐基准，但空闲期那一跳是主动停的（mode=idle），
        // 用它当基准会把几条无关的旧击杀记录拉进容差窗口，退回发现时刻。
        val journalCenter = heartbeat?.takeIf { !it.idle }?.lastAliveMs?.takeIf { it > 0L } ?: deathWallMs
        val journalNear = journalAll.filter { kotlin.math.abs(it.tsMs - journalCenter) <= KILL_JOURNAL_TOLERANCE_MS }
        val journalNearest = journalNear.minByOrNull { kotlin.math.abs(it.tsMs - journalCenter) }

        // 2) app 进程自己的死亡记录：守护进程不是 AMS 管理的进程，events 里**不会**有它的
        //    am_kill；出现我们包名的 am_kill/am_proc_died 就说明「app 先死、守护进程连带」
        //    （binder 断 + 再没人续租）。这条专门用来分开 `## processes` 全空的情形。
        val amEventsRaw = sh(
            "logcat -b events -d -v threadtime -t 8000 2>/dev/null | " +
                "grep -aE 'am_kill|am_proc_died|am_proc_start|am_low_memory|am_anr|am_crash' | " +
                "grep -aiE '$ownPackage|app_process' | tail -n 40",
        ).cap()
        val amEvents = parseAmEvents(amEventsRaw, ownPackage, from, to)

        // 3) su 侧：外部杀掉 root 守护进程最常见的来源是 su 策略变更 / Magisk 模块 / ROM 清理。
        //    magisk --sqlite 在非 Magisk 设备上会失败，容错输出即可。
        val suPolicy = sh(
            "logcat -d -v threadtime -t 3000 2>/dev/null | grep -aiE 'magisk|libsu|superuser' | tail -n 30; " +
                "echo '--- /data/adb ---'; ls -la /data/adb/magisk* 2>/dev/null | head -n 12; " +
                "ls /data/adb/modules 2>/dev/null | head -n 12; " +
                "echo '--- magisk log ---'; magisk --sqlite 'select * from logs' 2>/dev/null | tail -n 15",
        ).cap()

        // 心跳里那个 pid 现在还在不在 /proc。libsu 复活出来的新进程 pid 不同，
        // 因此「进程还在」必须严格按 pidfile 的 pid 认，不能凭 daemonPids 非空。
        val pidAlive = when {
            heartbeat == null -> "unknown"
            heartbeat.pid in procScan.daemonPids -> "true"
            else -> "false"
        }

        val (verdict, reason) = classify(
            ourBlocks = ourBlocks,
            tombstoneText = listOf(tombstones.daemon, dropbox.daemon).filter { it.isNotBlank() }.joinToString("\n"),
            lmkLines = lmkLines,
            appCrashed = tombstones.app.isNotBlank() || dropbox.app.isNotBlank(),
            // 「守护进程还活着」必须按心跳里那个 pid 认：取证时 libsu 往往已经拉起了
            // 新的守护进程，pid 对不上说明是复活的新进程，不是原来那个还活着。
            daemonInPs = heartbeat?.let { it.pid in procScan.daemonPids } ?: false,
            daemonStop = heartbeat?.stop,
            killJournalHits = journalNear.size,
            killJournalDetail = journalNearest?.let { "${it.caller}@${it.tsMs}" },
            appKilledByAms = amEvents.appKilled,
        )

        val appLog = appLogTail(context)

        val report = buildString {
            appendLine("# root-death report")
            appendLine("time=${timeFormat().format(Date(deathWallMs))}")
            appendLine("epoch_ms=$deathWallMs")
            appendLine("elapsed_ms=$deathElapsedMs")
            appendLine("window_start_ms=$from")
            appendLine("window_end_ms=$to")
            appendLine("window_source=$windowSource")
            appendLine("trigger=${trigger.oneLine()}")
            appendLine("verdict=${verdict.name}")
            appendLine("verdict_reason=${reason.oneLine()}")
            // 守护进程自己的遗言。心跳文件同时充当 pidfile：pid/started/cmdline 都在里面。
            // 心跳只在任务活跃期跳（mode=active），空闲期只留一跳 mode=idle——那一跳的 mtime
            // 是「主动停跳」时刻，不是死亡上界。
            appendLine("pidfile_pid=${heartbeat?.pid ?: "<none>"}")
            appendLine("daemon_started_ms=${heartbeat?.startedMs ?: "<none>"}")
            appendLine("daemon_last_beat_ms=${heartbeat?.beatMs ?: "<none>"}")
            appendLine("daemon_beat_mode=${heartbeat?.mode?.ifBlank { "<none>" } ?: "<none>"}")
            // 门控依据：active / expired / absent / denied；<none> 是只有 mode 没有 lease 的旧心跳。
            // 看到 denied 说明守护进程 stat 不到租约（SELinux），心跳其实一直没停过，
            // 这时不能用「mode 是 idle」与否去推任务状态。
            appendLine("daemon_lease=${heartbeat?.lease?.ifBlank { "<none>" } ?: "<none>"}")
            appendLine("daemon_silence_ms=${heartbeat?.takeIf { it.lastAliveMs > 0L }?.let { deathWallMs - it.lastAliveMs } ?: "<unknown>"}")
            appendLine("daemon_stop=${heartbeat?.stop ?: "<none>"}")
            appendLine("daemon_in_ps=${procScan.daemonPids.joinToString(",").ifBlank { "<absent>" }}")
            // 那个 pid 现在还活着没有：复活出来的新实例 pid 不同，不会被算成「活着」。
            appendLine("pid_alive=$pidAlive")
            // 心跳取自哪一份：current = 主文件（死的就是当前实例或它根本没重启），
            // previous = 被轮转出来的上一份（libsu 已经拉起新实例，死的是老的）。
            appendLine("heartbeat_source=$beatSource")
            appendLine(
                "heartbeat_prev_pid=" + (
                    heartbeatPrev?.takeIf { it !== heartbeat }?.pid?.toString() ?: "<none>"
                    )
            )
            appendLine(
                "heartbeat_prev_last_alive_ms=" + (
                    heartbeatPrev?.takeIf { it !== heartbeat }?.lastAliveMs?.takeIf { it > 0L }?.toString() ?: "<none>"
                    )
            )
            appendLine("last_alive_ms=${heartbeat?.lastAliveMs?.takeIf { it > 0L } ?: "<none>"}")
            appendLine("heartbeat_mtime=${heartbeat?.mtimeMs?.takeIf { it > 0L } ?: "<none>"}")
            appendLine("daemon_comm=${heartbeat?.comm?.ifBlank { "<none>" } ?: "<none>"}")
            appendLine("daemon_cmdline=${heartbeat?.cmdline?.oneLine()?.ifBlank { "<none>" } ?: "<none>"}")
            // 心跳窗（约 30s，围绕停摆时刻）与旧的发现窗一起留档，便于人工对照。
            appendLine("discovery_window_start_ms=$discoveryFrom")
            appendLine("discovery_window_end_ms=$discoveryTo")
            appendLine("kill_journal_hits=${journalNear.size}")
            appendLine(
                "kill_journal_nearest=" + (
                    journalNearest?.let { "${it.caller}@${it.tsMs}(delta_ms=${it.tsMs - journalCenter})" } ?: "<none>"
                    )
            )
            appendLine("app_killed_by_ams=${amEvents.appKilled}")
            appendLine("app_pid=$SELF_PID")
            appendLine("logcat_health=$logcatHealth")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("app=${appVersion(context)}")
            appendLine("# collected by RootDeathCollector, stays on device")
            appendLine()
            // 第一条真线索：所有 `[RootDeath]` / `[NullReply]` / `[CancelFallback]` 都只经过
            // LogUtil 写文件、从不进 logcat，所以这一段是判断「谁下的手」的唯一来源。
            appendSection("app-log-tail", appLog)
            appendSection(
                "death-window",
                buildString {
                    appendLine("window_source=$windowSource")
                    appendLine("evidence_window_ms=$from..$to (width=${(to - from) / 1000}s, centred on last heartbeat)")
                    appendLine("discovery_window_ms=$discoveryFrom..$discoveryTo (old symmetric window, kept for comparison)")
                    val lastAlive = heartbeat?.lastAliveMs?.takeIf { it > 0L }
                    appendLine("last_alive_ms=${lastAlive ?: "<none>"}")
                    appendLine("heartbeat_age_ms=${lastAlive?.let { deathWallMs - it } ?: "<unknown>"}")
                    appendLine(
                        "death_upper_bound_ms=" + if (heartbeat?.idle == true) {
                            // 空闲期心跳主动停摆，最后一跳不是死亡上界，窗口只能取发现时刻。
                            "<n/a: heartbeat idle>"
                        } else {
                            lastAlive?.plus(RootDeathHeartbeat.BEAT_INTERVAL_MS)?.toString() ?: "<unknown>"
                        },
                    )
                },
            )
            appendSection("kill-journal", killJournalReport(journalAll, journalNear, journalCenter, context))
            appendSection("am-events", amEvents.text())
            appendSection("su-policy", suPolicy)
            appendSection("logcat-probe", logcatProbe)
            appendSection("logcat-crash-ours", ourCrash)
            appendSection("logcat-crash-heads", crashBlockHeads(crashRaw))
            appendSection("logcat-main", mainLog)
            appendSection("dropbox-ours", dropbox.ours)
            appendSection("dropbox-summary", dropbox.summary)
            appendSection("dropbox-headers", dropbox.raw)
            appendSection("lmk-events", lmkEvents)
            appendSection("dmesg", dmesg)
            appendSection("meminfo", memInfo)
            appendSection("disk", disk)
            appendSection("app-proc", appProc)
            appendSection("processes", procScan.text)
            appendSection("tombstone-candidates", tombstones.summary)
            appendSection("tombstones", tombstones.daemon)
            appendSection("app-tombstones", tombstones.app)
        }

        // 目录可能已被 root 守护进程先建出来（它写心跳与击杀日志），统一走 prepareDir
        // 避免「root 建 0755 → app 这边连报告都建不出来」。
        val dir = RootDeathLogStore.prepareDir(context)
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

    /** tombstone 取证结果：按归属拆开，app 的绝不能算成守护进程的。 */
    private data class TombstoneEvidence(
        /** 守护进程的 tombstone 正文；无则为说明串。判定只用这一份。 */
        val daemon: String,
        /** app 主进程自己的 tombstone 正文；空串表示窗口内 app 没有 tombstone。 */
        val app: String,
        /** 候选清单与丢弃计数，用于区分「目录为空」与「有文件但都不属于我们」。 */
        val summary: String = "",
    )

    /**
     * 只取时间窗内、且归属明确的 tombstone。
     *
     * 归属靠 `Cmdline:` 行判断（见 [ownerOf]）：旧的 `app_process|包名` 关键词会把 app 自己的
     * tombstone 一并收进来（任何 tombstone 的 `Executable:` 都是 app_process64，守护进程的
     * apk 路径里也含包名），于是 app 的 native 崩溃被记成「守护进程崩了」。
     * 拿不到 Cmdline 的按 UNKNOWN 丢弃——宁可少认，也不要把 app 的死因安到守护进程头上。
     */
    private fun collectTombstones(
        windowStartMs: Long,
        windowEndMs: Long,
        ownPackage: String,
        /** 旧的「围绕发现时刻」窗口，只用于告出被心跳窗甩掉的候选，不参与正文。 */
        discoveryStartMs: Long = windowStartMs,
        discoveryEndMs: Long = windowEndMs,
    ): TombstoneEvidence {
        // `.pb` 是 protobuf 格式的同一份 tombstone（Android 14 起为默认）。文本正则对它完全无效：
        // `Cmdline:` / `signal` 一行都读不出来，留着只会白占候选名额，把真正的文本 tombstone
        // 挤出 `head -n 6`，最后表现为「目录里没有 tombstone」这种假象。
        val names = sh("ls -t /data/tombstones 2>/dev/null | grep -av '\\.pb\$' | head -n 6")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(6)
            .toList()
        val pbDropped = sh("ls /data/tombstones 2>/dev/null | grep -ac '\\.pb\$'").trim().toIntOrNull() ?: 0
        if (names.isEmpty()) {
            return TombstoneEvidence(
                daemon = "<no readable tombstone ($pbDropped protobuf file(s) skipped)>",
                app = "",
                summary = "dir has $pbDropped .pb file(s), 0 text tombstone(s)",
            )
        }

        val stats = names.mapNotNull { name ->
            val stat = sh("stat -c '%Y %s' /data/tombstones/$name 2>/dev/null").trim().split(' ')
            val mtimeMs = stat.getOrNull(0)?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            val size = stat.getOrNull(1)?.toLongOrNull() ?: 0L
            Triple(name, mtimeMs, size)
        }
        val fresh = stats.filter { (_, mtimeMs, _) -> mtimeMs in windowStartMs..windowEndMs }
        // 对照窗：围绕「发现时刻」的旧窗口。心跳窗是围绕死亡时刻的，二者不一致时
        // 报告要能告出「旧窗口里本来有个东西、现在被甩掉了」，否则会看起来像数据丢失。
        val discoveryOnly = if (discoveryStartMs == windowStartMs && discoveryEndMs == windowEndMs) {
            emptyList<Triple<String, Long, Long>>()
        } else {
            stats.filter { (_, mtimeMs, _) ->
                mtimeMs in discoveryStartMs..discoveryEndMs && mtimeMs !in windowStartMs..windowEndMs
            }
        }
        val discoveryHint = if (discoveryOnly.isEmpty()) {
            ""
        } else {
            " | discovery-window-only: ${discoveryOnly.joinToString { it.first }}"
        }

        if (fresh.isEmpty()) {
            return TombstoneEvidence(
                daemon = "<no tombstone in [$windowStartMs, $windowEndMs]; candidates: ${names.joinToString()}>$discoveryHint",
                app = "",
                summary = "candidates: ${names.joinToString()} (+$pbDropped .pb skipped), " +
                    "none in heartbeat window, discovery-window-only=${discoveryOnly.size}",
            )
        }

        val daemon = mutableListOf<String>()
        val app = mutableListOf<String>()
        var dropped = 0
        fresh.forEach { (name, mtimeMs, size) ->
            val body = sh("head -c $MAX_TOMBSTONE_BYTES /data/tombstones/$name 2>/dev/null")
            val entry = "--- /data/tombstones/$name (mtime=$mtimeMs, size=$size) ---\n${body.cap()}"
            when (ownerOf(body, ownPackage)) {
                ProcessOwner.DAEMON -> daemon += entry
                ProcessOwner.APP -> app += entry
                // 归属不明（第三方应用、或 protobuf 文本解析失败）一律丢弃，
                // 但必须留下计数：否则「窗口内 0 个 tombstone」与「有 3 个但都不是我们的」
                // 在报告里长得一模一样，会把排查人引向错误结论。
                ProcessOwner.UNKNOWN -> dropped++
            }
        }
        return TombstoneEvidence(
            daemon = daemon.take(2).joinToString("\n\n").cap().ifBlank {
                "<no daemon tombstone in [$windowStartMs, $windowEndMs]; candidates: ${names.joinToString()}>$discoveryHint"
            },
            app = app.take(1).joinToString("\n\n").cap(),
            summary = buildString {
                append("in-window=${fresh.size}, daemon=${daemon.size}, app=${app.size}, ")
                append("unknown-owner-dropped=$dropped, .pb-skipped=$pbDropped, ")
                append("discovery-window-only=${discoveryOnly.size}")
                if (discoveryOnly.isNotEmpty()) append(" (${discoveryOnly.joinToString { it.first }})")
            },
        )
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

    /** tombstone / 崩溃块归属。`Executable:` 区分不了（app 与守护进程都是 app_process64）。 */
    private enum class ProcessOwner { DAEMON, APP, UNKNOWN }

    /**
     * 从正文的 `Cmdline:` 行判断归属。
     *
     * - app 进程：zygote fork 后 argv[0] 被改成包名，Cmdline 就是 `com.xayah.databackup.xxx`；
     *   多进程组件（`:remote` 等）是 `com.xayah.databackup.xxx:remote`。
     * - 守护进程：libsu 以
     *   `app_process -Djava.class.path=/data/app/~~xx==/<pkg>-yy==/base.apk /system/bin --nice-name=.. <mainClass>`
     *   启动，首 token 是 `app_process` / `app_process64`。
     *
     * 守护进程的 apk 路径里**也含包名**，所以「正文含包名」不能判成 app，必须整行相等。
     * 拿不到 Cmdline 时返回 [ProcessOwner.UNKNOWN]，由调用方丢弃。
     *
     * 判成 UNKNOWN 的代价是致命的：这份 tombstone 会被丢掉，`signalOf` 再也看不到那条
     * `signal 7 (SIGBUS)`，一条确凿的 native 崩溃就落到兜底分支被报成「外部 SIGKILL」。
     * 所以「像守护进程」的判定要留两条后路——首 token 的**basename**（`/system/bin/app_process64`
     * 这种带路径的写法也得认），以及 app_process 的启动参数 `-Djava.class.path=`（部分
     * libsu 版本用 `--nice-name` 改写了 argv[0]，首 token 已经不再是 app_process；而 zygote
     * fork 出来的 app 进程 Cmdline 只有包名，不可能带这个参数）。
     */
    private fun ownerOf(text: String, ownPackage: String): ProcessOwner {
        val cmdlines = CMDLINE_LINE.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (cmdlines.isEmpty()) return ProcessOwner.UNKNOWN
        if (cmdlines.any { it.substringBefore(' ').substringAfterLast('/').startsWith("app_process") }) {
            return ProcessOwner.DAEMON
        }
        if (cmdlines.any { it.contains("-Djava.class.path=") }) return ProcessOwner.DAEMON
        if (cmdlines.any { it == ownPackage || it.startsWith("$ownPackage:") }) return ProcessOwner.APP
        return ProcessOwner.UNKNOWN
    }

    /**
     * 提取 native 信号编号：tombstone 里是 `signal 7 (SIGBUS)`，logcat 里是 `Fatal signal 7`。
     *
     * 按编号判而不是给每个信号名单开正则：只认 SIGSEGV/SIGABRT 会把 SIGBUS(7)、SIGILL(4)、
     * SIGFPE(8) 全漏到最后的兜底分支，把 native 崩溃误报成「被正常杀掉」。
     */
    private fun signalOf(text: String): Int? =
        SIGNAL_WITH_NAME.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: SIGNAL_BARE.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** 本进程 pid：`/proc/<pid>/...` 只读这个进程自己的状态，不能用 `$!`/`$$`（那是 root shell 的）。 */
    private val SELF_PID: Int = android.os.Process.myPid()

    /**
     * 击杀日志（[KillJournal]）与「最后一次心跳」的对齐容差。
     *
     * 记录是在动手**之前**写下的，正常情况 `journal_ts - last_beat` 落在 `[0, BEAT_INTERVAL]`
     * 内；留 15s 是为了容忍心跳线程被内存压力拖慢。上一次会话留下的旧记录时间对不上，
     * 天然被排除——这正是按时间窗判「谁杀的」而不是看「文件里有没有记录」的原因。
     */
    private const val KILL_JOURNAL_TOLERANCE_MS = 15_000L

    /** 报告里最多列多少条击杀日志（文件本身另有上限）。 */
    private const val KILL_JOURNAL_TAIL_LINES = 20

    /** app 日志只回读尾部：整份可能几 MB，取证不需要全量。 */
    private const val APP_LOG_TAIL_BYTES = 64 * 1024

    /**
     * 只抽 dropbox 条目的头（时间戳行 / signal / Cmdline / Cause / Abort message / 栈帧），
     * 弃掉十六进制内存转储与 thread 尾部的寄存器块。
     *
     * 原实现是 `tail -n 250`：一个 4MB 的 tombstone 的尾部只有内存 dump，`signal`、
     * `Cmdline:`、`Cause:` 全在头部，等于每次都在读最没用的部分。改 grep 还有额外好处——
     * 它对**所有**条目都生效，而不是只覆盖最后打印出来的那一条。
     */
    private val DROPBOX_HEAD_CMD = "dumpsys dropbox --print %s 2>/dev/null | " +
        "grep -aE '^[0-9]{4}-[0-9]{2}-[0-9]{2} |signal [0-9]+ \\(|^Cmdline:|^pid: |^tid: |" +
        "^Cause:|^Abort message|FATAL EXCEPTION|^Process: |^ *#[0-9][0-9] pc' | head -n 160"

    /** `2026-11-13 23:59:45 SYSTEM_TOMBSTONE (text, 1234 bytes)` 的时间戳部分。 */
    private val DROPBOX_ENTRY_HEADER = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})""")

    /** Java 崩溃条目里的 `Process: com.xayah.databackup.xxx`。 */
    private val PROCESS_LINE = Regex("""(?m)^Process:\s*(\S+)""")

    /**
     * 选出「本次死亡的当事人」。
     *
     * libsu 发现守护进程死后会立刻拉起新实例，新实例的 `start()` 会把心跳文件覆写成本身；
     * 若直接读主文件，就会把「A 死了、B 活着」读成「守护进程还活着，只是 binder 断了」。
     * [RootDeathHeartbeat] 在启动时把「没留遗言的上一份」轮转成 `.prev`，这里优先选那个
     * **已经不在 /proc 里**的 pid；两份都活着（或不都可信）时退回最近心跳的那份。
     */
    private fun pickHeartbeat(
        candidates: List<RootDeathHeartbeat.Evidence>,
        livePids: Collection<Int>,
        deathWallMs: Long,
    ): RootDeathHeartbeat.Evidence? {
        if (candidates.isEmpty()) return null
        val plausible = candidates.filter { evidence ->
            val age = deathWallMs - evidence.lastAliveMs
            age in -RootDeathHeartbeat.BEAT_INTERVAL_MS..RootDeathHeartbeat.MAX_EVIDENCE_SPAN_MS
        }
        return plausible.firstOrNull { it.pid !in livePids }
            ?: plausible.maxByOrNull { it.lastAliveMs }
            ?: candidates.maxByOrNull { it.lastAliveMs }
    }

    /**
     * 以「最后一次心跳」为中心开窗，而不是围绕取证时刻。
     *
     * 死亡必然发生在最后一次心跳之后的 [RootDeathHeartbeat.BEAT_INTERVAL_MS] 内，所以
     * `lastAlive + beat_interval` 是死亡时刻的硬上界；向前则留满 [TOMBSTONE_WINDOW_MS]，
     * 因为信号可能在写心跳之前就到了（日志/tombstone 的 mtime 会略早于 beat）。
     *
     * 这份报告之前的做法是 `[beat - 1s, 发现时刻]`：发现得晚（本次 23 分钟）时会变成一个
     * 几分钟宽的大窗口，把无关 tombstone 全吸进来；改动后固定为约 30s 宽。
     *
     * 返回 null 表示心跳不可用或时间不可信（时钟回拨、跨度超过 MAX_EVIDENCE_SPAN_MS），
     * 此时退回围绕取证时刻的默认窗，并由报告头的 `window_source=default` 标出。
     */
    private fun heartbeatWindow(evidence: RootDeathHeartbeat.Evidence?, deathWallMs: Long): Pair<Long, Long>? {
        val beat = evidence ?: return null
        // 心跳只在任务活跃期跳：空闲期的最后一跳是我们自己「主动停跳」写下的，
        // 它的 mtime 只是停跳时刻，真实死亡可能晚得多，不能当死亡上界用。
        if (beat.idle) return null
        val lastAlive = beat.lastAliveMs.takeIf { it > 0L } ?: return null
        val span = deathWallMs - lastAlive
        if (span < 0L || span > RootDeathHeartbeat.MAX_EVIDENCE_SPAN_MS) return null
        val start = lastAlive - TOMBSTONE_WINDOW_MS
        val end = minOf(deathWallMs, lastAlive + RootDeathHeartbeat.BEAT_INTERVAL_MS + TOMBSTONE_WINDOW_MS)
        return start to end
    }

    private data class ProcessScan(
        val text: String,
        /** 扫到的 app_process 进程 pid（通常最多一个：守护进程）。 */
        val daemonPids: List<Int>,
    )

    /**
     * 扫 `/proc/<pid>/cmdline` 找我们自己。
     *
     * 不能用 `ps -A -o NAME`：comm 被内核截到 15 字符，本 app 只剩 `com.xayah.datab`，
     * 用包名 grep 永远匹配不上（历史报告里 `## processes` 恒为空就是这个原因）。
     * cmdline 是完整 argv、不受截断影响，app 与守护进程（`app_process ...`）都能命中。
     *
     * 用 `case` 而不是每进程 fork 一个 grep：设备上通常有几百个进程，逐个 fork 会拖住取证。
     */
    private fun scanProcesses(): ProcessScan {
        val raw = sh(
            "for p in /proc/[0-9]*/cmdline; do " +
                "pid=\${p%/cmdline}; pid=\${pid#/proc/}; " +
                "c=\$(tr '\\0' ' ' < \$p 2>/dev/null); " +
                "case \"\$c\" in *app_process*|*databackup*) echo \"\$pid \$c\";; esac; " +
                "done | head -n 12",
        )
        val daemonPids = raw.lineSequence().mapNotNull { line ->
            val pid = line.trim().substringBefore(' ').toIntOrNull() ?: return@mapNotNull null
            val cmdline = line.trim().substringAfter(' ', "")
            if (cmdline.substringBefore(' ').startsWith("app_process")) pid else null
        }.toList()
        return ProcessScan(text = raw.cap(), daemonPids = daemonPids)
    }

    private data class DropboxEvidence(
        /** 窗口内归属我们的条目（守护进程 + app），按段落拼接。 */
        val ours: String,
        /** 其中守护进程的部分，供 [classify] 当 tombstone 文本用。 */
        val daemon: String,
        /** 其中 app 主进程的部分。 */
        val app: String,
        /** 每条目的「时间 + 归属 + 是否在窗口内」一览，用于识破「别家崩溃冒充我们」。 */
        val summary: String,
        /** 抽头后的原始文本，留档用。 */
        val raw: String,
    )

    /**
     * 解析 dropbox 条目并按归属 + 时间窗口归类。
     *
     * protobuf tombstone 设备上 `/data/tombstones` 读不出文本，dropbox 是**唯一**可读来源，
     * 所以必须做归属判定：本次那份报告里 `## dropbox-system-tombstone` 摆着的其实是微信
     * （`com.tencent.mm`）的 native 崩溃，混在报告的「系统 tombstone」段里极易被读成
     * 「daemon 崩了」。
     */
    private fun parseDropboxEntries(
        text: String,
        ownPackage: String,
        windowStartMs: Long,
        windowEndMs: Long,
    ): DropboxEvidence {
        if (text.isBlank()) {
            return DropboxEvidence("", "", "", "<no dropbox entry readable>", "")
        }

        val blocks = mutableListOf<Pair<String, String>>()
        var header: String? = null
        val body = StringBuilder()
        text.lineSequence().forEach { line ->
            val match = DROPBOX_ENTRY_HEADER.find(line)
            if (match != null && match.range.first == 0) {
                header?.let { blocks += it to body.toString() }
                header = line.trim()
                body.setLength(0)
            } else if (header != null) {
                body.appendLine(line)
            }
        }
        header?.let { blocks += it to body.toString() }
        // 抽不到条目头（dumpsys 格式变化，或退回整段输出）时按一条处理，宁可粗也不丢证据。
        if (blocks.isEmpty()) blocks += "<unparsed dropbox output>" to text

        val summary = StringBuilder()
        val daemon = StringBuilder()
        val app = StringBuilder()
        var daemonCount = 0
        var appCount = 0
        var otherCount = 0
        blocks.forEach { (line, content) ->
            val timeMs = runCatching {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(line.take(19))?.time
            }.getOrNull()
            val owner = ownerOf(content, ownPackage).let {
                if (it == ProcessOwner.UNKNOWN) ownerFromProcessLine(content, ownPackage) else it
            }
            val inWindow = timeMs == null || timeMs in windowStartMs..windowEndMs
            summary.appendLine("${line.oneLine()} -> owner=${owner.name}, in_window=$inWindow")
            when {
                !inWindow -> Unit
                owner == ProcessOwner.DAEMON -> {
                    daemonCount++
                    daemon.appendLine(line).appendLine(content)
                }

                owner == ProcessOwner.APP -> {
                    appCount++
                    app.appendLine(line).appendLine(content)
                }

                else -> otherCount++
            }
        }

        val ours = buildString {
            if (daemon.isNotEmpty()) appendLine("### daemon").appendLine(daemon.toString().trim())
            if (app.isNotEmpty()) appendLine("### app").appendLine(app.toString().trim())
        }
        return DropboxEvidence(
            ours = ours.cap(),
            daemon = daemon.toString().cap(),
            app = app.toString().cap(),
            summary = buildString {
                appendLine("entries=${blocks.size}, daemon=$daemonCount, app=$appCount, other=$otherCount in [$windowStartMs, $windowEndMs]")
                append(summary)
            }.cap(),
            raw = text.cap(),
        )
    }

    /** Java 崩溃条目没有 `Cmdline:`，只有 `Process: <包名>`。 */
    private fun ownerFromProcessLine(text: String, ownPackage: String): ProcessOwner {
        val name = PROCESS_LINE.find(text)?.groupValues?.get(1)?.trim() ?: return ProcessOwner.UNKNOWN
        return if (name == ownPackage || name.startsWith("$ownPackage:")) ProcessOwner.APP else ProcessOwner.UNKNOWN
    }

    /**
     * 回读 app 自己的日志尾部。
     *
     * 这是本次取证最大的缺口：`[RootDeath] root daemon died, reporting once:`、
     * `[NullReply] ... killing the root daemon`、`[CancelFallback] forceStopSelf called`
     * 全部走 [LogUtil.log] 写文件，**从不进 logcat**，所以 `## logcat-main` 里永远看不到
     * 「谁下的手」。日志流是 `FileOutputStream(append=true)` 无缓冲写，读到的就是已落盘内容。
     */
    private fun appLogTail(context: Context): String {
        val file = runCatching { LogUtil.currentLogFile() }.getOrNull()
            ?: return "<LogUtil has no log file in this process (initialize never ran)>"
        if (!file.isFile) return "<log file missing: ${file.absolutePath}>"
        return runCatching {
            val length = file.length()
            val start = (length - APP_LOG_TAIL_BYTES).coerceAtLeast(0L)
            val tail = java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buffer = ByteArray((length - start).toInt())
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
            }
            // 起点可能切在多字节字符或半行中间，丢掉第一行残片。
            val lines = tail.lines().let { if (start > 0L) it.drop(1) else it }
            "path=${file.absolutePath}, size=$length, showing last ${lines.size} line(s)\n" +
                lines.joinToString("\n")
        }.getOrElse { "<cannot read ${file.absolutePath}: ${it.message}>" }
    }

    /**
     * 生成 `## kill-journal` 段：把我们自己决定杀守护进程的那些记录摊开。
     *
     * 关键是「对齐」：只有与最后一次心跳相距 ≤ [KILL_JOURNAL_TOLERANCE_MS] 的条目才算
     * 本次死亡的原因；文件里更早的条目（上一次会话的正常重启）只作为历史尾巴展示，
     * 不参与判定。
     */
    private fun killJournalReport(
        all: List<KillJournal.Entry>,
        near: List<KillJournal.Entry>,
        centerMs: Long,
        context: Context,
    ): String = buildString {
        appendLine("file=${KillJournal.file(context).absolutePath}")
        appendLine("entries=${all.size}, in_window=${near.size}, center_ms=$centerMs, tolerance_ms=$KILL_JOURNAL_TOLERANCE_MS")
        appendLine("--- aligned with last heartbeat ---")
        if (near.isEmpty()) {
            appendLine("<none: no kill was recorded around this moment>")
        } else {
            near.forEach { appendLine("${it.raw}  delta_ms=${it.tsMs - centerMs}") }
        }
        appendLine("--- last ${minOf(all.size, KILL_JOURNAL_TAIL_LINES)} entry(ies) ---")
        if (all.isEmpty()) appendLine("<journal never written>")
        else all.takeLast(KILL_JOURNAL_TAIL_LINES).forEach { appendLine(it.raw) }
    }

    /** events 缓冲里与本次死亡相关的 AMS 动作。 */
    private data class AmEvidence(val lines: List<String>, val appKilled: Boolean) {
        fun text(): String =
            if (lines.isEmpty()) "<no am_kill/am_proc_died/am_proc_start for our package in window>"
            else lines.joinToString("\n")
    }

    /**
     * 解析 `am_kill` / `am_proc_died`。
     *
     * 守护进程是 `app_process` 拉起的，不归 AMS 管，所以 events 里**不会**出现它；
     * 能出现我们包名的只有 app 主进程，因此「命中 + 在窗口内」即「app 先死、守护进程连带」。
     */
    private fun parseAmEvents(text: String, ownPackage: String, windowStartMs: Long, windowEndMs: Long): AmEvidence {
        if (text.isBlank()) return AmEvidence(emptyList(), false)
        val picked = mutableListOf<String>()
        var killed = false
        text.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val ts = lineTimeMs(line, windowEndMs)
            if (ts != Long.MIN_VALUE && ts !in windowStartMs..windowEndMs) return@forEach
            // 两个正则都让 processName 落在第 2 组（第 1 组是 pid），与 LMK 侧的 AM_KILL 对齐。
            val kill = AM_KILL.find(line)
            val died = AM_PROC_DIED.find(line)
            val name = kill?.groupValues?.get(2)?.trim() ?: died?.groupValues?.get(2)?.trim()
            if (name != null && (name == ownPackage || name.startsWith("$ownPackage:"))) {
                if (kill != null || died != null) killed = true
            }
            picked += line.trim()
        }
        return AmEvidence(picked.takeLast(40), killed)
    }

    /**
     * 判定顺序：守护进程的崩溃证据 → LMK → app 自己崩溃 → 无证据。
     *
     * 前提：传入的两份证据都已按归属筛过——crash 缓冲、tombstone 目录、dropbox 都是全系统共用的。
     */
    private fun classify(
        ourBlocks: List<String>,
        tombstoneText: String,
        lmkLines: List<String>,
        appCrashed: Boolean,
        /** 死后扫 /proc 是否还能看到 app_process：用来区分 binder 断了与进程真的没了。 */
        daemonInPs: Boolean = false,
        /** 守护进程自己留下的遗言；非 null 即「我们主动杀」的铁证。 */
        daemonStop: String? = null,
        /** 与最后一次心跳对齐的击杀日志条数：>0 即「app 自己下的手」。 */
        killJournalHits: Int = 0,
        /** 最近一条击杀日志（caller@ts），只用于说明文字。 */
        killJournalDetail: String? = null,
        /** events 缓冲里出现了我们包名的 am_kill/am_proc_died：app 先死、守护进程连带。 */
        appKilledByAms: Boolean = false,
    ): Pair<RootDeathVerdict, String> {
        // 只有命中守护进程特征（app_process / rootservice / 我们的线程名）的块才算它的崩溃。
        // app 自身的 Java 崩溃同样在 crash 缓冲里，绝不能混进守护进程的死因。
        val ownBlocks = ourBlocks.filter { DAEMON_HINT_REGEX.containsMatchIn(it) }
        val daemonCrash = buildString {
            ownBlocks.forEach { appendLine(it) }
            appendLine(tombstoneText)
        }
        val signal = signalOf(daemonCrash)
        return when {
            signal == 11 -> RootDeathVerdict.SIGSEGV to "native SIGSEGV (signal 11)"

            signal == 6 && ART_OOM.containsMatchIn(daemonCrash) ->
                RootDeathVerdict.ART_OOM to "ART heap exhausted -> abort"

            signal == 6 && PANIC.containsMatchIn(daemonCrash) ->
                RootDeathVerdict.RUST_PANIC to "rust panic -> abort"

            signal == 6 ->
                RootDeathVerdict.RUST_PANIC to "SIGABRT without abort message (panic or alloc failure)"

            // SIGBUS(7)/SIGILL(4)/SIGFPE(8)/SIGTRAP(5)：守护进程被 native 信号带走。
            signal != null ->
                RootDeathVerdict.NATIVE_CRASH to "native crash (signal $signal)"

            // 缓冲区被 -t 截断时只剩 `Abort message`，没有 signal 行，仍按 abort 处理。
            ABORT_MESSAGE.containsMatchIn(daemonCrash) ->
                RootDeathVerdict.RUST_PANIC to "abort message without signal line (panic or alloc failure)"

            JAVA_OOM.containsMatchIn(daemonCrash) ->
                RootDeathVerdict.ART_OOM to "uncaught OutOfMemoryError in the daemon"

            ownBlocks.isNotEmpty() ->
                RootDeathVerdict.DAEMON_JAVA_CRASH to "uncaught Java exception in the daemon (${ownBlocks.size} block(s))"

            lmkLines.isNotEmpty() ->
                RootDeathVerdict.LMK to "low memory killer (${lmkLines.size} event(s))"

            // 守护进程没有任何崩溃证据，但 app 主进程自己被 native 信号打死了：
            // 真相是 app 崩了，不是 root 出了问题（本次报告的 SIGBUS 就属这一类）。
            appCrashed ->
                RootDeathVerdict.APP_CRASH to "daemon left no crash evidence; the app process itself crashed"

            // 无崩溃证据时不能只写「no crash record」：下面几种情形在系统侧长得一模一样，
            // 但排查方向完全不同，过去的报告把它们糊成一句话，等于没有结论。
            // 判定顺序即证据强度：遗言 > 击杀日志 > 进程仍在 > app 先死 > 外部 SIGKILL。
            daemonStop != null ->
                RootDeathVerdict.SELF_KILL to "daemon left a goodbye note before dying: $daemonStop"

            // 击杀日志在「心跳停摆时刻 ±15s」内有记录：app 侧 destroyService/orNullReply
            // 或守护进程侧 forceStopSelf 亲手下的手，时间能对上就不必再猜。
            killJournalHits > 0 ->
                RootDeathVerdict.SELF_KILL to "killed by app teardown at the last heartbeat " +
                    "($killJournalHits journal entr(ies), nearest=$killJournalDetail)"

            daemonInPs ->
                RootDeathVerdict.SELF_KILL to "daemon is still alive in /proc, yet its binder died " +
                    "(binder-only death: check destroyService / remote process, not the daemon itself)"

            // events 里出现我们包名的 am_kill/am_proc_died：app 主进程先被 AMS 干掉，
            // 守护进程失去 binder 客户端后无人续租，随之退出。不是 root 的问题。
            appKilledByAms ->
                RootDeathVerdict.SELF_KILL to "the app process was killed first " +
                    "(am_kill/am_proc_died for our package in window); the daemon went down as collateral"

            else ->
                RootDeathVerdict.SELF_KILL to "daemon absent from /proc, no journal entry, no am_kill, " +
                    "no tombstone -> external SIGKILL (su policy / system / user)"
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

    /** `signal 7 (SIGBUS)`：tombstone 与 logcat 的 native 崩溃行。 */
    private val SIGNAL_WITH_NAME = Regex("""signal (\d+) \(SIG[A-Z0-9]+\)""")

    /** `Fatal signal 7`：logcat 摘要行，不带信号名。 */
    private val SIGNAL_BARE = Regex("""Fatal signal (\d+)""")

    /** 缓冲区被截断时只剩 abort 摘要、没有 signal 行，用这条兜底。 */
    private val ABORT_MESSAGE = Regex("""Abort message""")

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

    /** `am_proc_died : [user,pid,processName,adj]`——app 进程被回收/杀死时 AMS 的记账。 */
    private val AM_PROC_DIED = Regex(
        """am_proc_died\s*:\s*\[\s*\d+\s*,\s*(\d+)\s*,\s*([^,\]]+)"""
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

    /**
     * tombstone 的进程命令行，形如：
     * - app：`Cmdline: com.xayah.databackup.revived.foss`
     * - 守护进程：`Cmdline: app_process -Djava.class.path=/data/app/... /system/bin --nice-name=.. <mainClass>`
     */
    private val CMDLINE_LINE = Regex("""^Cmdline:[ \t]*(.+)$""", RegexOption.MULTILINE)

    private const val ONE_YEAR_MS = 365L * 24 * 60 * 60 * 1000L
}
