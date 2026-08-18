package com.xayah.core.model.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.xayah.core.model.CloudType
import com.xayah.core.model.SFTPAuthMode
import com.xayah.core.model.SmbAuthMode
import com.xayah.core.model.SmbVersion
import kotlinx.serialization.Serializable

@Serializable
data class FTPExtra(
    val port: Int,
    val resticPassword: String = "",   // 新增：按账户存储的 restic 仓库密码
)

data class SMBExtra(
    val share: String,
    val port: Int,
    val domain: String,
    val version: List<SmbVersion>,
    val mode: SmbAuthMode = SmbAuthMode.PASSWORD,
)

data class SFTPExtra(
    val port: Int,
    val privateKey: String,
    val mode: SFTPAuthMode = SFTPAuthMode.PASSWORD,
)

data class WebDAVExtra(
    val insecure: Boolean,
)

@Serializable
data class S3Extra(
    val type: String = "S3",
    val region: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val bucket: String,
    val endpoint: String = "",
    val protocol: S3Protocol = S3Protocol.HTTPS,
    val networkType: S3NetworkType = S3NetworkType.PUBLIC,
    val resticPassword: String = "",   // 新增：按账户存储的 restic 仓库密码
)

enum class S3Protocol {
    HTTP,
    HTTPS
}

enum class S3NetworkType {
    PUBLIC,  // 公网(公有云)
    PRIVATE  // 内网(自建S3)
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
