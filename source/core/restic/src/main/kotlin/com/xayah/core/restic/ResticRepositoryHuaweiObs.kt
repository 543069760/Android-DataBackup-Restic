package com.xayah.core.restic

import android.util.Log
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.HuaweiObsExtra
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.rootservice.ICallback
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readHuaweiObsResticPassword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResticRepositoryHuaweiObs @Inject constructor(
    private val shared: ResticShared,
) : CloudResticBackend {

    override suspend fun initRepository(
        cloudEntity: CloudEntity, remotePath: String, password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<HuaweiObsExtra>(cloudEntity.extra)
            val options = buildHuaweiObsBackendOptions(extra, remotePath) + shared.context.resticCompressionOptions()
            Log.i("ResticCompression", "huaweiobs initRepository options=$options")
            val result = shared.rootService.initRusticRepository("opendal:obs", password, options)
            if (result.isSuccess) Result.success("Huawei OBS repository initialized")
            else Result.failure(Exception(result.exceptionOrNull()?.message ?: "Unknown error during rustic init"))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun resolveResticPassword(cloudEntity: CloudEntity): String {
        val extra = ResticShared.json.decodeFromString<HuaweiObsExtra>(cloudEntity.extra)
        return extra.resticPassword.ifEmpty {
            shared.context.readHuaweiObsResticPassword() ?: shared.context.readResticPassword() ?: ""
        }
    }

    override suspend fun checkRepository(
        cloudEntity: CloudEntity, password: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<HuaweiObsExtra>(cloudEntity.extra)
            val options = buildHuaweiObsBackendOptions(extra, cloudEntity.remote)

            val exists = shared.rootService.rusticRepositoryExists("opendal:obs", options)
            if (!exists) return@withContext Result.failure(Exception("仓库不存在或不可访问"))

            val validate = shared.rootService.validateRusticRepository("opendal:obs", password, options)
            if (validate.isFailure) return@withContext Result.failure(validate.exceptionOrNull() ?: Exception("仓库密码错误或无法打开"))

            val check = shared.rootService.checkRusticRepository("opendal:obs", password, options)
            if (check.isFailure) return@withContext Result.failure(check.exceptionOrNull() ?: Exception("仓库损坏"))

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "checkRepository(huaweiobs) 异常", e); Result.failure(e)
        }
    }

    override suspend fun backupFile(
        cloudEntity: CloudEntity, remotePath: String, filePath: String,
        tags: List<String>, password: String,
        progressCallback: ResticRepository.ResticProgressCallback?,
        cancelId: Long
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<HuaweiObsExtra>(cloudEntity.extra)
            val options = buildHuaweiObsBackendOptions(extra, remotePath)

            Log.i("RusticCancel", "backupFile(huaweiobs) enter, cancelId=$cancelId")

            val callback: ICallback? = progressCallback?.let { cb ->
                object : ICallback.Stub() {
                    override fun onProgress(
                        readBytes: Long, readTotal: Long, readProgress: Float,
                        writtenBytes: Long, writtenSpeed: Long
                    ) {
                        cb.onBackupProgress(
                            percentDone = readProgress,
                            bytesDone   = writtenBytes,
                            bytesTotal  = readTotal,
                            filesDone   = readBytes,
                            filesTotal  = 0L,
                            speed       = writtenSpeed
                        )
                    }

                    override fun onRestorePlan(
                        filesTotal: Long, bytesTotal: Long,
                        filesSkipped: Long, bytesSkipped: Long
                    ) {}
                }
            }

            val snapshotId = shared.rootService.createRusticSnapshot(
                repositoryPath = "opendal:obs",
                password = password,
                sourcePaths = listOf(filePath),
                tags = tags,
                options = options,
                callback = callback,
                cancelId = cancelId
            )

            if (snapshotId.isNotBlank()) {
                Log.d(ResticShared.TAG, "backupFile(huaweiobs) 成功，snapshotId=$snapshotId")
                Pair(0, snapshotId)
            } else {
                Log.e(ResticShared.TAG, "backupFile(huaweiobs) 返回空快照 ID")
                Pair(1, "Rustic returned an empty snapshot ID")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            if (msg.contains("cancel", ignoreCase = true)) {
                Log.i("RusticCancel", "backupFile(huaweiobs) cancelled by user, cancelId=$cancelId, msg=$msg")
                Pair(1, "用户取消")
            } else {
                Log.e("RusticCancel", "backupFile(huaweiobs) failed, cancelId=$cancelId, msg=$msg")
                Pair(1, msg)
            }
        }
    }

    override suspend fun <T> withStreamBackupContext(
        cloudEntity: CloudEntity,
        remotePath: String,
        block: suspend (repoPath: String, options: Map<String, String>) -> T
    ): T = withContext(Dispatchers.IO) {
        val extra = ResticShared.json.decodeFromString<HuaweiObsExtra>(cloudEntity.extra)
        val options = buildHuaweiObsBackendOptions(extra, remotePath)
        Log.i("ResticHuaweiObsRoot", "withStreamBackupContext remotePath=$remotePath root=${options["root"]}")
        block("opendal:obs", options)
    }

    override suspend fun listSnapshots(
        cloudEntity: CloudEntity, password: String
    ): List<ResticSnapshot> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<HuaweiObsExtra>(cloudEntity.extra)
            val options = buildHuaweiObsBackendOptions(extra, cloudEntity.remote)
            val sqlDir = File(shared.context.cacheDir, "sql"); if (!sqlDir.exists()) sqlDir.mkdirs()
            val dbFile = File(sqlDir, "snapshots_icons_huaweiobs_${System.currentTimeMillis()}.db")
            val result = shared.rootService.listRusticSnapshotsDb("opendal:obs", password, dbFile.absolutePath, options)
            if (result.isFailure || !dbFile.exists() || dbFile.length() == 0L) { dbFile.delete(); return@withContext emptyList() }
            val snapshots = shared.parseSnapshotsDb(dbFile); dbFile.delete(); snapshots
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "listSnapshots(huaweiobs) 异常", e); emptyList()
        }
    }

    override suspend fun restoreSnapshot(
        cloudEntity: CloudEntity, password: String, snapshotId: String,
        targetPath: String, snapshotSubPath: String?,
        includePath: String?,
        progressCallback: ResticRepository.ResticProgressCallback?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<HuaweiObsExtra>(cloudEntity.extra)
            val options = buildHuaweiObsBackendOptions(extra, cloudEntity.remote)
            val fullSnapshotId = if (!snapshotSubPath.isNullOrEmpty()) "$snapshotId:$snapshotSubPath" else snapshotId
            val includeGlob = if (!includePath.isNullOrEmpty()) "!$includePath" else ""
            val callback: ICallback? = if (progressCallback != null) object : ICallback.Stub() {
                @Volatile var planFilesTotal = 0L; @Volatile var planBytesTotal = 0L
                @Volatile var planFilesSkipped = 0L; @Volatile var planBytesSkipped = 0L
                override fun onRestorePlan(filesTotal: Long, bytesTotal: Long, filesSkipped: Long, bytesSkipped: Long) {
                    planFilesTotal = filesTotal; planBytesTotal = bytesTotal
                    planFilesSkipped = filesSkipped; planBytesSkipped = bytesSkipped
                }
                override fun onProgress(
                    readBytes: Long, readTotal: Long, readProgress: Float,
                    writtenBytes: Long, writtenSpeed: Long
                ) {
                    progressCallback.onRestoreProgress(
                        0L, planFilesTotal, readBytes,
                        if (readTotal > 0) readTotal else planBytesTotal,
                        planFilesSkipped, planBytesSkipped
                    )
                }
            } else null
            val result = shared.rootService.restoreRusticSnapshot(
                repositoryPath = "opendal:obs", password = password,
                snapshotId = fullSnapshotId, destinationPath = targetPath,
                options = options, includeGlob = includeGlob, callback = callback
            )
            result.isSuccess
        } catch (e: Exception) { false }
    }

    override suspend fun forgetSnapshot(
        cloudEntity: CloudEntity, password: String, snapshotId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<HuaweiObsExtra>(cloudEntity.extra)
            val options = buildHuaweiObsBackendOptions(extra, cloudEntity.remote)
            shared.rootService.forgetRusticSnapshot("opendal:obs", password, snapshotId, options).isSuccess
        } catch (e: Exception) { false }
    }

    override suspend fun pruneRepository(
        cloudEntity: CloudEntity, password: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<HuaweiObsExtra>(cloudEntity.extra)
            val options = buildHuaweiObsBackendOptions(extra, cloudEntity.remote)
            shared.rootService.pruneRusticRepository("opendal:obs", password, "unlimited", options, instantDelete = true).isSuccess
        } catch (e: Exception) { false }
    }

    override suspend fun listBackedUpFiles(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupFiles> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<HuaweiObsExtra>(cloudEntity.extra)
            val options = buildHuaweiObsBackendOptions(extra, cloudEntity.remote)
            val sqlDir = File(shared.context.cacheDir, "sql"); if (!sqlDir.exists()) sqlDir.mkdirs()
            val dbFile = File(sqlDir, "snapshots_files_huaweiobs_${System.currentTimeMillis()}.db")
            val result = shared.rootService.listRusticSnapshotsDb("opendal:obs", password, dbFile.absolutePath, options)
            if (result.isFailure || !dbFile.exists() || dbFile.length() == 0L) { dbFile.delete(); return@withContext emptyList() }
            val files = shared.parseFilesDb(dbFile); dbFile.delete(); files
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun readCachedApps(cloudEntity: CloudEntity): List<ResticBackupApp> =
        withContext(Dispatchers.IO) { shared.readCachedApps(cloudEntity.name) }

    override suspend fun refreshAndListApps(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupApp> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<HuaweiObsExtra>(cloudEntity.extra)
            val options = buildHuaweiObsBackendOptions(extra, cloudEntity.remote)
            shared.refreshAppsDb(cloudEntity.name, "opendal:obs", password, options)
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "refreshAndListApps (HUAWEIOBS) 异常", e)
            shared.readCachedApps(cloudEntity.name)
        }
    }

    private fun buildHuaweiObsBackendOptions(extra: HuaweiObsExtra, remotePath: String): Map<String, String> {
        return mapOf(
            "bucket" to extra.bucket,
            "root" to shared.formatOpenDALRoot(remotePath),
            "endpoint" to buildHuaweiObsEndpoint(extra),
            "access_key_id" to extra.accessKeyId,
            "secret_access_key" to extra.secretAccessKey,
        )
    }

    /**
     * OBS 的 endpoint 由 SDK 拼成 https://{bucket}.{endpoint}，故剥离 scheme 后下发。
     */
    private fun buildHuaweiObsEndpoint(extra: HuaweiObsExtra): String {
        val raw = extra.endpoint.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")
        return raw
    }
}
