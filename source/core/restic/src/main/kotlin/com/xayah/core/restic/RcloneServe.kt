package com.xayah.core.restic

import android.util.Log
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.SFTPExtra
import com.xayah.core.model.SFTPAuthMode
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RcloneServe @Inject constructor(
    private val shared: ResticShared,
) {
    /** 一次 serve 会话：restUrl 交给 rustic JNI，id 用于 serve/stop。 */
    data class Session(val restUrl: String, val id: String)

    /**
     * 用 SFTP 表单字段起 `serve restic --addr localhost:0`，
     * 返回 rest:http://127.0.0.1:PORT/ 与 serve id。
     */
    suspend fun start(cloudEntity: CloudEntity, remotePath: String): Session {
        val extra = ResticShared.json.decodeFromString<SFTPExtra>(cloudEntity.extra)
        val fs = buildSftpConnectionString(cloudEntity, extra, remotePath)

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

    suspend fun stop(id: String) {
        runCatching {
            shared.rootService.rcloneRpc("serve/stop", JSONObject().put("id", id).toString())
        }.onFailure { Log.e(ResticShared.TAG, "serve/stop 失败 id=$id", it) }
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
        val root = shared.formatOpenDALRoot(remotePath)

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
                // key_pem 需把私钥换行替换成字面 \n
                val pem = extra.privateKey.replace("\n", "\\n")
                sb.append("key_pem='").append(pem).append("'")
            }
        }
        sb.append(":").append(root)
        return sb.toString()
    }
}