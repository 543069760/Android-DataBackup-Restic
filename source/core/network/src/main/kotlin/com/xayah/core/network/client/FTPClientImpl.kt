package com.xayah.core.network.client

import android.content.Context
import com.xayah.core.common.util.toPathString
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.FTPExtra
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
import org.json.JSONObject

/**
 * FTP 目录浏览客户端 —— 迁移到 librclone（rclone RC RPC）。
 *
 * 不再使用 Apache Commons Net 的 FTPClient，所有远端操作经
 * rootService.rcloneRpc(method, input) 在 root 进程的 librclone 中执行：
 *   - 列目录: operations/list   {"fs": "<connstr>:", "remote": "<dir>"}
 *   - 建目录: operations/mkdir  {"fs": "<connstr>:", "remote": "<dir>"}（天然 mkdir -p）
 *   - 密码混淆: core/obscure    {"clear": "<pass>"} -> {"obscured": "..."}
 *
 * connection string 拼法与 ResticRepositoryFtp/RcloneServe.buildFtpConnectionString
 * 一致，区别是这里 root 段留空，目标目录通过 remote 参数逐次传入。
 */
class FTPClientImpl(
    private val entity: CloudEntity,
    private val extra: FTPExtra,
    private val rootService: RemoteRootService,
) : CloudClient {

    private fun log(msg: () -> String): String = run {
        LogUtil.log { "FTPClientImpl" to msg() }
        msg()
    }

    private fun normalizePath(path: String): String = path.trim('/').replace("//", "/")

    /**
     * 拼 FTP rclone connection string 的参数区（末尾保留 ':'，root 段留空）：
     *   :ftp,host='...',port=NN,user='...',pass='<obscured>',
     *   concurrency=8,idle_timeout=5s,close_timeout=5s:
     *
     * 参数取值与 RcloneServe.buildFtpConnectionString 对齐
     * （concurrency=8 / idle_timeout=5s / close_timeout=5s，抗连接堆积/421）。
     */
    private suspend fun buildFtpFs(): String {
        val host = entity.host.trim()
            .removePrefix("ftp://")
            .removePrefix("ftps://")
            .removeSuffix("/")
        // 密码必须经 core/obscure 混淆后才能进 connection string
        val obscured = JSONObject(
            rootService.rcloneRpc(
                "core/obscure",
                JSONObject().put("clear", entity.pass).toString()
            )
        ).getString("obscured")
        return StringBuilder(":ftp,")
            .append("host='").append(host).append("',")
            .append("port=").append(extra.port).append(",")
            .append("user='").append(entity.user).append("',")
            .append("pass='").append(obscured).append("'")
            .append(",concurrency=8")
            .append(",idle_timeout=5s")
            .append(",close_timeout=5s")
            .append(":")
            .toString()
    }

    private fun rcloneList(remote: String): JSONObject {
        val out = runBlocking {
            val fs = buildFtpFs()
            rootService.rcloneRpc(
                "operations/list",
                JSONObject()
                    .put("fs", fs)
                    .put("remote", remote)
                    .toString()
            )
        }
        return JSONObject(out)
    }

    private fun rcloneMkdir(remote: String) {
        runBlocking {
            val fs = buildFtpFs()
            rootService.rcloneRpc(
                "operations/mkdir",
                JSONObject()
                    .put("fs", fs)
                    .put("remote", remote)
                    .toString()
            )
        }
    }

    // librclone 无长连接概念，connect/disconnect 保留为空实现以满足接口。
    override fun connect() {}

    override fun disconnect() {}

    override fun mkdir(dst: String) {
        val remote = normalizePath(dst)
        log { "mkdir(rclone): $remote" }
        rcloneMkdir(remote)
    }

    override fun mkdirRecursively(dst: String) {
        // rclone operations/mkdir 即 mkdir -p 语义，天然递归
        mkdir(dst)
    }

    override fun renameTo(
        src: String,
        dst: String,
        onProgress: ((currentPart: Int, totalParts: Int, currentFile: Int, totalFiles: Int) -> Unit)?
    ) {
        // 备份已统一走 rustic 快照链路，FTP 不再需要 renameTo。
        log { "renameTo is deprecated for FTP, skipping: $src to $dst" }
    }

    override fun upload(
        src: String,
        dst: String,
        onUploading: (read: Long, total: Long) -> Unit,
        isCanceled: (() -> Boolean)?
    ) {
        // 备份/配置上传已统一走 rustic 快照链路，FTPClientImpl 不再提供上传能力。
        throw UnsupportedOperationException("FTP upload is handled by rustic; direct upload is no longer supported.")
    }

    override fun download(src: String, dst: String, onDownloading: (written: Long, total: Long) -> Unit) {
        // 恢复已统一走 rustic 快照链路，FTPClientImpl 不再提供下载能力。
        throw UnsupportedOperationException("FTP download is handled by rustic; direct download is no longer supported.")
    }

    override fun deleteFile(src: String) {
        throw UnsupportedOperationException("FTP deleteFile is handled by rustic; not supported here.")
    }

    override fun removeDirectory(src: String): Boolean {
        throw UnsupportedOperationException("FTP removeDirectory is handled by rustic; not supported here.")
    }

    override fun clearEmptyDirectoriesRecursively(src: String) {
        // 目录浏览不再维护空目录清理，无需处理。
    }

    override fun deleteRecursively(src: String): Boolean {
        throw UnsupportedOperationException("FTP deleteRecursively is handled by rustic; not supported here.")
    }

    override fun listFiles(src: String): DirChildrenParcelable {
        log { "listFiles(rclone): $src" }
        val files = mutableListOf<FileParcelable>()
        val directories = mutableListOf<FileParcelable>()

        val remote = normalizePath(src)
        val json = rcloneList(remote)

        // operations/list 返回 {"list":[{"Path","Name","Size","MimeType","ModTime","IsDir"}, ...]}
        val arr = json.optJSONArray("list")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val name = item.optString("Name").takeIf { it.isNotEmpty() } ?: continue
                // ModTime 为 RFC3339（如 2024-01-01T00:00:00Z），解析失败给 0
                val mtime = runCatching {
                    java.time.Instant.parse(item.optString("ModTime")).toEpochMilli()
                }.getOrDefault(0L)
                val parcelable = FileParcelable(name, mtime)
                if (item.optBoolean("IsDir")) directories.add(parcelable)
                else files.add(parcelable)
            }
        }

        files.sortBy { it.name }
        directories.sortBy { it.name }
        return DirChildrenParcelable(files = files, directories = directories)
    }

    override fun walkFileTree(src: String): List<PathParcelable> {
        // 递归遍历仅用于旧的下载/reload 流程，已统一到 rustic，不再支持。
        throw UnsupportedOperationException("FTP walkFileTree is handled by rustic; not supported here.")
    }

    override fun exists(src: String): Boolean {
        throw UnsupportedOperationException("FTP exists is handled by rustic; not supported here.")
    }

    override fun size(src: String): Long {
        throw UnsupportedOperationException("FTP size is handled by rustic; not supported here.")
    }

    override suspend fun testConnection() {
        // 能成功列举根路径即视为连通（host/port/user/pass 可用）。失败时 rcloneRpc 抛异常上抛。
        log { "testConnection(rclone): host=${entity.host} port=${extra.port} user=${entity.user}" }
        rootService.rcloneRpc(
            "operations/list",
            JSONObject()
                .put("fs", buildFtpFs())
                .put("remote", "")
                .toString()
        )
    }

    private fun handleOriginalPath(path: String): String = run {
        val pathSplit = path.toPathList().toMutableList()
        // Remove "$Cloud:"
        pathSplit.removeFirstOrNull()
        pathSplit.toPathString()
    }

    override suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit) {
        val extra = entity.getExtraEntity<FTPExtra>()!!
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
            onSet(handleOriginalPath(pathString), GsonUtil().toJson(extra))
        }
    }
}