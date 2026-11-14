package com.xayah.core.rootservice.util

import android.content.Context
import android.os.Process
import java.io.File

/**
 * 击杀日志：**谁决定让守护进程死**。
 *
 * SIGKILL 拦不住是硬约束，所以死亡取证必须拆成两件事——
 * 1. 「死于何时」交给 [RootDeathHeartbeat] 的心跳停摆（文件 mtime ≈ 真实死亡时刻）；
 * 2. 「死于谁手」交给这份日志：凡是**我们自己**决定杀守护进程的路径，都在动手**之前**
 *    追加一行带时间戳的记录。二者对齐（心跳停摆时刻 ±15s 内有记录）即可定性为自杀，
 *    两者都无、且 `/proc` 里也没有该进程，才是外部 SIGKILL。
 *
 * 三条已知的 kill 路径：
 * - app 侧 [com.xayah.core.rootservice.service.RemoteRootService.destroyService] 的
 *   `killDaemon = true`（取消兜底的硬杀）；
 * - app 侧 `orNullReply`：AIDL 返回 null 时重载 APK 顺带杀守护进程；
 * - 守护进程侧 `forceStopSelf`：协作式取消失败后自己杀自己。
 *
 * 为什么必须与主日志分开：主日志由 LogUtil 管理、会随「清理日志」被一起删掉，而这份是
 * 只增不删的旁证（超过上限只裁掉最旧的行）。文件落在 `filesDir/root_death/kill_journal.txt`，
 * 与死亡报告同目录，app 进程与守护进程同 UID 都能写，用户清缓存也带不走。
 */
object KillJournal {
    const val FILE_NAME = "kill_journal.txt"

    /** 文件上限：超出后只保留最近 [MAX_LINES] 行，避免长年累月把 filesDir 撑大。 */
    private const val MAX_BYTES = 96 * 1024
    private const val MAX_LINES = 400
    private const val MAX_CALLER_CHARS = 80
    private const val MAX_DETAIL_CHARS = 160

    /** `key=value` 字段的起点（value 可能含空格，所以只能靠下一个 `key=` 的位置分界）。 */
    private val FIELD = Regex("""(?:^|\s)([A-Za-z_]+)=""")

    fun file(context: Context): File = File(RootDeathLogStore.dir(context), FILE_NAME)

    /**
     * 记录一次「即将杀掉守护进程」。
     *
     * 必须**在动手之前**调用：`killProcess` / `stopOrTask` 之后本进程可能已经不在了。
     * 任何异常都吞掉——取证辅助绝不能影响取消流程本身。
     */
    fun record(context: Context, caller: String, killDaemon: Boolean, detail: String? = null) {
        val appContext = context.applicationContext ?: context
        runCatching {
            val target = file(appContext)
            RootDeathLogStore.prepareDir(appContext)
            val line = buildString {
                append("ts_ms=").append(System.currentTimeMillis())
                append(" pid=").append(Process.myPid())
                append(" uid=").append(Process.myUid())
                append(" caller=").append(caller.replace('\n', ' ').take(MAX_CALLER_CHARS))
                append(" killDaemon=").append(killDaemon)
                if (!detail.isNullOrBlank()) {
                    append(" detail=").append(detail.replace('\n', ' ').take(MAX_DETAIL_CHARS))
                }
                append('\n')
            }
            // 单次 write 走 O_APPEND：app 与守护进程同时写也不会互相覆盖，
            // 最坏情况只是两行交错，解析侧按行独立处理。
            target.appendText(line)
            RootDeathLogStore.loosen(target)
            if (target.length() > MAX_BYTES) trim(target)
        }
    }

    /** 全部记录，按写入顺序（时间升序）。读失败返回空列表。 */
    fun read(context: Context): List<Entry> {
        val target = file(context)
        if (!target.isFile) return emptyList()
        return runCatching {
            target.readLines().mapNotNull { parse(it) }
        }.getOrElse { emptyList() }
    }

    /**
     * 取与 [centerMs] 相距不超过 [toleranceMs] 的记录。
     *
     * 上一次会话留下的记录因为时间对不上窗口，天然被排除——这正是用时间窗而不是
     * 「文件里有没有记录」来判定的原因。
     */
    fun near(context: Context, centerMs: Long, toleranceMs: Long): List<Entry> =
        read(context).filter { kotlin.math.abs(it.tsMs - centerMs) <= toleranceMs }

    data class Entry(
        val tsMs: Long,
        val pid: Int,
        val caller: String,
        val killDaemon: Boolean,
        val raw: String,
    )

    private fun parse(line: String): Entry? {
        if (line.isBlank()) return null
        val fields = fields(line)
        val ts = fields["ts_ms"]?.toLongOrNull() ?: return null
        return Entry(
            tsMs = ts,
            pid = fields["pid"]?.toIntOrNull() ?: 0,
            caller = fields["caller"] ?: "?",
            killDaemon = fields["killDaemon"]?.toBooleanStrictOrNull() ?: false,
            raw = line,
        )
    }

    private fun fields(line: String): Map<String, String> {
        val matches = FIELD.findAll(line).toList()
        if (matches.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        matches.forEachIndexed { index, match ->
            val start = match.range.last + 1
            val next = matches.getOrNull(index + 1)
            val end = if (next != null) next.range.first else line.length
            if (start <= end) map[match.groupValues[1]] = line.substring(start, end).trim()
        }
        return map
    }

    /** 只保留最后 [MAX_LINES] 行；tmp + rename 保证读侧不会读到半截文件。 */
    private fun trim(target: File) = runCatching {
        val lines = target.readLines().takeLast(MAX_LINES)
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(lines.joinToString("\n", postfix = "\n"))
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        RootDeathLogStore.loosen(target)
    }
}
