package com.xayah.core.rootservice.util

import android.content.Context
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 守护进程的「遗言」文件（心跳 + 受控退出原因）。
 *
 * 之前所有取证都在 app 侧、且发生在守护进程死之后，能看到的只有系统侧残留
 * （tombstone / dropbox / logcat），而 SIGKILL 与 `Process.killProcess` 都不留任何残留，
 * 于是只能落成 `SELF_KILL`（本次那份 `root-death-20261113-235945.log` 就是这种情况）。
 *
 * 这里补上 processus 侧的现场，四件事：
 * 1. **pidfile**：写 `pid` / `started_ms` / `cmdline` / `comm`，取证时能区分「守护进程被换过」、
 *    「从未起来」与「pm 看到的这个 pid 是不是我们那个」；
 * 2. **心跳**：每 [BEAT_INTERVAL_MS] 覆写 `beat_ms`，把「发现死亡」与「真实死亡」分开——
 *    本次报告的 `elapsed_ms` 显示发现时距上次取证已 23 分钟，15 秒的取证窗口很可能整个
 *    错过了真正的死亡时刻。心跳文件自己的 **mtime** 就是死亡时刻的上界，取证窗口直接
 *    围绕它开；
 * 3. **遗言**：受控退出前（[noteStop]）写一行 `stop=<原因>`，直接区分「app 自己下的手」
 *    与「外部 SIGKILL」；
 * 4. **进程名**：把主线程的 comm 改成 [COMM_NAME]（`/proc/self/task/<tid>/comm` 对自己可写，
 *    不需要 JNI 的 `prctl(PR_SET_NAME)`）。这样 `ps -o NAME` 不再只剩被内核截断 15 字符的
 *    `com.xayah.datab`，取证侧多一条不依赖 cmdline 的识别路径。
 *
 * 拦不住的部分：**SIGKILL / SIGTERM 无法拦截**（ART 只在 `Runtime.exit` / 主线程返回时跑
 * shutdown hook，外部信号是默认处置）。要覆盖裸 SIGTERM 必须在 native 层装 signal handler，
 * 属后续工作；这里靠心跳把死亡时间收敛到一个 [BEAT_INTERVAL_MS] 宽的区间。
 * `PR_SET_DUMPABLE` 同理需要 native 调用，且只影响 `/proc/<pid>/mem` 可读性，与死因无关，不做。
 *
 * 文件落在 `filesDir/root_death/daemon.heartbeat`——与报告同目录，app 侧直接可读，
 * 但**不**以 `root-death-` 前缀命名，因此不会被 [RootDeathLogStore.list] 当成报告。
 *
 * ## 只在任务活跃期跳
 *
 * 心跳原本是不管有没有事都每 [BEAT_INTERVAL_MS] 写一次，而真正需要「死亡时刻」的场景只有
 * 长任务（备份/恢复/压缩基准）在跑的时候——那时候守护进程死掉会直接变成一次用户可见的失败。
 * 空闲期（app 退到后台被缓存、用户只是翻页面）没有任何东西可诊断，却要一直写 flash。
 *
 * 所以加一道租约门控，默认就是「任务活跃期才跳」：app 侧在任务起止时调 [beginActive] /
 * [endActive]，守护进程每轮先看一眼租约（[ACTIVE_FILE_NAME]，只 stat 不写），有租约才写心跳；
 * 空闲期只轮询、不落盘。心跳文件因此多一个 `mode=` 字段，取证侧看到 `mode=idle` 就知道
 * mtime 是「主动停跳」而不是死亡上界（见 [Evidence.idle]）。
 *
 * 门控本身必须**失败时偏向取证**：su 域 stat 不到 app 私有目录时 `File.isFile()` 只会返回
 * false（不抛异常），若当成「租约不存在 → 空闲」，心跳会永久停摆且毫无痕迹。所以探测走
 * [Os.stat] 拿 errno（见 [LeaseState]），只有 ENOENT 才算真空闲，权限被拒一律按活跃处理，
 * 并把结论写进 `lease=` 字段。
 */
object RootDeathHeartbeat {
    const val FILE_NAME = "daemon.heartbeat"

    /** 上一实例被轮转出来的心跳（无遗言死亡时才产生，见 [rotateStale]）。 */
    const val PREV_FILE_NAME = "daemon.heartbeat.prev"

    /** 心跳周期。放宽到 3s 是为了少写 flash；死亡时间因此有 ±3s 的不确定度。 */
    const val BEAT_INTERVAL_MS = 3_000L

