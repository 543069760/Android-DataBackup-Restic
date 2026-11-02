package com.xayah.core.util.command

import android.os.Build
import com.xayah.core.util.LogUtil.log
import com.xayah.core.util.model.ShellResult

/**
 * 后台存活加固结果 / 现状。
 *
 * 之所以是三个状态而不是一个 Boolean：不同 ROM 的 `dumpsys deviceidle`、`am get-standby-bucket`
 * 行为差异很大（被裁剪、被 SELinux 拒绝、输出格式不同）。如果只返回 Boolean，把「查不出来」
 * 当成「没有加固」，这类设备会被永久判为失败、卡在初始化页无法继续。
 * 所以 [Unsupported] 一律按「通过」处理，只有 [Unoptimized] 才是明确的、可修复的未加固。
 */
sealed interface KeepAliveStatus {
    /** 已处于「系统尽量不限制后台」的状态。 */
    data object Optimized : KeepAliveStatus

    /** 明确查到没生效（Doze 白名单缺项，或待机桶被降级），可以尝试修复。 */
    data object Unoptimized : KeepAliveStatus

    /** 系统 / ROM 不支持这些查询，或输出无法解析 —— 无法判定，不应阻塞用户。 */
    data object Unsupported : KeepAliveStatus
}

/**
 * 后台存活加固（root 侧）。
 *
 * 与初始化页的「电池优化白名单」互补，白名单只解除 Doze 对 job / alarm / 网络的限制，
 * 但下面三件事它管不到，而它们都会让备份跑到一半被系统掐断（随后 root 守护进程 binder 死亡）：
 *
 * 1. 应用待机桶（App Standby Bucket）被降级到 FREQUENT / RARE / RESTRICTED 后，
 *    后台任务与前台服务的启动都会被系统压制。置为 active 即可长期保持高优先级。
 * 2. `RUN_ANY_IN_BACKGROUND` appop 被收紧时，进程会被直接冻结（部分 OEM 的「后台运行」开关就是改它）。
 * 3. Android 12+ 的 phantom process killer 会杀掉由 su 启动的 root 子进程（`:root:daemon`）。
 *
 * 全部操作幂等、失败仅记录日志，不阻塞调用方。
 *
 * 诊断：所有命令都走 [exec]（log = true），因此在 logcat 里可用
 * `adb logcat -s KeepAlive` 看结论、`adb logcat -d -v time | grep -E "SHELL_(IN|OUT|CODE)"`
 * 看每条命令的原始输入 / 输出 / 退出码；同样的内容也会落到 files/log/log_*.txt。
 */
object KeepAlive {
    private const val TAG = "KeepAlive"

    /** 备份前置加固的场景标识，仅用于把日志拆成应用备份 / 文件备份两路，便于 logcat 过滤。 */
    const val SCENE_PACKAGES = "packages"
    const val SCENE_MEDIA = "media"

    // am 返回的待机桶取值：5 EXEMPTED / 10 ACTIVE / 20 WORKING_SET / 30 FREQUENT / 40 RARE / 45 RESTRICTED
    private const val BUCKET_EXEMPTED = 5
    private const val BUCKET_ACTIVE = 10
    private const val BUCKET_WORKING_SET = 20

    /** dumpsys 的白名单行形如 `system-excidle,com.foo,10234`，某些 ROM 只输出包名，故按分隔符切词精确比对。 */
    private val WHITELIST_SEPARATOR = Regex("[,:;=\\s]+")

    /** Android 10 起 op 名从 RUN_IN_BACKGROUND 改为 RUN_ANY_IN_BACKGROUND。 */
    private val backgroundOp: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "RUN_ANY_IN_BACKGROUND" else "RUN_IN_BACKGROUND"

    /** LogUtil 需要先 initialize（root 校验时已完成），这里再兜一层，避免独立调用时抛异常淹没真实结果。 */
    private fun logQuietly(msg: String) = runCatching { log { TAG to msg } }

    /** 统一出口：打开 BaseUtil 的命令日志（SHELL_IN / SHELL_OUT / SHELL_CODE），异常也转成 ShellResult。 */
    private suspend fun exec(vararg args: String): ShellResult = runCatching {
        BaseUtil.execute(*args, log = true)
    }.getOrElse { e ->
        val detail = "${e.javaClass.simpleName}: ${e.message}"
        logQuietly("exec(${args.joinToString(" ")}) threw $detail")
        ShellResult(code = -1, input = args.toList(), out = listOf(detail))
    }

