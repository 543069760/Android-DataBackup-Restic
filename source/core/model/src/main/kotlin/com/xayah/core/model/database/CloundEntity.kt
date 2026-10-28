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
data class SMBExtra(
    val port: Int,
    val share: String,
    val domain: String = "",
    val resticPassword: String = "",
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

@Serializable
data class AwsS3Extra(
    val type: String = "AWSS3",
    val accessKeyId: String,
    val secretAccessKey: String,
    val bucket: String,
    val region: String = "us-east-1",
    val endpoint: String = "",
    val protocol: AwsS3Protocol = AwsS3Protocol.HTTPS,
    val enableVirtualHostStyle: Boolean,
    val resticPassword: String = "",
)

enum class AwsS3Protocol {
    HTTP,
    HTTPS
}

/**
 * 阿里云 OSS 账户附加配置。
 * 字段与 opendal 0.57 `opendal-service-oss` 的 OssConfig 一一对应：
 *   bucket / endpoint / access_key_id / access_key_secret（OSS 无 region，地域由 endpoint 表达）。
 * endpoint 必须填写（如 oss-cn-hangzhou.aliyuncs.com），未带 scheme 时按 protocol 补 http(s)://。
 */
@Serializable
data class AliyunOssExtra(
    val type: String = "ALIYUNOSS",
    val accessKeyId: String,
    val accessKeySecret: String,
    val bucket: String,
    val endpoint: String = "",
    val protocol: AliyunOssProtocol = AliyunOssProtocol.HTTPS,
    val resticPassword: String = "",
)

enum class AliyunOssProtocol {
    HTTP,
    HTTPS
}

/**
 * 华为云 OBS 账户附加配置。
 * 字段与 opendal 0.57 `opendal-service-obs` 的 ObsConfig 一一对应：
 *   bucket / endpoint / access_key_id / secret_access_key（OBS 无 region，地域由 endpoint 表达）。
 * endpoint 必须填写（如 obs.cn-north-4.myhuaweicloud.com）；OBS SDK 会自行拼接
 * https://{bucket}.{endpoint}，因此下发前一律剥离 scheme，protocol 仅用于界面地址预览。
 */
@Serializable
data class HuaweiObsExtra(
    val type: String = "HUAWEIOBS",
    val accessKeyId: String,
    val secretAccessKey: String,
    val bucket: String,
    val endpoint: String = "",
    val protocol: HuaweiObsProtocol = HuaweiObsProtocol.HTTPS,
    val resticPassword: String = "",
)

enum class HuaweiObsProtocol {
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
