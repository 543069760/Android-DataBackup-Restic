package com.xayah.core.network.client

import android.content.Context
import com.xayah.core.common.util.toPathString
import com.xayah.core.model.SFTPAuthMode
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.SFTPExtra
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
 * SFTP 目录浏览客户端 —— 迁移到 librclone（rclone RC RPC）。
 *
 * 不再使用 sshj（SSHClient/SFTPClient），所有远端操作经
 * rootService.rcloneRpc(method, input) 在 root 进程的 librclone 中执行：
 *   - 列目录: operations/list   {"fs": "<connstr>:", "remote": "<dir>"}
 *   - 建目录: operations/mkdir  {"fs": "<connstr>:", "remote": "<dir>"}（天然 mkdir -p）
 *   - 密码混淆: core/obscure    {"clear": "<pass>"} -> {"obscured": "..."}
 *
 * connection string 拼法与 ResticRepositorySftp/RcloneServe.buildSftpConnectionString
 * 一致（含 disable_hashcheck/shell_type=none/set_modtime=false/chunk_size=255k），
 * 区别是这里 root 段留空，目标目录通过 remote 参数逐次传入。
 */
class SFTPClientImpl(
    private val entity: CloudEntity,
    private val extra: SFTPExtra,
    private val rootService: RemoteRootService,
) : CloudClient {

    private fun log(msg: () -> String): String = run {
        LogUtil.log { "SFTPClientImpl" to msg() }
        msg()
    }

    private fun normalizePath(path: String): String = path.trim('/').replace("//", "/")

    /**
     * core/obscure 混淆明文密码，取返回 JSON 的 obscured 字段。
     * 与 RcloneServe.obscure 一致。
     */
    private suspend fun obscure(plain: String): String {
        val out = rootService.rcloneRpc(
            "core/obscure", JSONObject().put("clear", plain).toString()
        )
        return JSONObject(out).getString("obscured")
    }

    /**
     * 拼 SFTP rclone connection string 的参数区（末尾保留 ':'，root 段留空）：
     *   :sftp,host='...',port=NN,user='...',pass='<obscured>'[,或 key_pem='...'],
     *   disable_hashcheck=true,shell_type=none,set_modtime=false,chunk_size=255k:
     *
     * 参数取值与 RcloneServe.buildSftpConnectionString 完全对齐。
     * SFTP 的 remote 为相对路径时表示用户 home 目录（与 sshj 旧实现 "." 语义一致）。
     */
    private suspend fun buildSftpFs(): String {
        val host = entity.host.trim()
            .removePrefix("sftp://")
            .removeSuffix("/")

        val sb = StringBuilder(":sftp,")
        sb.append("host='").append(host).append("',")
        sb.append("port=").append(extra.port).append(",")
        sb.append("user='").append(entity.user).append("',")
        when (extra.mode) {
            SFTPAuthMode.PASSWORD -> {
                sb.append("pass='").append(obscure(entity.pass)).append("'")
            }

            SFTPAuthMode.PUBLIC_KEY -> {
                // key_pem 内联 PEM，换行符转义为字面 \n（与 RcloneServe 一致）
                val pem = extra.privateKey.replace("\n", "\\n")
                sb.append("key_pem='").append(pem).append("'")
            }
        }
        sb.append(",disable_hashcheck=true")
        sb.append(",shell_type=none")
        sb.append(",set_modtime=false")
        sb.append(",chunk_size=255k")
        sb.append(":")
        return sb.toString()
    }

    private fun rcloneList(remote: String): JSONObject {
        val out = runBlocking {
            rootService.rcloneRpc(
                "operations/list",
                JSONObject()
                    .put("fs", buildSftpFs())
                    .put("remote", remote)
                    .toString()
            )
        }
        return JSONObject(out)
    }

    private fun rcloneMkdir(remote: String) {
        runBlocking {
            rootService.rcloneRpc(
                "operations/mkdir",
                JSONObject()
                    .put("fs", buildSftpFs())
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
        // 备份已统一走 rustic 快照链路，SFTP 不再需要 renameTo。
        log { "renameTo is deprecated for SFTP, skipping: $src to $dst" }
    }

    override fun upload(
        src: String,
        dst: String,
        onUploading: (read: Long, total: Long) -> Unit,
        isCanceled: (() -> Boolean)?
    ) {
        // 备份/配置上传已统一走 rustic 快照链路，SFTPClientImpl 不再提供上传能力。
        throw UnsupportedOperationException("SFTP upload is handled by rustic; direct upload is no longer supported.")
    }

    override fun download(src: String, dst: String, onDownloading: (written: Long, total: Long) -> Unit) {
        // 恢复已统一走 rustic 快照链路，SFTPClientImpl 不再提供下载能力。
        throw UnsupportedOperationException("SFTP download is handled by rustic; direct download is no longer supported.")
    }

    override fun deleteFile(src: String) {
        throw UnsupportedOperationException("SFTP deleteFile is handled by rustic; not supported here.")
    }

    override fun removeDirectory(src: String): Boolean {
        throw UnsupportedOperationException("SFTP removeDirectory is handled by rustic; not supported here.")
    }

    override fun clearEmptyDirectoriesRecursively(src: String) {
        // 目录浏览不再维护空目录清理，无需处理。
    }

    override fun deleteRecursively(src: String): Boolean {
        throw UnsupportedOperationException("SFTP deleteRecursively is handled by rustic; not supported here.")
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
        throw UnsupportedOperationException("SFTP walkFileTree is handled by rustic; not supported here.")
    }

    override fun exists(src: String): Boolean {
        throw UnsupportedOperationException("SFTP exists is handled by rustic; not supported here.")
    }

    override fun size(src: String): Long {
        throw UnsupportedOperationException("SFTP size is handled by rustic; not supported here.")
    }

    override suspend fun testConnection() {
        // 能成功列举 home 目录即视为连通（host/port/凭据可用）。失败时 rcloneRpc 抛异常上抛。
        log { "testConnection(rclone): host=${entity.host} port=${extra.port} user=${entity.user} mode=${extra.mode}" }
        rootService.rcloneRpc(
            "operations/list",
            JSONObject()
                .put("fs", buildSftpFs())
                .put("remote", ".")
                .toString()
        )
    }

    private fun handleOriginalPath(path: String): String = run {
        val pathSplit = path.toPathList().toMutableList()
        // Remove "$Cloud:"
        pathSplit.removeFirstOrNull()
        // Add "."
        pathSplit.add(0, ".")
        pathSplit.toPathString()
    }

    override suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit) {
        val extra = entity.getExtraEntity<SFTPExtra>()!!
        val prefix = "${context.getString(R.string.cloud)}:"
        val pickYou = PickYouLauncher(
            checkPermission = false,
            // 保持原 sshj 实现的语义：prefix 替换为 "."，即默认从 home 目录开始浏览。
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