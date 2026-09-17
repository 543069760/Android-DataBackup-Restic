package com.xayah.core.network.client

import android.content.Context
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.database.S3Protocol
import com.xayah.core.network.R
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.withMainContext
import com.xayah.libpickyou.PickYouLauncher
import com.xayah.libpickyou.parcelables.DirChildrenParcelable
import com.xayah.libpickyou.parcelables.FileParcelable
import com.xayah.libpickyou.ui.model.PickerType
import kotlinx.coroutines.runBlocking

class S3ClientImpl(
    private val entity: CloudEntity,
    private val extra: S3Extra,
    private val rootService: RemoteRootService,
) : CloudClient {

    companion object {
        /** opendal scheme;S3(COS) 固定为 "cos"。将来 awss3 走 "s3"。 */
        private const val SCHEME = "cos"
    }

    private fun log(msg: () -> String): String = run {
        LogUtil.log { "S3ClientImpl" to msg() }
        msg()
    }

    private fun normalizeObjectKey(path: String): String {
        return path.trim('/').replace("//", "/")
    }

    /**
     * 构建 opendal cos options。
     * root 固定为 "/",当前浏览目录由 path 参数表达(与备份时 root=remotePath 语义不同)。
     * endpoint 格式 protocol://endpoint(去尾斜杠、不含 bucket),
     * 与 ResticShared.buildOpenDALEndpoint 一致,但在 network 层本地实现以避免依赖 restic 模块。
     */
    private fun buildOpendalOptions(): Map<String, String> = mapOf(
        "bucket" to extra.bucket,
        "root" to "/",
        "endpoint" to buildOpendalEndpoint(),
        "secret_id" to extra.accessKeyId,
        "secret_key" to extra.secretAccessKey,
    )

    private fun buildOpendalEndpoint(): String {
        val protocol = when (extra.protocol) {
            S3Protocol.HTTP -> "http"
            S3Protocol.HTTPS -> "https"
        }
        return "$protocol://${extra.endpoint.trim().removeSuffix("/")}"
    }

    // opendal 无长连接概念,connect/disconnect 保留为空实现以满足接口。
    override fun connect() {}

    override fun disconnect() {}

    override fun mkdir(dst: String) {
        val path = normalizeObjectKey(dst)
        log { "mkdir(opendal): $path" }
        runBlocking {
            rootService.opendalCreateDir(SCHEME, path, buildOpendalOptions())
        }
    }

    override fun mkdirRecursively(dst: String) {
        mkdir(dst)
    }

    override fun renameTo(
        src: String,
        dst: String,
        onProgress: ((currentPart: Int, totalParts: Int, currentFile: Int, totalFiles: Int) -> Unit)?
    ) {
        // 备份完全基于 rustic 时间戳,S3 不再需要 renameTo。
        log { "renameTo is deprecated for S3, skipping: $src to $dst" }
    }

    override fun upload(
        src: String,
        dst: String,
        onUploading: (read: Long, total: Long) -> Unit,
        isCanceled: (() -> Boolean)?
    ) {
        // 备份/配置上传已统一走 rustic 快照链路,S3ClientImpl 不再提供上传能力。
        throw UnsupportedOperationException("S3 upload is handled by rustic; direct upload is no longer supported.")
    }

    override fun download(src: String, dst: String, onDownloading: (written: Long, total: Long) -> Unit) {
        // 恢复已统一走 rustic 快照链路,S3ClientImpl 不再提供下载能力。
        throw UnsupportedOperationException("S3 download is handled by rustic; direct download is no longer supported.")
    }

    override fun deleteFile(src: String) {
        throw UnsupportedOperationException("S3 deleteFile is handled by rustic; not supported here.")
    }

    override fun removeDirectory(src: String): Boolean {
        throw UnsupportedOperationException("S3 removeDirectory is handled by rustic; not supported here.")
    }

    override fun deleteRecursively(src: String): Boolean {
        throw UnsupportedOperationException("S3 deleteRecursively is handled by rustic; not supported here.")
    }

    override fun clearEmptyDirectoriesRecursively(src: String) {
        // 对象存储没有真正的空目录概念,无需处理。
    }

    override fun listFiles(src: String): DirChildrenParcelable {
        log { "listFiles(opendal): $src" }
        val files = mutableListOf<FileParcelable>()
        val directories = mutableListOf<FileParcelable>()

        val path = if (src.isEmpty()) "/" else normalizeObjectKey(src)
        val raw = runBlocking {
            rootService.opendalList(SCHEME, path, buildOpendalOptions())
        }

        // 解析格式(与 jni_bridge.rs nativeOpendalList 约定一致):
        //   目录: d:<name>
        //   文件: f:<name>:<mtimeEpoch>   (name 可能含 ':',用最后一个 ':' 之后作为 mtime)
        //   条目以 '\n' 分隔
        raw.lineSequence().forEach { line ->
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("d:") -> {
                    val name = line.removePrefix("d:").removeSuffix("/")
                    if (name.isNotEmpty()) directories.add(FileParcelable(name, 0))
                }

                line.startsWith("f:") -> {
                    val rest = line.removePrefix("f:")
                    val sep = rest.lastIndexOf(':')
                    val name = if (sep >= 0) rest.substring(0, sep) else rest
                    val mtime = if (sep >= 0) rest.substring(sep + 1).toLongOrNull() ?: 0L else 0L
                    if (name.isNotEmpty()) files.add(FileParcelable(name, mtime))
                }
            }
        }

        files.sortBy { it.name }
        directories.sortBy { it.name }
        return DirChildrenParcelable(files = files, directories = directories)
    }

    override fun walkFileTree(path: String): List<PathParcelable> {
        // 递归遍历仅用于旧的 AWS 下载/reload 流程,已统一到 rustic,不再支持。
        throw UnsupportedOperationException("S3 walkFileTree is handled by rustic; not supported here.")
    }

    override fun exists(src: String): Boolean {
        throw UnsupportedOperationException("S3 exists is handled by rustic; not supported here.")
    }

    override fun size(src: String): Long {
        throw UnsupportedOperationException("S3 size is handled by rustic; not supported here.")
    }

    override suspend fun testConnection() {
        // 能成功列举根路径即视为连通(凭据/endpoint 可用)。失败时 opendalList 抛异常上抛。
        log { "testConnection(opendal): scheme=$SCHEME endpoint=${buildOpendalEndpoint()} bucket=${extra.bucket}" }
        rootService.opendalList(SCHEME, "/", buildOpendalOptions())
    }

    override suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit) {
        val currentExtra = entity.getExtraEntity<S3Extra>()!!
        val prefix = "${context.getString(R.string.cloud)}:"
        val pickYou = PickYouLauncher(
            checkPermission = false,
            traverseBackend = { listFiles(it.replaceFirst(prefix, "")) },
            mkdirsBackend = { parent, child ->
                runCatching {
                    val path = "$parent/$child".replaceFirst(prefix, "").trim('/')
                    mkdirRecursively(path)
                }.isSuccess
            },
            title = context.getString(R.string.select_target_directory),
            pickerType = PickerType.DIRECTORY,
            rootPathList = listOf(prefix),
            defaultPathList = listOf(prefix),
        )
        withMainContext {
            val pathString = pickYou.awaitLaunch(context)
            val remotePath = pathString.replaceFirst(prefix, "").trim('/')
            onSet(remotePath, GsonUtil().toJson(currentExtra))
        }
    }
}