package com.xayah.core.network.client

import android.content.Context
import com.xayah.core.model.database.AliyunOssExtra
import com.xayah.core.model.database.AliyunOssProtocol
import com.xayah.core.model.database.CloudEntity
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

class AliyunOssClientImpl(
    private val entity: CloudEntity,
    private val extra: AliyunOssExtra,
    private val rootService: RemoteRootService,
) : CloudClient {

    companion object {
        private const val SCHEME = "oss"
    }

    private fun log(msg: () -> String): String = run {
        LogUtil.log { "AliyunOssClientImpl" to msg() }
        msg()
    }

    private fun normalizeObjectKey(path: String): String {
        return path.trim('/').replace("//", "/")
    }

    private fun buildOpendalOptions(): Map<String, String> {
        return mapOf(
            "bucket" to extra.bucket,
            "root" to "/",
            "endpoint" to buildOpendalEndpoint(),
            "access_key_id" to extra.accessKeyId,
            "access_key_secret" to extra.accessKeySecret,
        )
    }

    private fun buildOpendalEndpoint(): String {
        val raw = extra.endpoint.trim().removeSuffix("/")
        if (raw.isEmpty()) return ""
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val protocol = when (extra.protocol) {
            AliyunOssProtocol.HTTP -> "http"
            AliyunOssProtocol.HTTPS -> "https"
        }
        return "$protocol://$raw"
    }

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
        log { "renameTo is deprecated for Aliyun OSS, skipping: $src to $dst" }
    }

    override fun upload(
        src: String,
        dst: String,
        onUploading: (read: Long, total: Long) -> Unit,
        isCanceled: (() -> Boolean)?
    ) {
        throw UnsupportedOperationException("Aliyun OSS upload is handled by rustic; direct upload is no longer supported.")
    }

    override fun download(src: String, dst: String, onDownloading: (written: Long, total: Long) -> Unit) {
        throw UnsupportedOperationException("Aliyun OSS download is handled by rustic; direct download is no longer supported.")
    }

    override fun deleteFile(src: String) {
        throw UnsupportedOperationException("Aliyun OSS deleteFile is handled by rustic; not supported here.")
    }

    override fun removeDirectory(src: String): Boolean {
        throw UnsupportedOperationException("Aliyun OSS removeDirectory is handled by rustic; not supported here.")
    }

    override fun deleteRecursively(src: String): Boolean {
        throw UnsupportedOperationException("Aliyun OSS deleteRecursively is handled by rustic; not supported here.")
    }

    override fun clearEmptyDirectoriesRecursively(src: String) {
    }

    override fun listFiles(src: String): DirChildrenParcelable {
        log { "listFiles(opendal): $src" }
        val files = mutableListOf<FileParcelable>()
        val directories = mutableListOf<FileParcelable>()

        val path = if (src.isEmpty()) "/" else normalizeObjectKey(src)
        val raw = runBlocking {
            rootService.opendalList(SCHEME, path, buildOpendalOptions())
        }

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
        throw UnsupportedOperationException("Aliyun OSS walkFileTree is handled by rustic; not supported here.")
    }

    override fun exists(src: String): Boolean {
        throw UnsupportedOperationException("Aliyun OSS exists is handled by rustic; not supported here.")
    }

    override fun size(src: String): Long {
        throw UnsupportedOperationException("Aliyun OSS size is handled by rustic; not supported here.")
    }

    override suspend fun testConnection() {
        log { "testConnection(opendal): scheme=$SCHEME endpoint=${buildOpendalEndpoint()} bucket=${extra.bucket}" }
        rootService.opendalList(SCHEME, "/", buildOpendalOptions())
    }

    override suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit) {
        val currentExtra = entity.getExtraEntity<AliyunOssExtra>()!!
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
