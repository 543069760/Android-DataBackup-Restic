package com.xayah.core.restic

import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.restic.ResticBackupFiles

/**
 * 批量解快照的单条描述。
 *
 * [includeGlob] 为纯路径（如 `media_restore_config.json`），空串表示不过滤；
 * 后端实现内部会拼 `!` 前缀，与单条 [CloudResticBackend.restoreSnapshot] 的 includePath 语义保持一致。
 */
data class RestoreBatchItem(
    val snapshotId: String,
    val destinationPath: String,
    val includeGlob: String = "",
)

/**
 * 云端 restic 后端统一接口。所有实现以 CloudEntity + remotePath + password 为统一入参，
 * 内部各自 decode extra / 起 serve。用于把散落的 when(CloudType) 收敛为多态调用。
 */
interface CloudResticBackend {
    suspend fun initRepository(
        cloudEntity: CloudEntity, remotePath: String, password: String
    ): Result<String>

    /**
     * 仓库可用性检查。
     *
     * [fullCheck] = true（默认）：exists + validate + 全库完整性 check，用于账户校验 / 手动维护；
     * [fullCheck] = false：只做 exists + validate，用于**每次备份的前置检查**。
     *
     * 全库 check 会遍历全部快照树与 index，在对象存储上意味着成百上千次远程 IO，
     * 仓库越大越慢（实测几十秒～几分钟），绝不能放在备份启动路径上。
     */
    suspend fun checkRepository(
        cloudEntity: CloudEntity, password: String, fullCheck: Boolean = true
    ): Result<Unit>

    suspend fun backupFile(
        cloudEntity: CloudEntity, remotePath: String, filePath: String,
        tags: List<String>, password: String,
        progressCallback: ResticRepository.ResticProgressCallback? = null,
        cancelId: Long = 0L
    ): Pair<Int, String>

    /**
     * fd 流式备份的"上下文回调"：由各后端准备好 repoPath/options（FTP/SFTP 用 serve 生命周期包住整个 block），
     * 把 streamBackupToRustic 的调用塞进这个窗口内执行。
     * 关键：block 内含 streamBackupToRustic，其 FATAL 失败路径会在 impl 侧用同一 repoPath/options forget 空壳快照，
     *       所以 FTP/SFTP 的 serve 必须存活到 block 完全返回（含失败 forget）之后才能 stop。
     */
    suspend fun <T> withStreamBackupContext(
        cloudEntity: CloudEntity,
        remotePath: String,
        block: suspend (repoPath: String, options: Map<String, String>) -> T
    ): T

    suspend fun listSnapshots(
        cloudEntity: CloudEntity, password: String
    ): List<ResticSnapshot>

    suspend fun restoreSnapshot(
        cloudEntity: CloudEntity, password: String, snapshotId: String,
        targetPath: String, snapshotSubPath: String? = null,
        includePath: String? = null,
        progressCallback: ResticRepository.ResticProgressCallback? = null
    ): Boolean

    /**
     * 批量解快照：只打开一次仓库，依次解 [items] 中的快照。
     *
     * 相比逐条调用 [restoreSnapshot]，省掉 N-1 次 open_repository + index 加载的固定开销，
     * 适合「一次解 N 个 KB 级 config」的场景。
     *
     * @return 与 [items] 等长的结果列表，true 表示该条成功；单条失败不影响后续条目。
     *         整体失败（如仓库打不开）时返回空列表。
     */
    suspend fun restoreSnapshotsBatch(
        cloudEntity: CloudEntity,
        password: String,
        items: List<RestoreBatchItem>,
    ): List<Boolean>

    suspend fun forgetSnapshot(
        cloudEntity: CloudEntity, password: String, snapshotId: String
    ): Boolean

    suspend fun pruneRepository(
        cloudEntity: CloudEntity, password: String
    ): Boolean

    suspend fun listBackedUpFiles(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupFiles>

    suspend fun readCachedApps(
        cloudEntity: CloudEntity
    ): List<ResticBackupApp>

    suspend fun refreshAndListApps(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupApp>

    suspend fun resolveResticPassword(
        cloudEntity: CloudEntity
    ): String
}