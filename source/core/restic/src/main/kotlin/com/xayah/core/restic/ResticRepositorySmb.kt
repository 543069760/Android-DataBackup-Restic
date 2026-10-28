package com.xayah.core.restic

import android.util.Log
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.SMBExtra
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.rootservice.ICallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import java.io.File
import com.xayah.core.util.toFriendlyMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SMB 备份的实现，基于 librclone serve restic（结构对照 ResticRepositorySftp/ResticRepositoryFtp）。
 *
 * 与 FTP/SFTP 一致的三点设计：
 *  1) repositoryPath 使用 rclone serve restic 起服务后返回的 rest: URL（session.restUrl），
 *     而不是直接的 smb:// 路径；rclone 在 serve 侧完成 SMB 协议封装。
 *  2) options 一律传 emptyMap()——SMB 的 host/port/user/pass/share/domain 认证由 rclone 在 serve 侧完成，
 *     rustic 侧只当作普通 REST 后端。
 *  3) 每个方法都用 serve 生命周期包住：start() 起服务拿端口，finally { stop(id) } 收尾。
 *     serve 必须在整批 JNI 调用期间一直存活，端口为 localhost:0 动态分配、不可跨会话复用。
 *
 * 说明：restic 仓库密码单独作为 password 参数传 JNI（与 FTP/SFTP 一致）。
 */
