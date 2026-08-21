package com.xayah.core.restic

import android.util.Log
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.rootservice.ICallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SFTP 备份的 JNI 实现（纯 JNI，不依赖 restic 二进制），结构对照 ResticRepositoryFtp。
 *
 * 与 FTP 的三点区别：
 *  1) repositoryPath 使用 librclone serve restic 起服务后返回的 rest: URL（session.restUrl），
 *     而不是 opendal:ftp；
 *  2) options 一律传 emptyMap()——SFTP 的 host/port/user/pass 认证由 rclone 在 serve 侧完成，
 *     rustic 侧只当作普通 REST 后端，不再需要 opendal 后端 options；
 *  3) 每个方法都用 serve 生命周期包住：start() 起服务拿端口，finally { stop(id) } 收尾。
 *     serve 必须在整批 JNI 调用期间一直存活，端口为 localhost:0 动态分配、不可跨会话复用。
 *
 * 说明：restic 仓库密码单独作为 password 参数传 JNI（与 FTP 一致）。
 */
@Singleton
class ResticRepositorySftp @Inject constructor(
    private val shared: ResticShared,
    private val rcloneServe: RcloneServe,
) {
    // initSftpRepository —— 对应 initFtpRepository
    suspend fun initSftpRepository(
        cloudEntity: CloudEntity, remotePath: String, password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, remotePath)
        try {
            Log.i("ResticSftpRoot", "initSftp restUrl=${session.restUrl} remotePath=$remotePath")

            // 1) 建库
            val initResult = shared.rootService.initRusticRepository(session.restUrl, password, emptyMap())
            if (initResult.isFailure) {
                return@withContext Result.failure(
                    Exception(initResult.exceptionOrNull()?.message ?: "Unknown error during rustic init")
                )
            }

            // 2) 复核：确认仓库 config 真的写进了 SFTP
            val exists = shared.rootService.rusticRepositoryExists(session.restUrl, emptyMap())
            if (!exists) {
                Log.e(ResticShared.TAG, "SFTP init 报成功但 repositoryExists=false")
                return@withContext Result.failure(
                    Exception("init 报成功但仓库 config 未写入 SFTP（疑似 rclone serve/认证失败）")
                )
            }

            Log.d(ResticShared.TAG, "SFTP repository initialized and verified")
            Result.success("SFTP repository initialized")
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "initSftpRepository 异常", e)
            Result.failure(e)
        } finally {
            stopServe(session)
        }
    }

    // backupFileToSftp —— 对应 backupFileToFtp，返回 Pair<Int,String>（保持上层契约）
    suspend fun backupFileToSftp(
        cloudEntity: CloudEntity, remotePath: String, filePath: String,
        tags: List<String>, password: String,
        progressCallback: ResticRepository.ResticProgressCallback? = null,
        cancelId: Long = 0L
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, remotePath)
        try {
            Log.i("ResticSftpRoot", "backupSftp restUrl=${session.restUrl} remotePath=$remotePath")

            val callback: ICallback? = progressCallback?.let { cb ->
                object : ICallback.Stub() {
                    // 5 参签名，与 ICallback.aidl 一致
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
                repositoryPath = session.restUrl,
                password = password,
                sourcePaths = listOf(filePath),
                tags = tags,
                options = emptyMap(),
                callback = callback,
                cancelId = cancelId
            )

            if (snapshotId.isNotBlank()) {
                Log.d(ResticShared.TAG, "backupFileToSftp 成功，snapshotId=$snapshotId")
                Pair(0, snapshotId)
            } else {
                Log.e(ResticShared.TAG, "backupFileToSftp 返回空快照 ID")
                Pair(1, "Rustic returned an empty snapshot ID")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            if (msg.contains("cancel", ignoreCase = true)) {
                Log.i("RusticCancel", "backupFileToSftp cancelled by user, cancelId=$cancelId, msg=$msg")
                Pair(1, "用户取消")
            } else {
                Log.e("RusticCancel", "backupFileToSftp failed, cancelId=$cancelId, msg=$msg")
                Pair(1, msg)
            }
        } finally {
            stopServe(session)
        }
    }

    // restoreSnapshotFromSftp —— 对应 restoreSnapshotFromFtp
    suspend fun restoreSnapshotFromSftp(
        cloudEntity: CloudEntity, password: String, snapshotId: String,
        targetPath: String, snapshotSubPath: String? = null,
        includePath: String? = null,
        progressCallback: ResticRepository.ResticProgressCallback? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            Log.i("ResticSftpRoot", "restoreSftp restUrl=${session.restUrl}")
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
                repositoryPath = session.restUrl, password = password,
                snapshotId = fullSnapshotId, destinationPath = targetPath,
                options = emptyMap(), includeGlob = includeGlob, callback = callback
            )
            result.isSuccess
        } catch (e: Exception) { false } finally {
            stopServe(session)
        }
    }

    // forgetSnapshotFromSftp —— 对应 forgetSnapshotFromFtp
    suspend fun forgetSnapshotFromSftp(
        cloudEntity: CloudEntity, password: String, snapshotId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            shared.rootService.forgetRusticSnapshot(session.restUrl, password, snapshotId, emptyMap()).isSuccess
        } catch (e: Exception) { false } finally {
            stopServe(session)
        }
    }

    // pruneSftpRepository —— 对应 pruneFtpRepository（--max-unused unlimited）
    suspend fun pruneSftpRepository(
        cloudEntity: CloudEntity, password: String
    ): Boolean = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            shared.rootService.pruneRusticRepository(session.restUrl, password, "unlimited", emptyMap()).isSuccess
        } catch (e: Exception) { false } finally {
            stopServe(session)
        }
    }

    // listBackedUpFilesFromSftpWithSqlJni —— 对应 listBackedUpFilesFromFtpWithSqlJni
    suspend fun listBackedUpFilesFromSftpWithSqlJni(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupFiles> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            val sqlDir = File(shared.context.cacheDir, "sql"); if (!sqlDir.exists()) sqlDir.mkdirs()
            val dbFile = File(sqlDir, "snapshots_files_sftp_${System.currentTimeMillis()}.db")
            val result = shared.rootService.listRusticSnapshotsDb(session.restUrl, password, dbFile.absolutePath, emptyMap())
            if (result.isFailure || !dbFile.exists() || dbFile.length() == 0L) { dbFile.delete(); return@withContext emptyList() }
            val files = shared.parseFilesDb(dbFile); dbFile.delete(); files
        } catch (e: Exception) { emptyList() } finally {
            stopServe(session)
        }
    }

    // listBackedUpAppsFromSftpWithSqlJni —— 对应 listBackedUpAppsFromFtpWithSqlJni
    suspend fun listBackedUpAppsFromSftpWithSqlJni(
        cloudEntity: CloudEntity,
        password: String
    ): List<ResticBackupApp> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            val repoPath = session.restUrl

            val sqlDir = File(shared.context.cacheDir, "sql")
            if (!sqlDir.exists()) sqlDir.mkdirs()

            val dbFile = File(sqlDir, "snapshots_sftp_${System.currentTimeMillis()}.db")

            Log.d(ResticShared.TAG, "执行 JNI listSnapshotsDb (SFTP)，repo=$repoPath，输出=${dbFile.absolutePath}")

            val result = shared.rootService.listRusticSnapshotsDb(
                repositoryPath = repoPath,
                password = password,
                dbPath = dbFile.absolutePath,
                options = emptyMap(),
            )
            if (result.isFailure) {
                Log.e(ResticShared.TAG, "listRusticSnapshotsDb (SFTP) 失败", result.exceptionOrNull())
                return@withContext emptyList()
            }
            if (!dbFile.exists() || dbFile.length() == 0L) {
                Log.e(ResticShared.TAG, "DB 文件未生成或为空 (SFTP)")
                return@withContext emptyList()
            }

            val apps = shared.parseAppsDb(dbFile)
            dbFile.delete()
            Log.d(ResticShared.TAG, "JNI 模式 (SFTP) 成功提取 ${apps.size} 个应用备份")
            apps
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "listBackedUpAppsFromSftpWithSqlJni 异常", e)
            emptyList()
        } finally {
            stopServe(session)
        }
    }

    /**
     * 起 librclone serve restic：把 SFTP 表单字段（host/port/user/pass + remotePath）交给
     * 接入层，由 rclone 在 serve 侧完成 SFTP + 密码认证，返回本地 rest: URL 与 serve id。
     * 端口为 localhost:0 动态分配，session 只在本次调用内有效。
     */
    private suspend fun startServe(cloudEntity: CloudEntity, remotePath: String): RcloneServe.Session =
        rcloneServe.start(cloudEntity, remotePath)

    /** 收尾：停掉本次 serve（传 id）。异常吞掉，避免影响主流程结果。 */
    private suspend fun stopServe(session: RcloneServe.Session) {
        try {
            rcloneServe.stop(session.id)
        } catch (e: Exception) {
            Log.w("ResticSftpRoot", "rcloneServeStop 失败 id=${session.id}", e)
        }
    }
}