package com.xayah.core.restic

import android.util.Log
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.S3Extra
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