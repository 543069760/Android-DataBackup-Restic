package com.xayah.core.model.restic

import com.xayah.core.model.DataType
import kotlinx.serialization.Serializable

/**
 * 批量恢复队列元素（临时文件 restic_restore_queue.json 的单条）。
 *
 * 独立于 [ResticBackupApp]，避免向现有备份/快照列表模型引入恢复专用的身份字段。
 *
 * 关于 [accountName]：
 * - 本地 restic 仓库恢复时为空字符串 ""。
 * - 云端 restic 仓库恢复时存**原始未编码**的 cloudEntity.name（可能含中文/日文等非 ASCII 字符）。
 * - 这里刻意存明文：队列走 JSON 落盘（kotlinx.serialization / UTF-8），不经过导航 route，
 *   因此**不做** encodeURL / encodeAccountId。服务侧凭明文名直接 cloudRepo.queryByName(accountName)。
 * - 仅当服务内需要拼接账号目录/匹配 restic tag 时，才对 accountName 调用
 *   com.xayah.core.util.encodeAccountId（Base64URL 无填充），本字段本身始终保持明文。
 */
@Serializable
data class ResticRestoreQueueItem(
    val packageName: String,
    val userId: Int,
    val dataType: DataType,
    val snapshotId: String,
    val accountName: String = "",
    /**
     * 该批恢复是否走 OTG 仓库的持久化决策。
     *
     * 由 writer（ResticRestoreViewModel.prepareBatchRestore）在入队时按当时仍正确的
     * readResticActiveIsOtg() 一次性 stamp 进每条队列元素，服务端 extractOne() 据此按条选择
     * OTG 仓库（readResticOtgRepoPath/readResticOtgPassword）或本地仓库。
     *
     * 替代易被 processing 图（RestoreViewModelImpl.FinishSetup 无条件 saveResticActiveIsOtg(false)）
     * 重置的全局标记，避免重型 tar 解出时读到被覆盖后的 false 而误回退本地仓库。
     *
     * 默认 false：保证既有 writer 不传该参数可编译，且旧队列 JSON（无该字段）能兼容反序列化。
     */
    val isOtg: Boolean = false,
)