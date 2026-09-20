package com.xayah.core.restic

import android.util.Log
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.AwsS3Extra
import com.xayah.core.model.database.AwsS3Protocol
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.rootservice.ICallback
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readAwsS3ResticPassword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AWS S3 / S3 兼容（MinIO 等自建）备份的 JNI 实现（scheme = opendal:s3）。
 * 与 ResticRepositoryCos 同构，仅：
 *   - scheme 用 "opendal:s3"（COS 是 "opendal:cos"）
 *   - decode 用 AwsS3Extra（含 region / enableVirtualHostStyle）
 *   - options 用 buildAwsS3BackendOptions（S3Config 字段：bucket/root/endpoint/region/
 *     access_key_id/secret_access_key，可选 enable_virtual_host_style）
 * endpoint 一律手动填写完整地址（AWS 与 MinIO 统一），本类内按 protocol 补 http(s)://。
 */
@Singleton
class ResticRepositoryAwsS3 @Inject constructor(
    private val shared: ResticShared,
) : CloudResticBackend {

    // ==================== CloudResticBackend override（统一入参 CloudEntity） ====================

    override suspend fun initRepository(
        cloudEntity: CloudEntity, remotePath: String, password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<AwsS3Extra>(cloudEntity.extra)
            // 仅在 init 这一处合并压缩 key（与 COS 一致）
            val options = buildAwsS3BackendOptions(extra, remotePath) + shared.context.resticCompressionOptions()
            Log.i("ResticCompression", "awss3 initRepository options=$options")
            val result = shared.rootService.initRusticRepository("opendal:s3", password, options)
            if (result.isSuccess) Result.success("AWS S3 repository initialized")
            else Result.failure(Exception(result.exceptionOrNull()?.message ?: "Unknown error during rustic init"))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun resolveResticPassword(cloudEntity: CloudEntity): String {
        val extra = ResticShared.json.decodeFromString<AwsS3Extra>(cloudEntity.extra)
        return extra.resticPassword.ifEmpty {
            shared.context.readAwsS3ResticPassword() ?: shared.context.readResticPassword() ?: ""
        }
    }

    override suspend fun checkRepository(
        cloudEntity: CloudEntity, password: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<AwsS3Extra>(cloudEntity.extra)
            val options = buildAwsS3BackendOptions(extra, cloudEntity.remote)

            val exists = shared.rootService.rusticRepositoryExists("opendal:s3", options)
            if (!exists) return@withContext Result.failure(Exception("仓库不存在或不可访问"))

            val validate = shared.rootService.validateRusticRepository("opendal:s3", password, options)
            if (validate.isFailure) return@withContext Result.failure(validate.exceptionOrNull() ?: Exception("仓库密码错误或无法打开"))

            val check = shared.rootService.checkRusticRepository("opendal:s3", password, options)
            if (check.isFailure) return@withContext Result.failure(check.exceptionOrNull() ?: Exception("仓库损坏"))

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "checkRepository(awss3) 异常", e); Result.failure(e)
        }
    }

    override suspend fun backupFile(
        cloudEntity: CloudEntity, remotePath: String, filePath: String,
        tags: List<String>, password: String,
        progressCallback: ResticRepository.ResticProgressCallback?,
        cancelId: Long
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<AwsS3Extra>(cloudEntity.extra)
            val options = buildAwsS3BackendOptions(extra, remotePath)

            Log.i("RusticCancel", "backupFile(awss3) enter, cancelId=$cancelId")

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
                repositoryPath = "opendal:s3",
                password = password,
                sourcePaths = listOf(filePath),
                tags = tags,
                options = options,
                callback = callback,
                cancelId = cancelId
            )

            if (snapshotId.isNotBlank()) {
                Log.d(ResticShared.TAG, "backupFile(awss3) 成功，snapshotId=$snapshotId")
                Pair(0, snapshotId)
            } else {
                Log.e(ResticShared.TAG, "backupFile(awss3) 返回空快照 ID")
                Pair(1, "Rustic returned an empty snapshot ID")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            if (msg.contains("cancel", ignoreCase = true)) {
                Log.i("RusticCancel", "backupFile(awss3) cancelled by user, cancelId=$cancelId, msg=$msg")
                Pair(1, "用户取消")
            } else {
                Log.e("RusticCancel", "backupFile(awss3) failed, cancelId=$cancelId, msg=$msg")
                Pair(1, msg)
            }
        }
    }

    override suspend fun listSnapshots(
        cloudEntity: CloudEntity, password: String
    ): List<ResticSnapshot> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<AwsS3Extra>(cloudEntity.extra)
            val options = buildAwsS3BackendOptions(extra, cloudEntity.remote)
            val sqlDir = File(shared.context.cacheDir, "sql"); if (!sqlDir.exists()) sqlDir.mkdirs()
            val dbFile = File(sqlDir, "snapshots_icons_awss3_${System.currentTimeMillis()}.db")
            val result = shared.rootService.listRusticSnapshotsDb("opendal:s3", password, dbFile.absolutePath, options)
            if (result.isFailure || !dbFile.exists() || dbFile.length() == 0L) { dbFile.delete(); return@withContext emptyList() }
            val snapshots = shared.parseSnapshotsDb(dbFile); dbFile.delete(); snapshots
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "listSnapshots(awss3) 异常", e); emptyList()
        }
    }

    override suspend fun restoreSnapshot(
        cloudEntity: CloudEntity, password: String, snapshotId: String,
        targetPath: String, snapshotSubPath: String?,
        includePath: String?,
        progressCallback: ResticRepository.ResticProgressCallback?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<AwsS3Extra>(cloudEntity.extra)
            val options = buildAwsS3BackendOptions(extra, cloudEntity.remote)
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
                repositoryPath = "opendal:s3", password = password,
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
            val extra = ResticShared.json.decodeFromString<AwsS3Extra>(cloudEntity.extra)
            val options = buildAwsS3BackendOptions(extra, cloudEntity.remote)
            shared.rootService.forgetRusticSnapshot("opendal:s3", password, snapshotId, options).isSuccess
        } catch (e: Exception) { false }
    }

    override suspend fun pruneRepository(
        cloudEntity: CloudEntity, password: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<AwsS3Extra>(cloudEntity.extra)
            val options = buildAwsS3BackendOptions(extra, cloudEntity.remote)
            shared.rootService.pruneRusticRepository("opendal:s3", password, "unlimited", options, instantDelete = true).isSuccess
        } catch (e: Exception) { false }
    }

    override suspend fun listBackedUpFiles(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupFiles> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<AwsS3Extra>(cloudEntity.extra)
            val options = buildAwsS3BackendOptions(extra, cloudEntity.remote)
            val sqlDir = File(shared.context.cacheDir, "sql"); if (!sqlDir.exists()) sqlDir.mkdirs()
            val dbFile = File(sqlDir, "snapshots_files_awss3_${System.currentTimeMillis()}.db")
            val result = shared.rootService.listRusticSnapshotsDb("opendal:s3", password, dbFile.absolutePath, options)
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
            val extra = ResticShared.json.decodeFromString<AwsS3Extra>(cloudEntity.extra)
            val options = buildAwsS3BackendOptions(extra, cloudEntity.remote)
            shared.refreshAppsDb(cloudEntity.name, "opendal:s3", password, options)
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "refreshAndListApps (AWSS3) 异常", e)
            shared.readCachedApps(cloudEntity.name)
        }
    }

    // ==================== options 拼接 ====================

    /**
     * 把 AwsS3Extra 翻译成 opendal:s3 后端所需 options map。
     * key 名对应 opendal S3Config 字段（endpoint/region/bucket/root/access_key_id/
     * secret_access_key，可选 enable_virtual_host_style）。密码不放 map。
     * endpoint 一律手动填写完整地址：若用户未带 scheme，则按 protocol 补 http(s)://。
     */
    private fun buildAwsS3BackendOptions(extra: AwsS3Extra, remotePath: String): Map<String, String> {
        val options = mutableMapOf(
            "bucket" to extra.bucket,
            "root" to shared.formatOpenDALRoot(remotePath),
            "endpoint" to buildAwsS3Endpoint(extra),
            "region" to extra.region,
            "access_key_id" to extra.accessKeyId,
            "secret_access_key" to extra.secretAccessKey,
        )
        if (extra.enableVirtualHostStyle) {
            options["enable_virtual_host_style"] = "true"
        }
        return options
    }

    /** endpoint 手填完整地址；已带 http(s):// 则原样，否则按 protocol 补前缀。空则返回 ""（由 region 推导）。 */
    private fun buildAwsS3Endpoint(extra: AwsS3Extra): String {
        val raw = extra.endpoint.trim().removeSuffix("/")
        if (raw.isEmpty()) return ""
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val scheme = when (extra.protocol) {
            AwsS3Protocol.HTTP -> "http"
            AwsS3Protocol.HTTPS -> "https"
        }
        return "$scheme://$raw"
    }
}