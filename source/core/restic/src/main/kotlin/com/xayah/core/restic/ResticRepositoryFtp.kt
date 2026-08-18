package com.xayah.core.restic

import android.util.Log
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.FTPExtra
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.rootservice.ICallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FTP 备份的 JNI 实现（纯 JNI，不依赖 restic 二进制），参照 ResticRepositoryCos。
 * scheme 使用 opendal:ftp，options key 依据 opendal 0.57.0 opendal-service-ftp 的 FtpConfig 字段：
 *   endpoint（形如 ftp://host:port）/ root / user / password。
 * 说明：user/password（FTP 登录）放入 options map；restic 仓库密码单独作为 password 参数传 JNI。
 */
@Singleton
class ResticRepositoryFtp @Inject constructor(
    private val shared: ResticShared,
) {
    // initFtpRepository —— 对应 initCosRepository
    // 压缩配置：仅在建库时合并压缩 key；语义与本地/COS 一致
    //   -1(AUTO) -> resticCompressionOptions() 返回 emptyMap()（不设 set_compression → rustic v2 默认压缩）
    //    0(OFF)  -> {COMPRESSION_KEY:"0"}（关闭压缩）
    //  1..22     -> {COMPRESSION_KEY:"<level>"}（指定 zstd 级别）
    // 该 key 会被 Rust 侧 backends() 过滤，不会进入 opendal 后端配置。
    suspend fun initFtpRepository(
        cloudEntity: CloudEntity, remotePath: String, password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val options = buildFtpBackendOptions(cloudEntity, remotePath)
            Log.i("ResticFtpRoot", "initFtp options=$options root=${options["root"]} endpoint=${options["endpoint"]}")

            // 1) 建库
            val initResult = shared.rootService.initRusticRepository("opendal:ftp", password, options)
            if (initResult.isFailure) {
                return@withContext Result.failure(
                    Exception(initResult.exceptionOrNull()?.message ?: "Unknown error during rustic init")
                )
            }

            // 2) 复核：用相同 options 确认仓库 config 真的写进了 FTP
            val exists = shared.rootService.rusticRepositoryExists("opendal:ftp", options)
            if (!exists) {
                Log.e(ResticShared.TAG, "FTP init 报成功但 repositoryExists=false")
                return@withContext Result.failure(
                    Exception("init 报成功但仓库 config 未写入 FTP（疑似 opendal FTP 被动数据连接失败）")
                )
            }

            Log.d(ResticShared.TAG, "FTP repository initialized and verified")
            Result.success("FTP repository initialized")
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "initFtpRepository 异常", e)
            Result.failure(e)
        }
    }

    // backupFileToFtp —— 对应 backupFileToCos，返回 Pair<Int,String>（保持上层契约）
    suspend fun backupFileToFtp(
        cloudEntity: CloudEntity, remotePath: String, filePath: String,
        tags: List<String>, password: String,
        progressCallback: ResticRepository.ResticProgressCallback? = null,
        cancelId: Long = 0L
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val options = buildFtpBackendOptions(cloudEntity, remotePath)

            Log.i("ResticFtpRoot", "backupFtp remotePath=$remotePath root=${options["root"]}")

            val callback: ICallback? = progressCallback?.let { cb ->
                object : ICallback.Stub() {
                    // 5 参签名，与 ICallback.aidl（commit cbd81d0e）一致
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
                repositoryPath = "opendal:ftp",
                password = password,
                sourcePaths = listOf(filePath),
                tags = tags,
                options = options,
                callback = callback,
                cancelId = cancelId
            )

            if (snapshotId.isNotBlank()) {
                Log.d(ResticShared.TAG, "backupFileToFtp 成功，snapshotId=$snapshotId")
                Pair(0, snapshotId)
            } else {
                Log.e(ResticShared.TAG, "backupFileToFtp 返回空快照 ID")
                Pair(1, "Rustic returned an empty snapshot ID")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            if (msg.contains("cancel", ignoreCase = true)) {
                Log.i("RusticCancel", "backupFileToFtp cancelled by user, cancelId=$cancelId, msg=$msg")
                Pair(1, "用户取消")
            } else {
                Log.e("RusticCancel", "backupFileToFtp failed, cancelId=$cancelId, msg=$msg")
                Pair(1, msg)
            }
        }
    }

    // restoreSnapshotFromFtp —— 对应 restoreSnapshotFromCos
    suspend fun restoreSnapshotFromFtp(
        cloudEntity: CloudEntity, password: String, snapshotId: String,
        targetPath: String, snapshotSubPath: String? = null,
        includePath: String? = null,
        progressCallback: ResticRepository.ResticProgressCallback? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val options = buildFtpBackendOptions(cloudEntity, cloudEntity.remote)
            Log.i("ResticFtpRoot", "listFtp root=${options["root"]}")
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
                        0L, planFilesTotal, readBytes, planBytesTotal,
                        planFilesSkipped, planBytesSkipped
                    )
                }
            } else null
            val result = shared.rootService.restoreRusticSnapshot(
                repositoryPath = "opendal:ftp", password = password,
                snapshotId = fullSnapshotId, destinationPath = targetPath,
                options = options, includeGlob = includeGlob, callback = callback
            )
            result.isSuccess
        } catch (e: Exception) { false }
    }

    // forgetSnapshotFromFtp —— 对应 forgetSnapshotFromCos
    suspend fun forgetSnapshotFromFtp(
        cloudEntity: CloudEntity, password: String, snapshotId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val options = buildFtpBackendOptions(cloudEntity, cloudEntity.remote)
            shared.rootService.forgetRusticSnapshot("opendal:ftp", password, snapshotId, options).isSuccess
        } catch (e: Exception) { false }
    }

    // pruneFtpRepository —— 对应 pruneCosRepository（--max-unused unlimited）
    suspend fun pruneFtpRepository(
        cloudEntity: CloudEntity, password: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val options = buildFtpBackendOptions(cloudEntity, cloudEntity.remote)
            shared.rootService.pruneRusticRepository("opendal:ftp", password, "unlimited", options).isSuccess
        } catch (e: Exception) { false }
    }

    // listBackedUpFilesFromFtpWithSqlJni —— 对应 listBackedUpFilesFromS3WithSqlJni
    suspend fun listBackedUpFilesFromFtpWithSqlJni(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupFiles> = withContext(Dispatchers.IO) {
        try {
            val options = buildFtpBackendOptions(cloudEntity, cloudEntity.remote)
            val sqlDir = File(shared.context.cacheDir, "sql"); if (!sqlDir.exists()) sqlDir.mkdirs()
            val dbFile = File(sqlDir, "snapshots_files_ftp_${System.currentTimeMillis()}.db")
            val result = shared.rootService.listRusticSnapshotsDb("opendal:ftp", password, dbFile.absolutePath, options)
            if (result.isFailure || !dbFile.exists() || dbFile.length() == 0L) { dbFile.delete(); return@withContext emptyList() }
            val files = shared.parseFilesDb(dbFile); dbFile.delete(); files
        } catch (e: Exception) { emptyList() }
    }

    // listBackedUpAppsFromFtpWithSqlJni —— 对应 listBackedUpAppsFromS3WithSqlJni
    suspend fun listBackedUpAppsFromFtpWithSqlJni(
        cloudEntity: CloudEntity,
        password: String
    ): List<ResticBackupApp> = withContext(Dispatchers.IO) {
        try {
            val repoPath = "opendal:ftp"
            val options = buildFtpBackendOptions(cloudEntity, cloudEntity.remote)

            val sqlDir = File(shared.context.cacheDir, "sql")
            if (!sqlDir.exists()) sqlDir.mkdirs()

            val dbFile = File(sqlDir, "snapshots_ftp_${System.currentTimeMillis()}.db")

            Log.d(ResticShared.TAG, "执行 JNI listSnapshotsDb (FTP)，repo=$repoPath，输出=${dbFile.absolutePath}")
            Log.d(ResticShared.TAG, "options keys=${options.keys.joinToString(",")}")

            val result = shared.rootService.listRusticSnapshotsDb(
                repositoryPath = repoPath,
                password = password,
                dbPath = dbFile.absolutePath,
                options = options,
            )
            if (result.isFailure) {
                Log.e(ResticShared.TAG, "listRusticSnapshotsDb (FTP) 失败", result.exceptionOrNull())
                return@withContext emptyList()
            }
            if (!dbFile.exists() || dbFile.length() == 0L) {
                Log.e(ResticShared.TAG, "DB 文件未生成或为空 (FTP)")
                return@withContext emptyList()
            }

            val apps = shared.parseAppsDb(dbFile)
            dbFile.delete()
            Log.d(ResticShared.TAG, "JNI 模式 (FTP) 成功提取 ${apps.size} 个应用备份")
            apps
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "listBackedUpAppsFromFtpWithSqlJni 异常", e)
            emptyList()
        }
    }

    /**
     * 把 CloudEntity(host/user/pass) + FTPExtra(port) 翻译成 opendal:ftp 后端 options map。
     * key 名对应 opendal 0.57.0 FtpConfig 字段，原样透传给 opendal Operator::via_iter。
     * restic 仓库密码不放 map，单独作为 password 参数传给 JNI。
     */
    private fun buildFtpBackendOptions(cloudEntity: CloudEntity, remotePath: String): Map<String, String> {
        val extra = ResticShared.json.decodeFromString<FTPExtra>(cloudEntity.extra)
        val options = mapOf(
            "endpoint" to shared.buildOpenDALFtpEndpoint(cloudEntity.host, extra.port),
            "root" to shared.formatOpenDALRoot(remotePath),
            "user" to cloudEntity.user,
            "password" to cloudEntity.pass,
        )
        Log.i("ResticFtpRoot", "buildFtpOptions remotePath=$remotePath root=${options["root"]} endpoint=${options["endpoint"]}")
        return options
    }
}