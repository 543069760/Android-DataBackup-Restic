package com.xayah.core.service.util

import android.content.Context
import com.xayah.core.model.util.formatToStorageSizePerSecond
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readCompressionLevel
import com.xayah.core.datastore.readFollowSymlinks
import com.xayah.core.model.DataType
import com.xayah.core.model.OperationState
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.util.getCompressPara
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.Tar
import com.xayah.core.util.model.ShellResult
import com.xayah.core.rootservice.ICallback
import org.json.JSONObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class MediumBackupUtil @Inject constructor(
    @ApplicationContext val context: Context,
    private val rootService: RemoteRootService,
    private val taskDao: TaskDao,
    private val mediaRepository: MediaRepository,
    private val commonBackupUtil: CommonBackupUtil,
    private val cloudRepository: CloudRepository,
) {
    companion object {
        private const val TAG = "MediumBackupUtil"
    }

    private fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { TAG to msg }
        msg
    }

    private fun MediaEntity.getDataBytes() = mediaInfo.dataBytes

    private fun MediaEntity.setDataBytes(sizeBytes: Long) = run { mediaInfo.dataBytes = sizeBytes }
    private fun MediaEntity.setDisplayBytes(sizeBytes: Long) =
        run { mediaInfo.displayBytes = sizeBytes }

    private fun TaskDetailMediaEntity.getLog() = mediaInfo.log

    private suspend fun TaskDetailMediaEntity.updateInfo(
        state: OperationState? = null,
        bytes: Long? = null,
        log: String? = null,
        content: String? = null,
    ) = run {
        mediaInfo.also {
            if (state != null) it.state = state
            if (bytes != null) it.bytes = bytes
            if (log != null) it.log = log
            if (content != null) it.content = content
        }
        taskDao.upsert(this)
    }

    /**
     * 准备 argv 的结果（与 PackagesBackupUtil.ArgvResult 等价，媒体版）：
     * - Ready：可流式打包，携带 tar 的 argv、源大小(用于进度/统计)、快照内逻辑归档名
     * - Skip ：源不存在(忽略) / 数据未变化，调用侧应跳过且不视为失败
     * - Error：源缺失(必备) 或 取路径失败，调用侧应记 ERROR
     */
    sealed interface ArgvResult {
        data class Ready(
            val argv: Array<String>,
            val sizeBytes: Long,
            val stdinFilename: String,
        ) : ArgvResult

        data object Skip : ArgvResult

        data class Error(val out: List<String>) : ArgvResult
    }

    /** fd 备份结果：shell 结果 + 快照元数据（供调用方入库/统计） */
    data class StreamBackupResult(
        val shell: ShellResult,
        val snapshotId: String? = null,
        val fedBytes: Long = -1L,
        val tarCode: Int = -1,
    )

    // JNI tar 的公共 argv 前缀（媒体文件在外部存储，无需 --xattrs/--acls/--selinux）。
    private val tarHeader = arrayOf("tar", "--totals")

    suspend fun backupMedia(
        m: MediaEntity,
        t: TaskDetailMediaEntity,
        r: MediaEntity?,
        dstDir: String,
        isCanceled: (() -> Boolean)? = null
    ): ShellResult = run {
        log { "Backing up ${DataType.MEDIA_MEDIA.type}..." }

        val name = m.name
        val ct = m.indexInfo.compressionType
        val dst = mediaRepository.getArchiveDst(dstDir = dstDir, ct = ct)
        var isSuccess = true
        val out = mutableListOf<String>()
        val src = m.path
        val srcDir = PathUtil.getParentPath(src)

        // Check the existence of origin path.
        rootService.exists(src).also {
            if (it.not()) {
                isSuccess = false
                out.add(log { "Not exist: $src" })
                t.updateInfo(state = OperationState.ERROR, log = out.toLineString())
                return@run ShellResult(code = -1, input = listOf(), out = out)
            }
        }

        val sizeBytes = rootService.calculateSize(src)
        t.updateInfo(state = OperationState.PROCESSING, bytes = sizeBytes)
        if (rootService.exists(dst) && sizeBytes == r?.getDataBytes()) {
            t.updateInfo(state = OperationState.SKIP)
            out.add(log { "Data has not changed." })
        } else {
            // Compress and test.
            Tar.compressToFile(
                cacheDir = context.cacheDir.path,
                callTar = { o, e, a -> rootService.callTarCli(o, e, a) },
                exclusionList = listOf(),
                h = if (context.readFollowSymlinks().first()) "-h" else "",
                srcDir = srcDir,
                src = PathUtil.getFileName(src),
                dst = dst,
            ).also { result ->
                isSuccess = result.isSuccess
                out.addAll(result.out)

                // 压缩完成后立即检查取消标志
                if (isCanceled?.invoke() == true) {
                    log { "Backup canceled after compression" }
                    isSuccess = false
                    out.add("Backup canceled by user")
                    return@run ShellResult(code = -1, input = listOf(), out = out)
                }
            }
        }

        t.updateInfo(
            state = if (isSuccess) OperationState.DONE else OperationState.ERROR,
            log = out.toLineString()
        )

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    /**
     * 生成媒体（文件）的流式 argv（喂给 rustic 的 stdin），不再落盘 media.tar。
     * JNI tar 无 shell：不用通配符，直接 -C <srcDir> -- <fileName>。
     *
     * 说明：fd 流直通没有落盘的目标 tar 文件，"数据未变化则 SKIP" 只能依据
     * 上一次已记录的源大小判定（r.getDataBytes()），不再检查 dst 是否存在。
     *
     * stdinFilename 用绝对归档路径 "$dstDir/media.tar"（与 config 快照同前缀），
     * 保证恢复列表分组键 prefixPath 一致、两条快照合并为同一 group、不再误报
     * "备份不完整"。basename 恒为 media.tar，恢复端 includePath="media.tar" 不受影响。
     */
    suspend fun prepareMediaArgv(
        m: MediaEntity,
        t: TaskDetailMediaEntity,
        r: MediaEntity?,
        dstDir: String,
        isCanceled: (() -> Boolean)? = null,
    ): ArgvResult = run {
        log { "Preparing ${DataType.MEDIA_MEDIA.type} argv..." }

        val out = mutableListOf<String>()
        val src = m.path
        val srcDir = PathUtil.getParentPath(src)
        val fileName = PathUtil.getFileName(src)

        // 取消快速退出
        if (isCanceled?.invoke() == true) {
            out.add(log { "Backup canceled before prepare" })
            t.updateInfo(state = OperationState.ERROR, log = out.toLineString())
            return@run ArgvResult.Error(out)
        }

        // 源存在性检查
        if (rootService.exists(src).not()) {
            out.add(log { "Not exist: $src" })
            t.updateInfo(state = OperationState.ERROR, log = out.toLineString())
            return@run ArgvResult.Error(out)
        }

        val sizeBytes = rootService.calculateSize(src)
        t.updateInfo(state = OperationState.PROCESSING, bytes = sizeBytes)

        // 已删除“数据未变化 → SKIP”体积去重：
        // app（packages）fd 直通链路不做体积去重，且 fd 模式无落盘 dst 可作守卫，
        // 仅凭 r.getDataBytes() 比对会在每次重复备份时误跳过。

        val argv = mutableListOf(*tarHeader)
        if (context.readFollowSymlinks().first()) argv.add("-h")
        argv.addAll(listOf("-cpf", "-", "-C", srcDir, "--", fileName))

        ArgvResult.Ready(
            argv = argv.toTypedArray(),
            sizeBytes = sizeBytes,
            stdinFilename = "$dstDir/${DataType.MEDIA_MEDIA.type}.tar",
        )
    }

    /**
     * fd 流式入库：ArgvResult.Ready → rootService.backupTarToRustic。
     * 成功返回 StreamBackupResult（snapshotId 供调用方 updateResticInfo 入库），
     * 失败透传为 ShellResult(code = -1)。完整性由 impl 侧四要素判定承担，本层只做
     * JSON 解析、进度回调兜底与 dataBytes/displayBytes 记账。
     */
    suspend fun streamBackupToRustic(
        ready: ArgvResult.Ready,
        m: MediaEntity,
        repositoryPath: String,
        password: String,
        tags: List<String>,
        options: Map<String, String> = emptyMap(),
        callback: ICallback? = null,
        onProgress: ((readBytes: Long, writtenSpeed: Long) -> Unit)? = null,
        cancelId: Long = 0L,
    ): StreamBackupResult = run {
        val out = mutableListOf<String>()
        log { "[FdBackup] streamBackupToRustic enter: media=${m.name} filename=${ready.stdinFilename} sizeBytes=${ready.sizeBytes} argv=${ready.argv.toList()}" }
        android.util.Log.i(
            "FdBackup",
            "tar -> rustic fd backup: media=${m.name} stdinFilename=${ready.stdinFilename} sizeBytes=${ready.sizeBytes} argv=${ready.argv.toList()}"
        )

        // Rust 侧 createSnapshotFromFd 对 callback.is_null() 早退，null 必失败 → 兜底最小实现。
        // 只打 logcat 不进 UI：read=已读字节(源口径)，written=写仓库字节，speed=写速。
        val effectiveCallback = object : ICallback.Stub() {
            override fun onProgress(
                readBytes: Long,
                readTotal: Long,
                readProgress: Float,
                writtenBytes: Long,
                writtenSpeed: Long,
            ) {
                android.util.Log.d(
                    "FdBackup",
                    "progress read=$readBytes/$readTotal written=$writtenBytes speed=${writtenSpeed.formatToStorageSizePerSecond()}"
                )
                onProgress?.invoke(readBytes, writtenSpeed)
                callback?.onProgress(readBytes, readTotal, readProgress, writtenBytes, writtenSpeed)
            }

            override fun onRestorePlan(
                filesTotal: Long,
                bytesTotal: Long,
                filesSkipped: Long,
                bytesSkipped: Long,
            ) {
                callback?.onRestorePlan(filesTotal, bytesTotal, filesSkipped, bytesSkipped)
            }
        }

        val startMs = System.currentTimeMillis()
        val jsonStr = try {
            rootService.backupTarToRustic(
                repositoryPath,
                password,
                ready.stdinFilename,
                ready.argv,
                tags,
                options,
                effectiveCallback,
                cancelId,
            )
        } catch (e: Exception) {
            // impl 侧 FATAL 已 forget 快照，此处只透传失败
            out.add(log { "[FdBackup] FATAL: ${e.message}" })
            android.util.Log.e("FdBackup", "backupTarToRustic failed: media=${m.name}", e)
            return@run StreamBackupResult(shell = ShellResult(code = -1, input = listOf(), out = out))
        }
        val elapsedMs = System.currentTimeMillis() - startMs

        val result = JSONObject(jsonStr)
        val snapshotId = result.optString("snapshotId", "")
        val fedBytes = result.optLong("fedBytes", -1L)
        val tarCode = result.optInt("tarCode", -1)
        val warnings = result.optInt("warnings", 0)

        val mbps = if (elapsedMs > 0) fedBytes * 1000.0 / 1024 / 1024 / elapsedMs else 0.0
        android.util.Log.i(
            "FdBackup",
            "stream done media=${m.name} filename=${ready.stdinFilename} snapshotId=$snapshotId fedBytes=$fedBytes tarCode=$tarCode warnings=$warnings elapsed=${elapsedMs}ms avg=${"%.1f".format(mbps)}MB/s"
        )
        out.add(log { "[FdBackup] snapshot=$snapshotId fedBytes=$fedBytes tarCode=$tarCode warnings=$warnings elapsed=${elapsedMs}ms avg=${"%.1f".format(mbps)}MB/s" })
        if (tarCode == 1 || warnings > 0) {
            out.add(log { "[FdBackup] WARNING: tar exit=$tarCode, $warnings volatile-file warnings (see FdBackup logcat in root process)" })
        }

        // 对齐旧链路语义：dataBytes=预估源大小（扫描结果），displayBytes=实际 tar 产出（fedBytes 对账值）
        m.setDataBytes(ready.sizeBytes)
        m.setDisplayBytes(fedBytes)

        StreamBackupResult(
            shell = ShellResult(code = 0, input = listOf(), out = out),
            snapshotId = snapshotId.takeIf { it.isNotEmpty() },
            fedBytes = fedBytes,
            tarCode = tarCode,
        )
    }

    suspend fun upload(
        client: CloudClient,
        m: MediaEntity,
        t: TaskDetailMediaEntity,
        srcDir: String,
        dstDir: String,
        isCanceled: (() -> Boolean)? = null  // 新增参数
    ) = run {
        val ct = m.indexInfo.compressionType
        val src = mediaRepository.getArchiveDst(dstDir = srcDir, ct = ct)
        t.updateInfo(state = OperationState.UPLOADING)

        var flag = true
        var progress = 0f
        var speed = 0L
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()

        with(CoroutineScope(coroutineContext)) {
            launch {
                while (flag) {
                    // 在进度更新循环中也检查取消标志
                    if (isCanceled?.invoke() == true) {
                        log { "Upload progress monitoring canceled" }
                        flag = false
                        break
                    }

                    val speedText = if (speed > 0) speed.formatToStorageSizePerSecond() else ""
                    val content = if (speedText.isNotEmpty()) {
                        "$speedText | ${(progress * 100).toInt()}%"
                    } else {
                        "${(progress * 100).toInt()}%"
                    }
                    t.updateInfo(content = content)
                    delay(500)
                }
            }
        }

        cloudRepository.upload(
            client = client,
            src = src,
            dstDir = dstDir,
            onUploading = { read, total ->
                progress = read.toFloat() / total
                val currentTime = System.currentTimeMillis()
                val timeDiff = currentTime - lastTime
                if (timeDiff >= 500) {
                    val bytesDiff = read - lastBytes
                    speed = if (timeDiff > 0) (bytesDiff * 1000 / timeDiff) else 0L
                    lastTime = currentTime
                    lastBytes = read
                }
            },
            isCanceled = isCanceled  // 传递取消检查
        ).apply {
            flag = false
            t.updateInfo(
                state = if (isSuccess) OperationState.DONE else OperationState.ERROR,
                log = t.getLog() + "\n${outString}",
                content = "100%"
            )
        }
    }
}