    /** 主线程 comm（内核上限 15 字符，超出会被截断）。 */
    const val COMM_NAME = "xayah-rootd"

    /**
     * 证据窗口允许回溯的最大跨度。
     *
     * 心跳揭示的死亡区间是 `[beat_ms, death_wall]`，若两者相距很远（守护进程死了很久才被
     * 发现），把整个区间当窗口会吸入大量无关 tombstone；超过这个上限就退回默认窄窗口，
     * 只在报告头里记录 `daemon_silence_ms` 供人判读。
     */
    const val MAX_EVIDENCE_SPAN_MS = 5L * 60 * 1000

    /**
     * app 侧续租的「任务活跃期租约」文件名。
     *
     * 放在 `cacheDir` 而不是 `root_death`：那里的属主一定是 app（创建文件不需要目录可写），
     * 而守护进程是 root，读这里没有权限问题；系统清缓存把它删掉也不致命，续租会在
     * [ACTIVE_RENEW_INTERVAL_MS] 内重建。
     */
    const val ACTIVE_FILE_NAME = "daemon.heartbeat.active"

    /**
     * 租约有效期。
     *
     * app 每 [ACTIVE_RENEW_INTERVAL_MS] 续一次，这里留 3 次余量：续租线程被冻结或任务进程
     * 意外死掉时，最多 [ACTIVE_LEASE_MS] 就自动回落到「空闲不写盘」，不会永久丢掉心跳。
     */
    const val ACTIVE_LEASE_MS = 60_000L

    /** app 侧续租周期。 */
    const val ACTIVE_RENEW_INTERVAL_MS = 20_000L

    /** 空闲期的租约轮询周期：只 stat 一个文件，不产生任何写盘。 */
    private const val IDLE_POLL_MS = 1_000L

    /** 心跳文件 `mode=` 取值：任务活跃期，正常每 [BEAT_INTERVAL_MS] 一跳。 */
    const val MODE_ACTIVE = "active"

    /** 心跳文件 `mode=` 取值：空闲期心跳主动停摆，mtime 不再代表死亡时刻的上界。 */
    const val MODE_IDLE = "idle"

    /**
     * 本轮探测到的租约状态，写进心跳文件的 `lease=` 字段。
     *
     * 存在的理由：`File.isFile()` 在 EACCES 时也**返回 false 而不是抛异常**，于是
     * 「su 域 stat 不到 app 私有目录」会被当成「租约不存在 → 空闲」，心跳永久停摆且不留
     * 任何痕迹。所以探测必须把「不存在」与「看不见」分开（见 [probeLease]），并把结论
     * 留在文件里供人判读。
     */
    enum class LeaseState(val wire: String) {
        /** 有租约且在 [ACTIVE_LEASE_MS] 内：正常跳。 */
        ACTIVE("active"),

        /** 文件在但已过期（app 被杀 / 续租线程被冻结）：按空闲处理。 */
        EXPIRED("expired"),

        /** 确认不存在（ENOENT）：任务真的没在跑，按空闲处理。 */
        ABSENT("absent"),

        /** stat 被拒（EACCES/EPERM/其他 errno）：看不见 ≠ 不存在，按活跃处理。 */
        DENIED("denied"),
    }

    private const val ACTIVE_LEASE_BODY = "active"
    private const val MAX_REASON_CHARS = 160
    private const val TAG_THREAD = "root-death-heartbeat"
    private const val TAG_LEASE = "root-death-lease"

    @Volatile
    private var startedMs = 0L

    @Volatile
    private var beatMs = 0L

    @Volatile
    private var stopReason: String? = null

    @Volatile
    private var beatThread: Thread? = null

    /** 守护进程自己的命令行（`/proc/self/cmdline`），取证侧据此确认 pidfile 里的 pid 是不是我们。 */
    @Volatile
    private var cmdline: String = ""

    /** 改过之后实际生效的 comm（SELinux 拒绝时为空，取证侧据此知道该字段不可用）。 */
    @Volatile
    private var comm: String = ""

    /** 当前写入模式（[MODE_ACTIVE] / [MODE_IDLE]），由心跳线程维护，[noteStop] 也带它。 */
    @Volatile
    private var mode: String = MODE_IDLE

    /** 最近一轮探测到的租约状态：停跳时也要写进 `lease=`，解释「为什么停」。 */
    @Volatile
    private var leaseState: LeaseState = LeaseState.ABSENT