    suspend fun readStandbyBucket(packageName: String): Int? = runCatching {
        val result = exec("am", "get-standby-bucket", packageName)
        val value = result.outString.trim().toIntOrNull()
        if (value == null) {
            logQuietly("readStandbyBucket($packageName): unparsable, code=${result.code}, out=${result.outString.trim().take(160)}")
        }
        value
    }.getOrNull()

    suspend fun setStandbyBucketActive(packageName: String): Boolean = runCatching {
        val result = exec("am", "set-standby-bucket", packageName, "active")
        if (result.isSuccess.not()) {
            logQuietly("set-standby-bucket($packageName) failed: code=${result.code}, out=${result.outString.trim().take(160)}")
        }
        result.isSuccess
    }.getOrDefault(false)

    suspend fun readBackgroundRunAllowed(packageName: String): Boolean? = runCatching {
        val result = exec("appops", "get", packageName, backgroundOp)
        val out = result.outString.lowercase()
        val allowed = when {
            out.contains("$backgroundOp: allow".lowercase()) -> true
            out.contains("$backgroundOp: ignore".lowercase()) -> false
            out.contains("$backgroundOp: deny".lowercase()) -> false
            out.contains("$backgroundOp: default".lowercase()) -> false
            else -> null
        }
        if (allowed == null) {
            logQuietly("readBackgroundRunAllowed($packageName): unrecognized, code=${result.code}, out=${result.outString.trim().take(160)}")
        }
        allowed
    }.getOrNull()

    suspend fun allowBackgroundRun(packageName: String): Boolean = runCatching {
        val result = exec("appops", "set", packageName, backgroundOp, "allow")
        if (result.isSuccess.not()) {
            logQuietly("appops set($packageName, $backgroundOp, allow) failed: code=${result.code}, out=${result.outString.trim().take(160)}")
        }
        result.isSuccess
    }.getOrDefault(false)

