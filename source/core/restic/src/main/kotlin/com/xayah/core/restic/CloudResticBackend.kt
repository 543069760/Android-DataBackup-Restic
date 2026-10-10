package com.xayah.core.restic

import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.restic.ResticBackupFiles

/**
 * 云端 restic 后端统一接口。所有实现以 CloudEntity + remotePath + password 为统一入参，
 * 内部各自 decode extra / 起 serve。用于把散落的 when(CloudType) 收敛为多态调用。
 */
interface CloudResticBackend {
    suspend fun initRepository(
        cloudEntity: CloudEntity, remotePath: String, password: String
    ): Result<String>

    suspend fun checkRepository(
        cloudEntity: CloudEntity, password: String
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