    /** app 侧的活跃任务计数：备份/恢复/基准可能嵌套，只有归零才算真正空闲。 */
    private val activeCount = AtomicInteger(0)

    @Volatile
    private var leaseExecutor: ScheduledExecutorService? = null

    fun file(context: Context): File = File(RootDeathLogStore.dir(context), FILE_NAME)

    /**
     * 上一个「没说再见就消失」的实例留下的心跳。
     *
     * 存在的理由：libsu 在发现守护进程死掉后往往**立刻**重新拉起一个新实例，新实例的
     * [start] 会把主心跳文件整份覆写掉（新 pid、新时间），于是「A 死了、B 活着」在报告里
     * 会退化成「守护进程还活着，只是 binder 断了」。所以启动时把没有遗言的那份挪到 `.prev`，
     * 取证时给「那个已经不在 /proc 里的 pid」一次被选中的机会。
     */
    fun previousFile(context: Context): File = File(RootDeathLogStore.dir(context), PREV_FILE_NAME)

    /** 把主心跳文件挪成 `.prev`：rename 保留 mtime，也就是上一实例的真实死亡时刻。 */
    private fun rotateStale(target: File) = runCatching {
        if (!target.isFile) return@runCatching
        val hadGoodbye = target.readLines().any { it.startsWith("stop=") && it.length > 5 }
        if (hadGoodbye) return@runCatching
        val previous = File(target.parentFile, PREV_FILE_NAME)
        previous.delete()
        target.renameTo(previous)
    }

    /**
     * 启动心跳。只在守护进程里调用，幂等。
     *
     * 任何失败都不抛：心跳是取证辅助，不能因为它影响 root 服务本身。
     */
    fun start(context: Context) {
        if (beatThread != null) return
        val appContext = context.applicationContext ?: context
        val target = file(appContext)
        // 上一位住户没留遗言就走人：把它的心跳留成 .prev，否则下面第一行 write 会把它抹掉。
        rotateStale(target)
        val pid = Process.myPid()
        startedMs = System.currentTimeMillis()
        beatMs = startedMs
        cmdline = readCmdline()
        comm = renameComm()
        mode = if (isActive(appContext)) MODE_ACTIVE else MODE_IDLE
        runCatching { write(target, pid, startedMs, beatMs, null, cmdline, comm, mode, leaseState.wire) }

        beatThread = Thread({
            while (true) {
                if (isActive(appContext)) {
                    runCatching { Thread.sleep(BEAT_INTERVAL_MS) }
                    runCatching {
                        beatMs = System.currentTimeMillis()
                        mode = MODE_ACTIVE
                        write(target, pid, startedMs, beatMs, stopReason, cmdline, comm, mode, leaseState.wire)
                    }
                } else {
                    // 空闲：只轮询租约，不落盘。从活跃转空闲时补最后一跳并标成 idle，
                    // 这样取证侧看到 mode=idle 就不会把它的 mtime 当成死亡上界。
                    if (mode == MODE_ACTIVE) {
                        mode = MODE_IDLE
                        runCatching {
                            write(target, pid, startedMs, beatMs, stopReason, cmdline, comm, mode, leaseState.wire)
                        }
                    }
                    runCatching { Thread.sleep(IDLE_POLL_MS) }
                }
            }
        }, TAG_THREAD).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * 任务活跃期租约文件（app 写、守护进程读）。
     *
     * 放 `cacheDir`：那里的属主一定是 app，创建文件不需要目录按位放开；守护进程是 root，
     * 读它没有权限问题。系统清缓存时它会被删，续租线程 [ACTIVE_RENEW_INTERVAL_MS] 内重建。
     */
    fun activeFile(context: Context): File =
        File((context.applicationContext ?: context).cacheDir, ACTIVE_FILE_NAME)

    /**
     * app 侧：任务开始，开始维持活跃租约。
     *
     * 引用计数，允许备份/恢复/基准嵌套调用；只有第一个任务真正下发租约并起续租线程。
     * 任何失败都不抛——掉一次续租最多让心跳提前回到空闲，不影响任务本身。
     */
    fun beginActive(context: Context) {
        val appContext = context.applicationContext ?: context
        if (activeCount.incrementAndGet() != 1) return
        touchLease(appContext)
        runCatching {
            val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, TAG_LEASE).apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
            executor.scheduleWithFixedDelay(
                { touchLease(appContext) },
                ACTIVE_RENEW_INTERVAL_MS,
                ACTIVE_RENEW_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
            leaseExecutor = executor
        }
    }

    /** app 侧：任务结束，计数归零后立刻删租约，守护进程下一轮就不再写心跳。 */
    fun endActive(context: Context) {
        val appContext = context.applicationContext ?: context
        if (activeCount.decrementAndGet() > 0) return
        activeCount.set(0)
        leaseExecutor?.let { runCatching { it.shutdownNow() } }
        leaseExecutor = null
        runCatching { activeFile(appContext).delete() }
    }

    /**
     * 守护进程侧：当前是否有长任务在跑。
     *
     * **「看不见」不等于「不存在」**：部分 ROM 的 SELinux 策略下 su 域 stat 不到 app 私有
     * 目录，此时 `File.isFile()` 返回 false 而不是抛异常。如果按「不存在 → 空闲」处理，
     * 心跳会在这些机器上永久停摆且不留任何痕迹——比优化前更糟。所以探测走 [Os.stat]
     * 拿具体 errno：只有 ENOENT 才算真空闲，权限被拒或未知 errno 一律按活跃处理，
     * 最坏情况退回「一直跳」（优化前的行为），而不是把死亡取证静默关掉。
     */
    private fun isActive(context: Context): Boolean {
        val state = probeLease(activeFile(context))
        leaseState = state
        return state == LeaseState.ACTIVE || state == LeaseState.DENIED
    }

    /** 用 errno 区分「文件不存在」与「看不见文件」，见 [isActive]。 */
    private fun probeLease(lease: File): LeaseState = try {
        val stat = Os.stat(lease.absolutePath)
        // stat 的时间单位是秒，租约有效期是分钟级，精度足够。
        if (System.currentTimeMillis() - stat.st_mtime * 1000L <= ACTIVE_LEASE_MS) {
            LeaseState.ACTIVE
        } else {
            LeaseState.EXPIRED
        }
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.ENOENT) LeaseState.ABSENT else LeaseState.DENIED
    } catch (e: Throwable) {
        // 连 syscall 都调不通：宁可多写盘，也不要把取证关掉。
        LeaseState.DENIED
    }

