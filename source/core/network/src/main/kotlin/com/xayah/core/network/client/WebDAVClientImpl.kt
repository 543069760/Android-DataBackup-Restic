package com.xayah.core.network.client

import android.content.Context
import com.xayah.core.common.util.toPathString
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.WebDAVExtra
import com.xayah.core.model.database.WebDAVProtocol
import com.xayah.core.network.R
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.toPathList
import com.xayah.core.util.withMainContext
import com.xayah.libpickyou.PickYouLauncher
import com.xayah.libpickyou.parcelables.DirChildrenParcelable
import com.xayah.libpickyou.parcelables.FileParcelable
import com.xayah.libpickyou.ui.model.PickerType
import kotlinx.coroutines.runBlocking

class WebDAVClientImpl(
    private val entity: CloudEntity,
    private val extra: WebDAVExtra,
    private val rootService: RemoteRootService,
) : CloudClient {

    companion object {
        /** opendal scheme;WebDAV 固定为 "webdav"。 */
        private const val SCHEME = "webdav"
    }

    private fun log(msg: () -> String): String = run {
        LogUtil.log { "WebDAVClientImpl" to msg() }
        msg()
    }

    private fun normalizePath(path: String): String {
        return path.trim('/').replace("//", "/")
    }

    /**
     * 构建 opendal webdav options。
     * root 固定为 "/",当前浏览目录由 path 参数表达(与建库时 root=remotePath 语义不同)。
     * endpoint 即 entity.host(完整 URL,含 http(s)://),trim + 去尾斜杠,
     * 与 ResticShared.buildOpenDALWebdavEndpoint 一致,但在 network 层本地实现以避免依赖 restic 模块。
     * 注意:opendal webdav 不支持跳过 TLS 校验,故不传 insecure 相关 key。
     */
    private fun buildOpendalOptions(): Map<String, String> = mapOf(
        "endpoint" to entity.host.trim().removeSuffix("/"),
        "root" to "/",
        "username" to entity.user,
        "password" to entity.pass,
    )

    // opendal 无长连接概念,connect/disconnect 保留为空实现以满足接口。
    override fun connect() {}

    override fun disconnect() {}

    override fun mkdir(dst: String) {
        val path = normalizePath(dst)
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
        // 备份已统一走 rustic 快照链路,WebDAV 不再需要 renameTo。
        log { "renameTo is deprecated for WebDAV, skipping: $src to $dst" }
    }

    override fun upload(
        src: String,
        dst: String,
        onUploading: (read: Long, total: Long) -> Unit,
        isCanceled: (() -> Boolean)?
    ) {
        // 备份/配置上传已统一走 rustic 快照链路,WebDAVClientImpl 不再提供上传能力。
        throw UnsupportedOperationException("WebDAV upload is handled by rustic; direct upload is no longer supported.")
    }

    override fun download(src: String, dst: String, onDownloading: (written: Long, total: Long) -> Unit) {
        // 恢复已统一走 rustic 快照链路,WebDAVClientImpl 不再提供下载能力。
        throw UnsupportedOperationException("WebDAV download is handled by rustic; direct download is no longer supported.")
    }

    override fun deleteFile(src: String) {
        throw UnsupportedOperationException("WebDAV deleteFile is handled by rustic; not supported here.")
    }

    override fun removeDirectory(src: String): Boolean {
        throw UnsupportedOperationException("WebDAV removeDirectory is handled by rustic; not supported here.")
    }

    override fun deleteRecursively(src: String): Boolean {
        throw UnsupportedOperationException("WebDAV deleteRecursively is handled by rustic; not supported here.")
    }

    override fun clearEmptyDirectoriesRecursively(src: String) {
        // 目录浏览不再维护空目录清理,无需处理。
    }

    override fun listFiles(src: String): DirChildrenParcelable {
        log { "listFiles(opendal): $src" }
        val files = mutableListOf<FileParcelable>()
        val directories = mutableListOf<FileParcelable>()

        val path = if (src.isEmpty()) "/" else normalizePath(src)
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
        // 递归遍历仅用于旧的下载/reload 流程,已统一到 rustic,不再支持。
        throw UnsupportedOperationException("WebDAV walkFileTree is handled by rustic; not supported here.")
    }

    override fun exists(src: String): Boolean {
        throw UnsupportedOperationException("WebDAV exists is handled by rustic; not supported here.")
    }

    override fun size(src: String): Long {
        throw UnsupportedOperationException("WebDAV size is handled by rustic; not supported here.")
    }

    override suspend fun testConnection() {
        // 能成功列举根路径即视为连通(凭据/endpoint 可用)。失败时 opendalList 抛异常上抛。
        log { "testConnection(opendal): scheme=$SCHEME endpoint=${entity.host}" }
        rootService.opendalList(SCHEME, "/", buildOpendalOptions())
    }

    private fun handleOriginalPath(path: String): String = run {
        val pathSplit = path.toPathList().toMutableList()
        // Remove "$Cloud:"
        pathSplit.removeFirstOrNull()
        pathSplit.toPathString()
    }

    override suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit) {
        val extra = entity.getExtraEntity<WebDAVExtra>()!!
        // 兜底：旧账户 JSON 缺 protocol/resticPassword 时 Gson 会填 null，
        // 直接 toJson 写回会把显式 null 持久化，导致 restic 层 kotlinx decodeFromString 抛异常。
        val safeExtra = extra.copy(
            protocol = extra.protocol ?: WebDAVProtocol.HTTPS,
            resticPassword = extra.resticPassword.orEmpty(),
        )
        val prefix = "${context.getString(R.string.cloud)}:"
        val pickYou = PickYouLauncher(
            checkPermission = false,
            traverseBackend = { listFiles(it.replaceFirst(prefix, "")) },
            mkdirsBackend = { parent, child ->
                runCatching { mkdirRecursively(handleOriginalPath("$parent/$child")) }.isSuccess
            },
            title = context.getString(R.string.select_target_directory),
            pickerType = PickerType.DIRECTORY,
            rootPathList = listOf(prefix),
            defaultPathList = listOf(prefix),
        )
        withMainContext {
            val pathString = pickYou.awaitLaunch(context)
            onSet(handleOriginalPath(pathString), GsonUtil().toJson(safeExtra))
        }
    }
}