@Singleton
class ResticRepositorySmb @Inject constructor(
    private val shared: ResticShared,
    private val rcloneServe: RcloneServe,
) : CloudResticBackend {

    // ==================== CloudResticBackend 统一接口实现 ====================

    override suspend fun initRepository(
        cloudEntity: CloudEntity, remotePath: String, password: String
    ): Result<String> = initSmbRepository(cloudEntity, remotePath, password)

    override suspend fun resolveResticPassword(cloudEntity: CloudEntity): String {
        val extra = ResticShared.json.decodeFromString<SMBExtra>(cloudEntity.extra)
        return extra.resticPassword.ifEmpty { shared.context.readResticPassword() ?: "" }
    }

    override suspend fun checkRepository(
        cloudEntity: CloudEntity, password: String
    ): Result<Unit> = checkSmbRepository(cloudEntity, password)

    override suspend fun backupFile(
        cloudEntity: CloudEntity, remotePath: String, filePath: String,
        tags: List<String>, password: String,
        progressCallback: ResticRepository.ResticProgressCallback?,
        cancelId: Long
    ): Pair<Int, String> = backupFileToSmb(
        cloudEntity, remotePath, filePath, tags, password, progressCallback, cancelId
    )

    override suspend fun <T> withStreamBackupContext(
        cloudEntity: CloudEntity,
        remotePath: String,
        block: suspend (repoPath: String, options: Map<String, String>) -> T
    ): T = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, remotePath)
        Log.i("ResticSmbRoot", "withStreamBackupContext restUrl=${session.restUrl} remotePath=$remotePath")
        try {
            // serve 必须包住整个 block：block 内含 streamBackupToRustic，其 FATAL 失败路径
            // 会经 rest: URL（session.restUrl）forget 空壳快照，forget 发生时 serve 必须仍存活。
            block(session.restUrl, emptyMap())
        } finally {
            stopServe(session)
        }
    }

    override suspend fun listSnapshots(
        cloudEntity: CloudEntity, password: String
    ): List<ResticSnapshot> = listSnapshotsFromSmb(cloudEntity, password)

    override suspend fun restoreSnapshot(
        cloudEntity: CloudEntity, password: String, snapshotId: String,
        targetPath: String, snapshotSubPath: String?,
        includePath: String?,
        progressCallback: ResticRepository.ResticProgressCallback?
    ): Boolean = restoreSnapshotFromSmb(
        cloudEntity, password, snapshotId, targetPath, snapshotSubPath, includePath, progressCallback
    )

    override suspend fun forgetSnapshot(
        cloudEntity: CloudEntity, password: String, snapshotId: String
    ): Boolean = forgetSnapshotFromSmb(cloudEntity, password, snapshotId)

    override suspend fun pruneRepository(
        cloudEntity: CloudEntity, password: String
    ): Boolean = pruneSmbRepository(cloudEntity, password)

    override suspend fun listBackedUpFiles(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupFiles> = listBackedUpFilesFromSmbWithSqlJni(cloudEntity, password)

    // ==================== 以下为原有旧名方法（方法体保持不变） ====================

    suspend fun initSmbRepository(
        cloudEntity: CloudEntity, remotePath: String, password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, remotePath)
        try {
            Log.i("ResticSmbRoot", "initSmb restUrl=${session.restUrl} remotePath=$remotePath")

            // 1) 建库
            val initResult = shared.rootService.initRusticRepository(session.restUrl, password, emptyMap())
            if (initResult.isFailure) {
                return@withContext Result.failure(
                    Exception(initResult.exceptionOrNull()?.message ?: "Unknown error during rustic init")
                )
            }

            // 2) 复核：确认仓库 config 真的写进了 SMB
            val exists = shared.rootService.rusticRepositoryExists(session.restUrl, emptyMap())
            if (!exists) {
                Log.e(ResticShared.TAG, "SMB init 报成功但 repositoryExists=false")
                return@withContext Result.failure(
                    Exception("init 报成功但仓库 config 未写入 SMB（疑似 rclone serve/认证失败）")
                )
            }

            Log.d(ResticShared.TAG, "SMB repository initialized and verified")
            Result.success("SMB repository initialized")
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "initSmbRepository 异常", e)
            Result.failure(e)
        } finally {
            stopServe(session)
        }
    }

    suspend fun checkSmbRepository(
        cloudEntity: CloudEntity, password: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            val exists = shared.rootService.rusticRepositoryExists(session.restUrl, emptyMap())
            if (!exists) return@withContext Result.failure(Exception("仓库不存在或不可访问"))

            val validate = shared.rootService.validateRusticRepository(session.restUrl, password, emptyMap())
            if (validate.isFailure) return@withContext Result.failure(validate.exceptionOrNull() ?: Exception("仓库密码错误或无法打开"))

            val check = shared.rootService.checkRusticRepository(session.restUrl, password, emptyMap())
            if (check.isFailure) return@withContext Result.failure(check.exceptionOrNull() ?: Exception("仓库损坏"))

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "checkSmbRepository 异常", e); Result.failure(e)
        } finally { stopServe(session) }
    }

    suspend fun backupFileToSmb(
        cloudEntity: CloudEntity, remotePath: String, filePath: String,
        tags: List<String>, password: String,
        progressCallback: ResticRepository.ResticProgressCallback? = null,
        cancelId: Long = 0L
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, remotePath)

        val polling = AtomicBoolean(true)
        var pollJob: Job? = null

        try {
            Log.i("ResticSmbRoot", "backupSmb restUrl=${session.restUrl} remotePath=$remotePath")

            rcloneServe.resetStats()

            if (progressCallback != null) {
                pollJob = CoroutineScope(coroutineContext).launch {
                    while (polling.get() && isActive) {
                        val (bytes, speed) = rcloneServe.readStatsBytesAndSpeed()
                        Log.d("SmbStatsPoll", "poll bytes=$bytes speed=$speed")
                        progressCallback.onBackupProgress(
                            percentDone = 0f,
                            bytesDone   = bytes,
                            bytesTotal  = 0L,
                            filesDone   = 0L,
                            filesTotal  = 0L,
                            speed       = speed
                        )
                        delay(300L)
                    }
                }
            }

            val callback: ICallback? = progressCallback?.let {
                object : ICallback.Stub() {
                    override fun onProgress(
                        readBytes: Long, readTotal: Long, readProgress: Float,
                        writtenBytes: Long, writtenSpeed: Long
                    ) {
                        // no-op：进度显示改由 core/stats 轮询驱动
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
                Log.d(ResticShared.TAG, "backupFileToSmb 成功，snapshotId=$snapshotId")
                Pair(0, snapshotId)
            } else {
                Log.e(ResticShared.TAG, "backupFileToSmb 返回空快照 ID")
                Pair(1, "Rustic returned an empty snapshot ID")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            if (msg.contains("cancel", ignoreCase = true)) {
                Log.i("RusticCancel", "backupFileToSmb cancelled by user, cancelId=$cancelId, msg=$msg")
                Pair(1, "用户取消")
            } else {
                Log.e("RusticCancel", "backupFileToSmb failed, cancelId=$cancelId, msg=$msg")
                Pair(1, e.toFriendlyMessage(shared.context))
            }
        } finally {
            polling.set(false)
            runCatching { pollJob?.cancelAndJoin() }
            stopServe(session)
        }
    }

    suspend fun listSnapshotsFromSmb(
        cloudEntity: CloudEntity, password: String
    ): List<ResticSnapshot> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            val sqlDir = File(shared.context.cacheDir, "sql"); if (!sqlDir.exists()) sqlDir.mkdirs()
            val dbFile = File(sqlDir, "snapshots_icons_smb_${System.currentTimeMillis()}.db")
            val result = shared.rootService.listRusticSnapshotsDb(session.restUrl, password, dbFile.absolutePath, emptyMap())
            if (result.isFailure || !dbFile.exists() || dbFile.length() == 0L) { dbFile.delete(); return@withContext emptyList() }
            val snapshots = shared.parseSnapshotsDb(dbFile); dbFile.delete(); snapshots
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "listSnapshotsFromSmb 异常", e); emptyList()
        } finally { stopServe(session) }
    }

    suspend fun restoreSnapshotFromSmb(
        cloudEntity: CloudEntity, password: String, snapshotId: String,
        targetPath: String, snapshotSubPath: String? = null,
        includePath: String? = null,
        progressCallback: ResticRepository.ResticProgressCallback? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            Log.i("ResticSmbRoot", "restoreSmb restUrl=${session.restUrl}")
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
                repositoryPath = session.restUrl, password = password,
                snapshotId = fullSnapshotId, destinationPath = targetPath,
                options = emptyMap(), includeGlob = includeGlob, callback = callback
            )
            result.isSuccess
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "restoreSnapshotFromSmb 异常", e)
            false
        } finally {
            stopServe(session)
        }
    }

    suspend fun forgetSnapshotFromSmb(
        cloudEntity: CloudEntity, password: String, snapshotId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            Log.i("ResticSmbRoot", "forgetSmb restUrl=${session.restUrl} snapshotId=$snapshotId")
            shared.rootService.forgetRusticSnapshot(session.restUrl, password, snapshotId, emptyMap()).isSuccess
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "forgetSnapshotFromSmb 异常", e)
            false
        } finally {
            stopServe(session)
        }
    }

    suspend fun pruneSmbRepository(
        cloudEntity: CloudEntity, password: String
    ): Boolean = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            Log.i("ResticSmbRoot", "pruneSmb restUrl=${session.restUrl}")
            shared.rootService.pruneRusticRepository(session.restUrl, password, "unlimited", emptyMap(), instantDelete = true).isSuccess
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "pruneSmbRepository 异常", e)
            false
        } finally {
            stopServe(session)
        }
    }

    suspend fun listBackedUpFilesFromSmbWithSqlJni(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupFiles> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            Log.i("ResticSmbRoot", "listFilesSmb restUrl=${session.restUrl}")
            val sqlDir = File(shared.context.cacheDir, "sql"); if (!sqlDir.exists()) sqlDir.mkdirs()
            val dbFile = File(sqlDir, "snapshots_files_smb_${System.currentTimeMillis()}.db")
            val result = shared.rootService.listRusticSnapshotsDb(session.restUrl, password, dbFile.absolutePath, emptyMap())
            if (result.isFailure || !dbFile.exists() || dbFile.length() == 0L) { dbFile.delete(); return@withContext emptyList() }
            val files = shared.parseFilesDb(dbFile); dbFile.delete(); files
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "listBackedUpFilesFromSmbWithSqlJni 异常", e)
            emptyList()
        } finally {
            stopServe(session)
        }
    }

    override suspend fun readCachedApps(cloudEntity: CloudEntity): List<ResticBackupApp> =
        shared.readCachedApps(cloudEntity.name)

    override suspend fun refreshAndListApps(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupApp> = withContext(Dispatchers.IO) {
        val session = startServe(cloudEntity, cloudEntity.remote)
        try {
            Log.d(ResticShared.TAG, "refreshAndListApps (SMB) restUrl=${session.restUrl}")
            shared.refreshAppsDb(
                accountId = cloudEntity.name,
                repoPath = session.restUrl,
                password = password,
                options = emptyMap(),
            )
        } catch (e: Exception) {
            Log.e(ResticShared.TAG, "refreshAndListApps (SMB) 异常", e)
            emptyList()
        } finally {
            stopServe(session)
        }
    }

    suspend fun listBackedUpAppsFromSmbWithSqlJni(
        cloudEntity: CloudEntity, password: String
    ): List<ResticBackupApp> = refreshAndListApps(cloudEntity, password)

    /**
     * 起 librclone serve restic：把 SMB 表单字段（host/port/user/pass/share/domain + remotePath）交给
     * 接入层，由 rclone 在 serve 侧完成 SMB + 密码认证，返回本地 rest: URL 与 serve id。
     * 端口为 localhost:0 动态分配，session 只在本次调用内有效。
     */
    private suspend fun startServe(cloudEntity: CloudEntity, remotePath: String): RcloneServe.Session =
        rcloneServe.start(cloudEntity, remotePath)

    /** 收尾：停掉本次 serve（传 id）。异常吞掉，避免影响主流程结果。 */
    private suspend fun stopServe(session: RcloneServe.Session) {
        try {
            rcloneServe.stop(session.id)
        } catch (e: Exception) {
            Log.w("ResticSmbRoot", "rcloneServeStop 失败 id=${session.id}", e)
        }
    }
}