    /** 续租：优先只改 mtime（元数据写，不动数据块），失败才退回整份重写。 */
    private fun touchLease(context: Context) = runCatching {
        val lease = activeFile(context)
        if (lease.isFile && lease.setLastModified(System.currentTimeMillis())) return@runCatching
        lease.parentFile?.let { if (!it.exists()) it.mkdirs() }
        lease.writeText(ACTIVE_LEASE_BODY)
    }

    /** `/proc/self/cmdline` 是 NUL 分隔的完整 argv，换成单行便于写进 key=value 文件。 */
    private fun readCmdline(): String = runCatching {
        File("/proc/self/cmdline").readText().replace('\u0000', ' ').trim()
    }.getOrElse { "" }

    /**
     * 把主线程 comm 改成 [COMM_NAME]，返回实际生效值（失败返回空串）。
     *
     * 这是 `prctl(PR_SET_NAME)` 的免 JNI 等价物：`/proc/self/task/<tid>/comm` 对进程自己是可写的。
     * 失败不抛——部分 ROM 的 SELinux 会拒绝写 proc，此时退回 cmdline 匹配（本来就够用）。
     */
    private fun renameComm(): String = runCatching {
        val path = "/proc/self/task/${Process.myTid()}/comm"
        File(path).writeText(COMM_NAME)
        File(path).readText().trim()
    }.getOrElse { "" }

    /**
     * 受控退出前的遗言。
     *
     * 只覆盖「我们自己决定死」的路径（`forceStopSelf` / `onDestroy`）：先落盘再杀进程，
     * 因此取证时若看到 `stop=` 就等于拿到了明确死因，不必再靠证据推断。
     */
    fun noteStop(context: Context, reason: String) {
        if (startedMs == 0L) return
        val appContext = context.applicationContext ?: context
        val clean = reason.replace('\n', ' ').take(MAX_REASON_CHARS)
        stopReason = clean
        runCatching {
            write(file(appContext), Process.myPid(), startedMs, beatMs, clean, cmdline, comm, mode, leaseState.wire)
        }
    }

    /** app 侧读取遗言文件，不存在或损坏时返回 null（调用方必须把它和「没有心跳」区分开）。 */
    fun read(context: Context): Evidence? = parse(file(context))

    /** 读上一个实例被轮转出来的心跳；没有则 null。 */
    fun readPrevious(context: Context): Evidence? = parse(previousFile(context))

