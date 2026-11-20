package com.xayah.core.model.restic

import com.xayah.core.model.DataType
import kotlinx.serialization.Serializable

/**
 * 媒体（文件）恢复队列元素（临时文件 restic_media_restore_queue.json 的单条）。
 *
 * 设计目的：列表页「下一步」只解 KB 级 config 并统计**预期**恢复大小，
 * 重型 media.tar 的下载推迟到 setup 之后由服务层按本队列逐条解出。
 *
 * 与 [ResticRestoreQueueItem] 的区别：后者以 (packageName, userId) 为身份，
 * 本模型以 mediaName 为身份，并额外携带 [fullPath]（快照内绝对路径），
 * 用于拼 `snapshotId:fullPath` 精确命中，避免裸 id 把整棵绝对路径树解到目标目录造成嵌套错位。
 *
 * [accountName] 存**原始未编码**的 cloudEntity.name；本地仓库恢复时为空串。
 */
@Serializable
data class ResticMediaRestoreQueueItem(
    val mediaName: String,
    val dataType: DataType,
    val snapshotId: String,
    /** 快照内绝对路径（创建快照时的 sourcePaths[0]），空串表示按裸 id 解 */
    val fullPath: String = "",
    /** 云端账户名（明文）；空串表示本地 restic 仓库 */
    val accountName: String = "",
    /** 本地 restic 仓库路径；[accountName] 为空时使用（密码由服务层按 OTG 状态解析，不落盘） */
    val repoPath: String = "",
    /** 预期恢复字节数（来自快照 totalBytesProcessed），用于 setup 页展示 */
    val expectedBytes: Long = 0,
)