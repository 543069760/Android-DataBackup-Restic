package com.xayah.core.restic

import android.util.Log
import com.xayah.core.model.CloudType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.FTPExtra
import com.xayah.core.model.database.SFTPExtra
import com.xayah.core.model.SFTPAuthMode
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

@Singleton
class RcloneServe @Inject constructor(
    private val shared: ResticShared,
) {
    /** 一次 serve 会话：restUrl 交给 rustic JNI，id 用于 serve/stop。 */
    data class Session(val restUrl: String, val id: String)

    /**
     * 缓存条目：一个真实存活的 serve 会话 + 引用计数 + 空闲关闭定时器。
     * refCount>0 表示正被某次 JNI 操作使用；归零后启动 idleJob，到点仍空闲才真停。
     */
    private class Entry(
        val key: String,
        val session: Session,
        var refCount: Int,
        var idleJob: Job? = null,
    )

    /** 保护 cache 与各 Entry 的 refCount/idleJob 的并发访问。 */
    private val mutex = Mutex()

    /** key = cloudEntity.name + "::" + remotePath。 */
    private val cache = HashMap<String, Entry>()

    /** 承载空闲关闭定时器的独立作用域，不随任何一次任务协程取消。 */
    private val serveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun cacheKey(cloudEntity: CloudEntity, remotePath: String): String =
        "${cloudEntity.name}::${remotePath}"

    /**
     * 语义 = acquire：
     *  - 命中缓存 → 取消待关闭定时器、refCount++、复用同一 Session（省掉 ~3s serve/start）。
     *  - 未命中   → 真起一个 serve（doStart），refCount=1 入缓存。
     * 保持原签名不变，上层 startServe 无感。
     */
    suspend fun start(cloudEntity: CloudEntity, remotePath: String): Session {
        val key = cacheKey(cloudEntity, remotePath)
        mutex.withLock {
            val existing = cache[key]
            if (existing != null) {
                existing.idleJob?.cancel()
                existing.idleJob = null
                existing.refCount++
                Log.i(ResticShared.TAG, "serve 复用 key=$key id=${existing.session.id} refCount=${existing.refCount} -> ${existing.session.restUrl}")
                return existing.session
            }
            // 未命中：在锁内真起 serve（doStart 内含 obscure/serve-start RPC）。
            // 不同 key 的首启会被串行化，但本 App 备份/恢复本身是顺序执行，影响可忽略。
            val session = doStart(cloudEntity, remotePath)
            cache[key] = Entry(key = key, session = session, refCount = 1)
            Log.i(ResticShared.TAG, "serve 新建 key=$key id=${session.id} refCount=1 -> ${session.restUrl}")
            return session
        }
    }

    /**
     * 语义 = release：refCount--，归零后不立即停，启动空闲 keep-alive 定时器；
     * 定时器到点若仍空闲（refCount<=0 且缓存里还是本条目）才真正 serve/stop。
     * 保持原签名不变，上层 stopServe(session.id) 无感。
     */
    suspend fun stop(id: String) {
        mutex.withLock {
            val entry = cache.values.firstOrNull { it.session.id == id }
            if (entry == null) {
                // 未在缓存（异常兜底）：直接停，避免泄漏。
                Log.w(ResticShared.TAG, "serve stop 未命中缓存，直接停 id=$id")
                doStop(id)
                return
            }
            entry.refCount--
            if (entry.refCount > 0) {
                Log.i(ResticShared.TAG, "serve 释放 key=${entry.key} id=$id refCount=${entry.refCount}（仍在用，不关闭）")
                return
            }
            entry.refCount = 0
            Log.i(ResticShared.TAG, "serve 空闲 key=${entry.key} id=$id，启动 ${IDLE_KEEP_ALIVE_MS}ms keep-alive")
            scheduleIdleClose(entry)
        }
    }

    /** 在 serveScope 里挂一个延时关闭；到点重新加锁复检，确保这期间没有被再次 acquire。 */
    private fun scheduleIdleClose(entry: Entry) {
        entry.idleJob = serveScope.launch {
            delay(IDLE_KEEP_ALIVE_MS)
            mutex.withLock {
                // 复检：期间被复用（refCount>0）或已被顶替则不关。
                if (entry.refCount <= 0 && cache[entry.key] === entry) {
                    cache.remove(entry.key)
                    Log.i(ResticShared.TAG, "serve 空闲超时关闭 key=${entry.key} id=${entry.session.id}")
                    doStop(entry.session.id)
                } else {
                    Log.i(ResticShared.TAG, "serve 空闲定时器到点但已被复用/顶替，跳过关闭 key=${entry.key}")
                }
            }
        }
    }

    /**
     * 兜底：强制关闭所有缓存的 serve（可在整个云任务彻底结束、或进程收尾时调用）。
     * 不改上层也能正常工作（空闲定时器会自动回收），此方法仅用于确定性清理。
     */
    suspend fun shutdownAll() {
        mutex.withLock {
            cache.values.forEach { entry ->
                entry.idleJob?.cancel()
                runCatching { doStop(entry.session.id) }
                Log.i(ResticShared.TAG, "serve shutdownAll 关闭 key=${entry.key} id=${entry.session.id}")
            }
            cache.clear()
        }
    }

    /** 真正起一个 serve restic（原 start 主体）：按 cloudEntity.type 分支拼连接串。 */
    private suspend fun doStart(cloudEntity: CloudEntity, remotePath: String): Session {
        val fs = when (cloudEntity.type) {
            CloudType.FTP -> {
                val extra = ResticShared.json.decodeFromString<FTPExtra>(cloudEntity.extra)
                buildFtpConnectionString(cloudEntity, extra, remotePath)
            }
            else -> {
                val extra = ResticShared.json.decodeFromString<SFTPExtra>(cloudEntity.extra)
                buildSftpConnectionString(cloudEntity, extra, remotePath)
            }
        }

        val input = JSONObject().apply {
            put("type", "restic")
            put("fs", fs)
            put("addr", "localhost:0")
        }.toString()

        val out = shared.rootService.rcloneRpc("serve/start", input)
        val json = JSONObject(out)
        val addr = json.getString("addr")          // 形如 "[::]:34567" 或 "127.0.0.1:34567"
        val id = json.getString("id")
        val port = addr.substringAfterLast(":").toInt()
        val restUrl = "rest:http://127.0.0.1:$port/"
        Log.i(ResticShared.TAG, "rclone serve restic started id=$id addr=$addr -> $restUrl")
        return Session(restUrl, id)
    }

    /** 真正 serve/stop（原 stop 主体）。 */
    private suspend fun doStop(id: String) {
        runCatching {
            shared.rootService.rcloneRpc("serve/stop", JSONObject().put("id", id).toString())
        }.onFailure { Log.e(ResticShared.TAG, "serve/stop 失败 id=$id", it) }
    }

    /** 每次备份开始前把 global accounting 归零，拿到干净基准。走无锁 RPC，避免被 createRusticSnapshot 的大锁挡住。 */
    suspend fun resetStats() {
        runCatching {
            shared.rootService.rcloneRpcNoLock("core/stats-reset", "{}")
        }.onFailure { Log.e(ResticShared.TAG, "core/stats-reset 失败", it) }
    }

    /** 读取 global accounting 的 bytes 与 speed（bytes/s）。走无锁 RPC，供备份进行期间轮询。 */
    suspend fun readStatsBytesAndSpeed(): Pair<Long, Long> = runCatching {
        val out = shared.rootService.rcloneRpcNoLock(
            "core/stats", JSONObject().put("short", true).toString()
        )
        val json = JSONObject(out)
        val bytes = json.optLong("bytes", 0L)
        val speed = json.optLong("speed", 0L)
        Pair(bytes, speed)
    }.getOrElse {
        Log.e(ResticShared.TAG, "core/stats 读取失败", it)
        0L to 0L
    }

    /** core/obscure 混淆明文密码，取返回 JSON 的 obscured 字段。 */
    private suspend fun obscure(plain: String): String {
        val out = shared.rootService.rcloneRpc(
            "core/obscure", JSONObject().put("clear", plain).toString()
        )
        return JSONObject(out).getString("obscured")
    }

    /**
     * 拼 rclone connection string：
     *   :sftp,host='...',port=NN,user='...',pass='<obscured>':<remotePath>
     * 密码模式用 obscured pass；密钥模式用 key_pem。
     */
    private suspend fun buildSftpConnectionString(
        cloudEntity: CloudEntity, extra: SFTPExtra, remotePath: String
    ): String {
        val host = cloudEntity.host.trim()
            .removePrefix("sftp://").removeSuffix("/")
        val user = cloudEntity.user
        val root = shared.formatSftpRoot(remotePath)

        val sb = StringBuilder(":sftp,")
        sb.append("host='").append(host).append("',")
        sb.append("port=").append(extra.port).append(",")
        sb.append("user='").append(user).append("',")
        when (extra.mode) {
            SFTPAuthMode.PASSWORD -> {
                val obscured = obscure(cloudEntity.pass)
                sb.append("pass='").append(obscured).append("'")
            }
            SFTPAuthMode.PUBLIC_KEY -> {
                val pem = extra.privateKey.replace("\n", "\\n")
                sb.append("key_pem='").append(pem).append("'")
            }
        }
        sb.append(",disable_hashcheck=true")
        sb.append(",shell_type=none")
        sb.append(",set_modtime=false")
        sb.append(",chunk_size=255k")
        sb.append(":").append(root)
        return sb.toString()
    }

    /**
     * 拼 FTP rclone connection string：
     *   :ftp,host='...',port=NN,user='...',pass='<obscured>',
     *   concurrency=4,idle_timeout=5s,close_timeout=5s:<root>
     *
     * concurrency  : 同一时刻并发数据连接上界，压低单 IP 连接数，避免撞服务器上限触发 421。
     * idle_timeout : 空闲连接多久后从池里回收（5s，短于常见 FTP 服务器 idle 上限，主动先于服务器踢连接）。
     * close_timeout: 关闭数据连接的等待上限，避免半开连接滞留服务器侧。
     * 追加位置必须在结尾 ':<root>' 之前——冒号是连接串参数区/路径区的分隔符。
     */
    private suspend fun buildFtpConnectionString(
        cloudEntity: CloudEntity, extra: FTPExtra, remotePath: String
    ): String {
        val host = cloudEntity.host.trim()
            .removePrefix("ftp://")
            .removePrefix("ftps://")
            .removeSuffix("/")
        val user = cloudEntity.user
        val root = shared.formatSftpRoot(remotePath)

        val sb = StringBuilder(":ftp,")
        sb.append("host='").append(host).append("',")
        sb.append("port=").append(extra.port).append(",")
        sb.append("user='").append(user).append("',")
        val obscured = obscure(cloudEntity.pass)
        sb.append("pass='").append(obscured).append("'")
        // 抗连接堆积/421：限制并发 + 快速回收空闲/半开连接。
        sb.append(",concurrency=").append(FTP_CONCURRENCY)
        sb.append(",idle_timeout=").append(FTP_IDLE_TIMEOUT)
        sb.append(",close_timeout=").append(FTP_CLOSE_TIMEOUT)
        // 追加位置必须在结尾 ':<root>' 之前——冒号是参数区/路径区分隔符。
        sb.append(":").append(root)
        Log.i(ResticShared.TAG, "buildFtpConnectionString host=$host port=${extra.port} user=$user concurrency=$FTP_CONCURRENCY idle_timeout=$FTP_IDLE_TIMEOUT close_timeout=$FTP_CLOSE_TIMEOUT root=$root")
        return sb.toString()
    }

    companion object {
        /** serve 空闲多久后真正关闭；覆盖同一 App 多数据类型之间的间隔即可。 */
        private const val IDLE_KEEP_ALIVE_MS = 5_000L
        /** FTP 同一时刻并发连接上界，压低单 IP 连接数，避免撞服务器上限触发 421。 */
        private const val FTP_CONCURRENCY = 8
        /** FTP 空闲连接回收时长（rclone fs.Duration，接受 "5s"；"0" 表示不留空闲连接）。 */
        private const val FTP_IDLE_TIMEOUT = "5s"
        /** FTP 关闭数据连接的等待上限。 */
        private const val FTP_CLOSE_TIMEOUT = "5s"
    }
}