    private fun parse(target: File): Evidence? {
        if (!target.isFile) return null
        val values = runCatching { target.readLines() }.getOrNull() ?: return null
        val map = values.mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
        }.toMap()
        val pid = map["pid"]?.toIntOrNull() ?: return null
        val started = map["started_ms"]?.toLongOrNull() ?: 0L
        val beat = map["beat_ms"]?.toLongOrNull() ?: 0L
        // mtime 是**内核**写下的时间戳：即使内容写得慢一拍也不会漏，且进程被 SIGKILL
        // 时它停在最后一次成功写入的时刻，正好是死亡时刻的上界。
        val mtime = runCatching { target.lastModified() }.getOrDefault(0L)
        return Evidence(
            pid = pid,
            startedMs = started,
            beatMs = beat,
            stop = map["stop"]?.takeIf { it.isNotBlank() },
            cmdline = map["cmdline"].orEmpty(),
            comm = map["comm"].orEmpty(),
            mode = map["mode"].orEmpty(),
            lease = map["lease"].orEmpty(),
            mtimeMs = mtime,
        )
    }

    data class Evidence(
        val pid: Int,
        val startedMs: Long,
        val beatMs: Long,
        /** 受控退出原因，null 表示没有留下遗言（即不是我们主动杀的）。 */
        val stop: String?,
        /** 守护进程启动时的命令行，用来核对 `/proc` 里同 pid 的进程还是不是它。 */
        val cmdline: String = "",
        /** 实际生效的 comm（空串表示改写被拒）。 */
        val comm: String = "",
        /** 最后一跳的模式：[MODE_ACTIVE] / [MODE_IDLE]，空串代表旧版本没有这个字段。 */
        val mode: String = "",
        /**
         * 最后一跳看到的租约状态（见 [LeaseState]），空串代表旧版本没有这个字段。
         *
         * [LeaseState.DENIED] 说明这一跳是「stat 被拒所以退回一直跳」的结果，
         * 也就是门控在这台机器上没生效——判读时别把它当成任务还在跑。
         */
        val lease: String = "",
        /** 心跳文件 mtime，即「最后一次写成功」的内核时间戳。 */
        val mtimeMs: Long = 0L,
    ) {
        /**
         * 这一跳是不是「空闲期主动停摆」。
         *
         * 心跳只在任务活跃期跳，所以空闲时守护进程特意不写——这时 mtime 只是「心跳停下的
         * 时刻」，真实死亡可能晚得多。取证侧必须据此放弃把 [lastAliveMs] 当成死亡上界。
         */
        val idle: Boolean get() = mode == MODE_IDLE

        /**
         * 死亡时刻的上界：内容里的 `beat_ms` 与文件 mtime 取较大者。
         *
         * 正常情况下两者相等；取 max 是为了容忍时钟跳变（内容写的是 wall clock，
         * mtime 由内核按同一时钟记，但极少数 ROM 上有偏差）。
         */
        val lastAliveMs: Long get() = maxOf(beatMs, mtimeMs)
    }

    private fun write(
        target: File,
        pid: Int,
        started: Long,
        beat: Long,
        stop: String?,
        cmdline: String,
        comm: String,
        mode: String,
        lease: String,
    ) = runCatching {
        val dir = target.parentFile ?: return@runCatching
        if (!dir.exists()) dir.mkdirs()
        // 该目录被两个 uid 写（app 写报告与击杀日志、root 守护进程写心跳），
        // root 先建目录时默认 0755，app 侧之后连报告都建不出来，所以每次都放宽。
        runCatching {
            dir.setReadable(true, false)
            dir.setWritable(true, false)
            dir.setExecutable(true, false)
        }
        val body = buildString {
            appendLine("pid=$pid")
            appendLine("started_ms=$started")
            appendLine("beat_ms=$beat")
            appendLine("mode=$mode")
            // 门控的依据：active/expired/absent/denied。denied = 这台机器上 stat 被拒，
            // 心跳退回「一直跳」，报告据此解释为什么没有省下写盘。
            appendLine("lease=$lease")
            if (stop != null) appendLine("stop=$stop")
            if (cmdline.isNotBlank()) appendLine("cmdline=${cmdline.replace('\n', ' ')}")
            if (comm.isNotBlank()) appendLine("comm=$comm")
        }
        // 先写临时文件再 rename：rename 在同一目录下是原子的，读侧不会读到半行。
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(body)
        RootDeathLogStore.loosen(tmp)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        RootDeathLogStore.loosen(target)
    }
}
