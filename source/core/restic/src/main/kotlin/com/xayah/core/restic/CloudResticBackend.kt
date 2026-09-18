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