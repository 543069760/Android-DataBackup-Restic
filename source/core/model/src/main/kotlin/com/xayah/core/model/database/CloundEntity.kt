package com.xayah.core.model.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.xayah.core.model.CloudType
import com.xayah.core.model.SFTPAuthMode
import kotlinx.serialization.Serializable

@Serializable
data class FTPExtra(
    val port: Int,
    val resticPassword: String = "",   // 新增：按账户存储的 restic 仓库密码
)

@Serializable
data class SFTPExtra(
    val port: Int,
    val privateKey: String,
    val mode: SFTPAuthMode = SFTPAuthMode.PASSWORD,
    val resticPassword: String = "",   // 新增：按账户存储的 restic 仓库密码
)

@Serializable
data class WebDAVExtra(
    val insecure: Boolean,
    val protocol: WebDAVProtocol = WebDAVProtocol.HTTPS,
    val resticPassword: String = "",   // 新增：按账户存储的 restic 仓库密码
)

@Serializable
data class S3Extra(
    val type: String = "S3",
    val accessKeyId: String,
    val secretAccessKey: String,
    val bucket: String,
    val endpoint: String = "",
    val protocol: S3Protocol = S3Protocol.HTTPS,
    val resticPassword: String = "",
)

enum class S3Protocol {
    HTTP,
    HTTPS
}

enum class WebDAVProtocol {
    HTTP,
    HTTPS
}

@Entity
data class CloudEntity(
    @PrimaryKey var name: String,
    val type: CloudType = CloudType.S3,
    val host: String,
    val user: String,
    val pass: String,
    val remote: String,
    val extra: String,
    val activated: Boolean,
)
