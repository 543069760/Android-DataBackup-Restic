package com.xayah.core.restic

import android.util.Log
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.restic.ResticBackupFiles   // listBackedUpFilesFromS3WithSqlJni 返回类型
import com.xayah.core.rootservice.ICallback            // ICallback? / ICallback.Stub()
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * COS/S3 备份的 JNI 实现（纯 JNI，不依赖 restic 二进制）。
 * 共享 helper（formatOpenDALRoot / buildOpenDALEndpoint / parseAppsDb）统一由 ResticShared 提供。
 *
 * options key 依据 opendal 0.57.0 `opendal-service-cos` 的 CosConfig 字段确定：
 *   root / endpoint / secret_id / secret_key / bucket（无 region，凭据用 secret_id/secret_key）。
 */
@Singleton
class ResticRepositoryCos @Inject constructor(
    private val shared: ResticShared,
) {
    // initCosRepository —— 对应 initS3Repository
    suspend fun initCosRepository(
        extra: S3Extra, remotePath: String, password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val options = buildS3BackendOptions(extra, remotePath)
            val result = shared.rootService.initRusticRepository("opendal:cos", password, options)
            if (result.isSuccess) Result.success("COS repository initialized")
            else Result.failure(Exception(result.exceptionOrNull()?.message ?: "Unknown error during rustic init"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // backupFileToCos —— 对应 backupFileToS3，返回 Pair<Int,String>（保持上层契约）
    suspend fun backupFileToCos(
        extra: S3Extra, remotePath: String, filePath: String,
        tags: List<String>, password: String,
        progressCallback: ResticRepository.ResticProgressCallback? = null
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val options = buildS3BackendOptions(extra, remotePath)
            val callback: ICallback? = if (progressCallback != null) object : ICallback.Stub() {
                override fun onProgress(bytesWritten: Long, speed: Long, progress: Float) {
                    progressCallback.onBackupProgress(progress, bytesWritten, 0L, 0L, 0L, speed)
                }
                override fun onRestorePlan(filesTotal: Long, bytesTotal: Long, filesSkipped: Long, bytesSkipped: Long) {}
            } else null
            val snapshotId = shared.rootService.createRusticSnapshot(
                repositoryPath = "opendal:cos", password = password,
                sourcePaths = listOf(filePath), tags = tags,
                options = options, callback = callback
            )
            if (snapshotId.isNotBlank())
                Pair(0, """{"message_type":"summary","snapshot_id":"$snapshotId"}""")
            else Pair(1, "Rustic returned an empty snapshot ID")
        } catch (e: Exception) { Pair(1, e.message ?: "Unknown error") }
    }

    // restoreSnapshotFromCos —— 对应 restoreSnapshotFromS3
    suspend fun restoreSnapshotFromCos(
        cloudEntity: CloudEntity, password: String, snapshotId: String,
        targetPath: String, snapshotSubPath: String? = null,
        includePath: String? = null,
        progressCallback: ResticRepository.ResticProgressCallback? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<S3Extra>(cloudEntity.extra)
            val options = buildS3BackendOptions(extra, cloudEntity.remote)
            val fullSnapshotId = if (!snapshotSubPath.isNullOrEmpty()) "$snapshotId:$snapshotSubPath" else snapshotId
            // 与本地 restoreSnapshot 一致：include 语义要加 "!" 前缀
            val includeGlob = if (!includePath.isNullOrEmpty()) "!$includePath" else ""
            val callback: ICallback? = if (progressCallback != null) object : ICallback.Stub() {
                @Volatile var planFilesTotal = 0L; @Volatile var planBytesTotal = 0L
                @Volatile var planFilesSkipped = 0L; @Volatile var planBytesSkipped = 0L
                override fun onRestorePlan(filesTotal: Long, bytesTotal: Long, filesSkipped: Long, bytesSkipped: Long) {
                    planFilesTotal = filesTotal; planBytesTotal = bytesTotal
                    planFilesSkipped = filesSkipped; planBytesSkipped = bytesSkipped
                }
                override fun onProgress(bytesWritten: Long, speed: Long, progress: Float) {
                    progressCallback.onRestoreProgress(0L, planFilesTotal, bytesWritten, planBytesTotal, planFilesSkipped, planBytesSkipped)
                }
            } else null
            val result = shared.rootService.restoreRusticSnapshot(
                repositoryPath = "opendal:cos", password = password,
                snapshotId = fullSnapshotId, destinationPath = targetPath,
                options = options, includeGlob = includeGlob, callback = callback
            )
            result.isSuccess
        } catch (e: Exception) { false }
    }

    // forgetSnapshotFromCos —— 对应 forgetSnapshotFromS3
    suspend fun forgetSnapshotFromCos(
        cloudEntity: CloudEntity, password: String, snapshotId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<S3Extra>(cloudEntity.extra)
            val options = buildS3BackendOptions(extra, cloudEntity.remote)
            shared.rootService.forgetRusticSnapshot("opendal:cos", password, snapshotId, options).isSuccess
        } catch (e: Exception) { false }
    }

    // pruneCosRepository —— 对应 pruneS3Repository（--max-unused unlimited）
    suspend fun pruneCosRepository(
        cloudEntity: CloudEntity, password: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<S3Extra>(cloudEntity.extra)
            val options = buildS3BackendOptions(extra, cloudEntity.remote)
            shared.rootService.pruneRusticRepository("opendal:cos", password, "unlimited", options).isSuccess
        } catch (e: Exception) { false }
    }

    // listBackedUpFilesFromS3WithSqlJni —— 对应 listBackedUpFilesFromS3WithSql，与 apps 版同构
    suspend fun listBackedUpFilesFromS3WithSqlJni(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupFiles> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<S3Extra>(cloudEntity.extra)
            val options = buildS3BackendOptions(extra, cloudEntity.remote)
            val sqlDir = File(shared.context.cacheDir, "sql"); if (!sqlDir.exists()) sqlDir.mkdirs()
            val dbFile = File(sqlDir, "snapshots_files_s3_${System.currentTimeMillis()}.db")
            val result = shared.rootService.listRusticSnapshotsDb("opendal:cos", password, dbFile.absolutePath, options)
            if (result.isFailure || !dbFile.exists() || dbFile.length() == 0L) { dbFile.delete(); return@withContext emptyList() }
            val files = shared.parseFilesDb(dbFile); dbFile.delete(); files
        } catch (e: Exception) { emptyList() }
    }

    /**
     * 【JNI 版】从 S3/COS 获取应用备份列表。
     * 与 ResticRepository.listBackedUpAppsFromS3WithSql（二进制路径，已随二进制移除）功能等价。
     */
    suspend fun listBackedUpAppsFromS3WithSqlJni(
        cloudEntity: CloudEntity,
        password: String
    ): List<ResticBackupApp> = withContext(Dispatchers.IO) {
        try {
            val extra = ResticShared.json.decodeFromString<S3Extra>(cloudEntity.extra)

            val repoPath = "opendal:cos" // opendal COS service scheme
            val options = buildS3BackendOptions(extra, cloudEntity.remote)

            val sqlDir = File(shared.context.cacheDir, "sql")
            if (!sqlDir.exists()) sqlDir.mkdirs()

            // .db（二进制 SQLite），由 librustic 直接写入（与本地 listSnapshots 一致）
            val dbFile = File(sqlDir, "snapshots_s3_${System.currentTimeMillis()}.db")

            Log.d(ResticShared.TAG, "执行 JNI listSnapshotsDb (COS)，repo=$repoPath，输出=${dbFile.absolutePath}")
            Log.d(ResticShared.TAG, "options keys=${options.keys.joinToString(",")}")

            val result = shared.rootService.listRusticSnapshotsDb(
                repositoryPath = repoPath,
                password = password,
                dbPath = dbFile.absolutePath,
                options = options,
            )
            if (result.isFailure) {
                Log.e(ResticShared.TAG, "listRusticSnapshotsDb (COS) 失败", result.exceptionOrNull())
                return@withContext emptyList()
            }

            if (!dbFile.exists() || dbFile.length() == 0L) {
                Log.e(ResticShared.TAG, "DB 文件未生成或为空 (COS)")
                return@withContext emptyList()
            }

            val apps = shared.parseAppsDb(dbFile)
            dbFile.delete()
            Log.d(ResticShared.TAG, "JNI 模式 (COS) 成功提取 ${apps.size} 个应用备份")
            apps
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "listBackedUpAppsFromS3WithSqlJni 异常", e)
            emptyList()
        }
    }

    /**
     * 把 S3Extra 翻译成 opendal:cos 后端所需的 options map。
     * key 名对应 opendal 0.57.0 CosConfig 字段，原样透传给 opendal Operator::via_iter。
     * 密码不放 map，单独作为 password 参数传给 JNI。
     */
    private fun buildS3BackendOptions(extra: S3Extra, remotePath: String): Map<String, String> {
        return mapOf(
            "bucket" to extra.bucket,
            "root" to shared.formatOpenDALRoot(remotePath),
            "endpoint" to shared.buildOpenDALEndpoint(extra),
            "secret_id" to extra.accessKeyId,
            "secret_key" to extra.secretAccessKey,
        )
    }
}