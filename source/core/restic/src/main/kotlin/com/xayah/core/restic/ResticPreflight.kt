package com.xayah.core.restic

import android.content.Context
import com.xayah.core.common.R
import com.xayah.core.model.CloudType
import com.xayah.core.model.database.CloudEntity

/**
 * 云端账户建库前的统一前置校验。
 *
 * URL / endpoint 输入框只承载主机（WebDAV 与对象存储为完整 endpoint），目标目录
 * 必须走「高级 → 远程路径」写入 CloudEntity.remote。空 remote 时各后端行为不一致：
 * SMB 的 rclone 后端把空 root 当成 share 枚举层，config 根本写不进去；FTP/SFTP
 * 会静默把仓库建到登录家目录；WebDAV/对象存储建到根。因此所有 CloudResticBackend
 * 的建库入口必须先过这里，避免走到「init 报成功但仓库 config 未写入 …」兜底分支。
 */
object ResticPreflight {

    /** URL 栏只接受主机地址的协议；它们的 host 会被整段塞进 rclone 的 host 参数。 */
    private val PLAIN_HOST_TYPES = setOf(CloudType.FTP, CloudType.SFTP, CloudType.SMB)

    /** 返回可直接展示的失败原因；null 表示校验通过。 */
    fun error(context: Context, entity: CloudEntity, remotePath: String): String? {
        if (remotePath.isBlank()) return context.getString(R.string.restic_remote_path_required)
        return hostError(context, entity)
    }

    /** 只校验主机字段；测试连接阶段按设计不要求 remote 已选择。 */
    fun hostError(context: Context, entity: CloudEntity): String? =
        if (entity.type in PLAIN_HOST_TYPES && !isPlainHost(entity.host)) {
            context.getString(R.string.restic_host_only)
        } else {
            null
        }

    /** 供各后端 init 入口使用：非 null 时调用方应直接 `return@withContext it`。 */
    fun checkInit(context: Context, entity: CloudEntity, remotePath: String): Result<String>? =
        error(context, entity, remotePath)?.let { Result.failure(Exception(it)) }

    /** init 未抛异常但 config 读不到时的兜底文案，不再把用户引向「认证失败」的猜测。 */
    fun configMissingMessage(context: Context): String =
        context.getString(R.string.restic_config_missing_hint)

    /**
     * 兼容历史/导入值里残留的小写 scheme。这里必须与各后端
     * `removePrefix("smb://")` / `removePrefix("ftp://")` 的大小写敏感行为保持一致：
     * 大写 scheme 后端并不会剥离，放行反而会变成底层 rclone 的拨号错误，故直接判非法。
     */
    private fun isPlainHost(raw: String): Boolean {
        val host = raw.trim().removeSuffix("/")
        val withoutScheme = when {
            host.startsWith("smb://") -> host.substring(6)
            host.startsWith("sftp://") -> host.substring(7)
            host.startsWith("ftps://") -> host.substring(7)
            host.startsWith("ftp://") -> host.substring(6)
            else -> host
        }
        return withoutScheme.isNotEmpty() && !withoutScheme.contains('/') && !withoutScheme.contains("://")
    }
}