    /**
     * 是否在 Doze 白名单里。
     *
     * @return true 已在白名单；false 命令成功但列表里确实没有；null 命令失败或输出不可解析（无法判定）。
     */
    suspend fun readDozeWhitelist(packageName: String): Boolean? = runCatching {
        val result = exec("dumpsys", "deviceidle", "whitelist")
        if (result.isSuccess.not()) return@runCatching null
        val lines = result.outString.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) {
            logQuietly("readDozeWhitelist($packageName): empty dump, code=${result.code}")
            return@runCatching null
        }
        val whitelisted = lines.any { line -> line.split(WHITELIST_SEPARATOR).any { it == packageName } }
        if (whitelisted.not()) {
            // 未命中时把原始输出的头部打出来，便于确认 ROM 的格式 / 是否被 Permission Denial 之类顶掉。
            logQuietly("readDozeWhitelist($packageName)=false, lines=${lines.size}, head=${lines.take(6).joinToString(" | ").take(320)}")
        }
        whitelisted
    }.getOrNull()

    /** `dumpsys deviceidle whitelist +pkg` 即使失败也返回 0，因此加完再回读确认；失败时换 `cmd deviceidle` 再试一次。 */
    suspend fun addDozeWhitelist(packageName: String): Boolean = runCatching {
        exec("dumpsys", "deviceidle", "whitelist", "+$packageName")
        if (readDozeWhitelist(packageName) == true) return@runCatching true

        logQuietly("addDozeWhitelist($packageName): dumpsys had no effect, retry with cmd")
        exec("cmd", "deviceidle", "whitelist", "+$packageName")
        readDozeWhitelist(packageName) == true
    }.getOrDefault(false)

    /** Android 12+ 的 phantom process killer 开关；null 表示该系统版本没有该项。 */
    suspend fun readPhantomProcessMonitor(): Boolean? = runCatching {
        val result = exec("settings", "get", "global", "settings_enable_monitor_phantom_procs")
        when (val out = result.outString.trim().lowercase()) {
            "null", "" -> true // 未设置时系统默认开启
            "0", "false" -> false
            "1", "true" -> true
            else -> out.toIntOrNull()?.let { it != 0 }.also {
                if (it == null) logQuietly("readPhantomProcessMonitor: unrecognized, code=${result.code}, out=${out.take(80)}")
            }
        }
    }.getOrNull()

    /**
     * 未加固判定：读不到的那一项不参与判定，避免 ROM 差异把用户锁在初始化页。
     * 单一真相源，[query] / [diagnose] 共用，防止两套判定逻辑漂移。
     */
    private fun evaluate(doze: Boolean?, bucket: Int?): KeepAliveStatus {
        if (doze == null && bucket == null) return KeepAliveStatus.Unsupported
        val bucketOk = bucket == null || bucket == BUCKET_EXEMPTED || bucket == BUCKET_ACTIVE || bucket == BUCKET_WORKING_SET
        return if (bucketOk && doze != false) KeepAliveStatus.Optimized else KeepAliveStatus.Unoptimized
    }

    /** 只读查询当前状态，不做任何修改。 */
    suspend fun query(packageName: String): KeepAliveStatus {
        val doze = readDozeWhitelist(packageName)
        val bucket = readStandbyBucket(packageName)
        if (doze == null && bucket == null) {
            logQuietly("query($packageName)=Unsupported, state unreadable")
            return KeepAliveStatus.Unsupported
        }

        val status = evaluate(doze, bucket)
        logQuietly("query($packageName)=$status (doze=$doze, standbyBucket=$bucket)")
        return status
    }

    /**
     * 读取一次完整快照并打一行日志（白名单 / 待机桶 / 后台 appop / phantom 进程监控），
     * 供备份前置诊断使用。只读，不做修改。
     */
    private suspend fun diagnose(packageName: String, scene: String, stage: String): KeepAliveStatus {
        val doze = readDozeWhitelist(packageName)
        val bucket = readStandbyBucket(packageName)
        val backgroundRun = readBackgroundRunAllowed(packageName)
        val phantom = readPhantomProcessMonitor()
        val status = evaluate(doze, bucket)
        logQuietly(
            "backup[$scene] $stage: status=$status, dozeWhitelist=$doze, standbyBucket=$bucket, " +
                "backgroundRun=$backgroundRun, phantomMonitor=$phantom"
        )
        return status
    }

    /**
     * 备份前置加固（service 层在仓库可用性校验通过后调用）。
     *
     * 与首页的 [query] 不同：这里检测到未加固会直接修复 —— 备份跑到一半被系统掐断是无法回滚的损失，
     * 而此时 root 必然已就绪，修复成本接近于零。
     *
     * 全程不抛异常、也不返回「失败」：加固只是尽力而为，不能变成备份的新失败点。
     * 诊断：`adb logcat -s KeepAlive | grep "backup\\["` 能看到修复前后的完整快照。
     *
     * @param scene [SCENE_PACKAGES] / [SCENE_MEDIA]，只用于日志区分。
     */
    suspend fun ensureBeforeBackup(packageName: String, scene: String): KeepAliveStatus = runCatching {
        val before = diagnose(packageName, scene, "before")
        if (before != KeepAliveStatus.Unoptimized) {
            logQuietly("backup[$scene] keep-alive OK before backup, no action needed")
            return@runCatching before
        }

        apply(packageName)
        val after = diagnose(packageName, scene, "after")
        if (after == KeepAliveStatus.Unoptimized) {
            logQuietly("backup[$scene] still Unoptimized after apply, backup continues anyway")
        } else {
            logQuietly("backup[$scene] keep-alive hardened before backup")
        }
        after
    }.getOrElse { e ->
        logQuietly("backup[$scene] keep-alive check threw ${e.javaClass.simpleName}: ${e.message}")
        KeepAliveStatus.Unsupported
    }

    /**
     * 幂等加固：Doze 白名单 + 待机桶置 active + 放开后台运行 appop。
     *
     * @return 加固后的实测状态；[KeepAliveStatus.Unsupported] 表示这台设备不适用，调用方应按通过处理。
     */
    suspend fun apply(packageName: String): KeepAliveStatus {
        val bucketBefore = readStandbyBucket(packageName)
        val dozeBefore = readDozeWhitelist(packageName)
        if (bucketBefore == null && dozeBefore == null) {
            logQuietly("apply($packageName)=Unsupported, state unreadable")
            return KeepAliveStatus.Unsupported
        }

        if (dozeBefore != true) addDozeWhitelist(packageName)
        setStandbyBucketActive(packageName)
        allowBackgroundRun(packageName)
        readPhantomProcessMonitor()

        val status = query(packageName)
        logQuietly("apply($packageName)=$status (dozeBefore=$dozeBefore, bucketBefore=$bucketBefore)")
        return status
    }
}
