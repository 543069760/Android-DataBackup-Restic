package com.xayah.core.service.medium.backup

import android.util.Log
import com.xayah.core.model.CloudType
import com.xayah.core.model.util.formatToStorageSizePerSecond
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.database.dao.UploadIdDao
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.MediumBackupUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.restic.CloudResticBackend
import com.xayah.core.restic.ResticRepository.ResticProgressCallback
import com.xayah.core.restic.ResticSnapshot
import com.xayah.core.model.DataType
import com.xayah.core.model.util.formatSize
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import java.io.File

@AndroidEntryPoint
internal class BackupServiceCloudImpl @Inject constructor() : AbstractBackupService() {
    override val mTAG: String = "BackupServiceCloudImpl"

    @Inject
    override lateinit var mRootService: RemoteRootService

    @Inject
    override lateinit var mPathUtil: PathUtil

    @Inject
    override lateinit var mCommonBackupUtil: CommonBackupUtil

    @Inject
    override lateinit var mTaskDao: TaskDao

    @Inject
    override lateinit var mTaskRepo: TaskRepository

    @Inject
    lateinit var resticBackends: Map<CloudType, @JvmSuppressWildcards CloudResticBackend>

    /** 按云类型取对应 restic 后端；找不到直接抛异常（fail-fast，替代原 else 兜底） */
    private fun backend(entity: CloudEntity): CloudResticBackend = resticBackends.getValue(entity.type)

    private val json = Json { ignoreUnknownKeys = true }

    override val mTaskEntity by lazy {
        TaskEntity(
            id = 0,
            opType = OpType.BACKUP,
            taskType = TaskType.MEDIA,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    override suspend fun onTargetDirsCreated() {
        mCloudRepo.getClient().also { (c, e) ->
            mCloudEntity = e
            mClient = c
        }

        mRemotePath = mCloudEntity.remote
        mRemoteFilesDir = mPathUtil.getCloudRemoteFilesDir(mRemotePath)
        mRemoteConfigsDir = mPathUtil.getCloudRemoteConfigsDir(mRemotePath)
        mTaskEntity.update(cloud = mCloudEntity.name, backupDir = mRemotePath)

        log { "Trying to create: $mRemoteFilesDir." }
        log { "Trying to create: $mRemoteConfigsDir." }
        mClient.mkdirRecursively(mRemoteFilesDir)
        mClient.mkdirRecursively(mRemoteConfigsDir)

        // 备份前一次性云端仓库可用性检查：不通过直接终止本次备份（与本地对称）
        if (!onPreBackupRepositoryCheck()) {
            throw IllegalStateException(mContext.getString(com.xayah.core.data.R.string.pre_backup_repository_check_failed))
        }
    }

    override suspend fun onPreBackupRepositoryCheck(): Boolean {
        val entity = mCloudEntity
        val backend = backend(entity)
        val password = backend.resolveResticPassword(entity)
        val ok = backend.checkRepository(entity, password).isSuccess
        if (!ok) {
            Log.e(mTAG, "备份前云端仓库检查失败: type=${entity.type} remote=${entity.remote}（仓库不存在/损坏/密码错/不可访问）")
        }
        return ok
    }

    private fun getRemoteFileDir(archivesRelativeDir: String) = "${mRemoteFilesDir}/${archivesRelativeDir}"

    override suspend fun onFileDirCreated(archivesRelativeDir: String): Boolean = runCatchingOnService {
        mClient.mkdirRecursively(getRemoteFileDir(archivesRelativeDir))
    }

    override suspend fun backup(m: MediaEntity, r: MediaEntity?, t: TaskDetailMediaEntity, dstDir: String) {
        try {
            // 在开始备份前检查取消标志
            if (isCanceled()) {
                Log.d(mTAG, "Backup canceled before processing ${m.name}")
                return
            }

            Log.d(mTAG, "Starting backup for ${m.name}")
            val remoteFileDir = getRemoteFileDir(m.archivesRelativeDir)

            val result = mMediumBackupUtil.backupMedia(
                m = m,
                t = t,
                r = r,
                dstDir = dstDir,
                isCanceled = { isCanceled() }
            )

            Log.d(mTAG, "Backup compression completed for ${m.name}, success: ${result.isSuccess}")

            // 压缩后再次检查取消标志
            if (isCanceled()) {
                Log.d(mTAG, "Backup canceled after compression for ${m.name}")
                return
            }

            if (result.isSuccess && t.mediaInfo.state != OperationState.SKIP) {
                // 查找压缩文件
                val compressedFile = findCompressedFile(dstDir)

                if (compressedFile != null) {
                    Log.d(mTAG, "Found compressed file: ${compressedFile.absolutePath}")

                    // 根据云存储类型选择备份方式
                    try {
                        when (mCloudEntity.type) {
                            CloudType.S3, CloudType.FTP, CloudType.WEBDAV, CloudType.SFTP, CloudType.AWSS3 -> {
                                Log.d(mTAG, "Using Restic backup for ${m.name}")
                                val mediaSuccess = backupFileWithResticByType(
                                    mediaName = m.name,
                                    compressedFile = compressedFile,
                                    dataType = DataType.PACKAGE_MEDIA,
                                    remotePath = "${m.archivesRelativeDir}",
                                    t = t
                                )
                                if (mediaSuccess) {
                                    Log.d(mTAG, "Restic backup successful for ${m.name}")
                                } else {
                                    Log.e(mTAG, "Restic backup failed for ${m.name}")
                                    t.update(state = OperationState.ERROR, log = "Restic备份失败")
                                    return
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e(mTAG, "Upload failed for ${m.name}", e)
                        t.update(state = OperationState.ERROR, log = e.message)
                        return
                    }
                } else {
                    Log.w(mTAG, "No compressed file found for ${m.name}")
                }
            }

            t.update(progress = 1f)
            t.update(processingIndex = t.processingIndex + 1)
            Log.d(mTAG, "Backup completed successfully for ${m.name}")

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(mTAG, "Backup failed for ${m.name}", e)
            t.update(state = OperationState.ERROR, log = e.message)
            throw e
        }
    }

    override suspend fun backupConfigToCloud(configFile: File, media: MediaEntity): Boolean {
        return try {
            val configSuccess = backupFileWithResticByType(
                mediaName = media.name,
                compressedFile = configFile,
                dataType = DataType.PACKAGE_CONFIG,
                remotePath = "${media.archivesRelativeDir}"
            )
            Log.d(mTAG, "云端config文件备份结果: $configSuccess")
            configSuccess
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(mTAG, "云端config文件备份异常", e)
            false
        }
    }

    /**
     * 统一的 Restic 文件备份实现：不再按云类型分派。
     * 通过 backend(mCloudEntity) 取对应后端（S3/COS、FTP、WEBDAV、SFTP），
     * 密码经各后端 resolveResticPassword 解析，备份逻辑对所有类型完全一致。
     */
    private suspend fun backupFileWithResticByType(
        mediaName: String,
        compressedFile: File,
        dataType: DataType,
        remotePath: String,
        t: TaskDetailMediaEntity? = null
    ): Boolean {
        return try {
            // 根据文件类型确定标签后缀
            val tagSuffix = when (dataType) {
                DataType.PACKAGE_MEDIA -> "filesbackup"
                DataType.PACKAGE_CONFIG -> "filesconfig"
                else -> "filesbackup"
            }

            // 文件备份标签格式:mediaName-timestamp-filesbackup/filesconfig
            val tag = "$mediaName-$mBackupTimestamp-$tagSuffix"
            val tags = listOf(tag)

            // 设置当前处理标签,用于取消机制
            Log.d("ResticTag", "Setting current tag: $tag")
            mCurrentProcessingTag = tag

            // 生成并记录本次备份的取消令牌 id
            val cancelId = System.nanoTime()
            mCurrentBackupCancelId = cancelId
            Log.i("RusticCancel", "backupFileWithRestic enter, media=$mediaName, tag=$tag, cancelId=$cancelId")

            val backend = backend(mCloudEntity)
            val password = backend.resolveResticPassword(mCloudEntity)
            val unifiedRepoPath = mCloudEntity.remote
            val backupStartAt = System.currentTimeMillis()
            val result = backend.backupFile(
                cloudEntity = mCloudEntity,
                remotePath = unifiedRepoPath,
                filePath = compressedFile.absolutePath,
                tags = tags,
                password = password,
                progressCallback = object : ResticProgressCallback {
                    override fun onBackupProgress(
                        percentDone: Float, bytesDone: Long,
                        bytesTotal: Long, filesDone: Long, filesTotal: Long,
                        speed: Long
                    ) {
                        val elapsedMs = (System.currentTimeMillis() - backupStartAt).coerceAtLeast(1L)
                        val avgSpeed = if (bytesDone > 0) bytesDone * 1000L / elapsedMs else 0L
                        val speedText = if (avgSpeed > 0) avgSpeed.formatToStorageSizePerSecond() else ""
                        val bytesText = bytesDone.toDouble().formatSize()
                        val content = if (speedText.isNotEmpty()) "$speedText | $bytesText" else bytesText
                        Log.d(mTAG, "Restic backup progress: $content")
                        runBlocking {
                            // 不再传 progress = percentDone(恒为 0),避免 UI 进度条卡 0%
                            t?.update(content = content)
                        }
                    }

                    override fun onRestoreProgress(
                        filesFinished: Long, filesTotal: Long,
                        bytesWritten: Long, bytesTotal: Long,
                        filesSkipped: Long, bytesSkipped: Long
                    ) {
                        // 备份时不使用
                    }
                },
                cancelId = cancelId
            )

            // 清除当前处理标签(正常完成)
            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L

            if (result.first == 0) {
                val snapshotId = extractSnapshotIdFromJson(result.second)
                if (snapshotId != null) {
                    Log.d(mTAG, "Restic backup successful for $mediaName, snapshotId: $snapshotId")
                    updateCloudResticInfo(mediaName, snapshotId, remotePath)
                }
                true
            } else {
                Log.i("RusticCancel", "backupFileWithRestic non-zero result, media=$mediaName, code=${result.first}, msg=${result.second}")
                false
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // 异常时也要清除标签
            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L

            Log.e("RusticCancel", "backupFileWithRestic failed/cancelled, media=$mediaName, msg=${e.message}")
            Log.e(mTAG, "Error during file Restic backup", e)
            false
        }
    }

    // 辅助方法：查找压缩文件
    private fun findCompressedFile(dstDir: String): File? {
        return File("$dstDir/media.tar").takeIf { it.exists() }
    }

    // 辅助方法：查找配置文件
    private fun findConfigFile(dstDir: String): File? {
        return File("$dstDir/media_restore_config.json").takeIf { it.exists() }
    }

    /**
     * 从 JSON 输出中提取快照 ID
     */
    private fun extractSnapshotIdFromJson(jsonOutput: String): String? {
        return try {
            json.decodeFromString<ResticSnapshot>(jsonOutput).id
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun updateCloudResticInfo(mediaName: String, snapshotId: String, repoPath: String) {
        Log.d(mTAG, "Updating cloud Restic info for $mediaName: snapshotId=$snapshotId")
    }

    override suspend fun onConfigSaved(path: String, archivesRelativeDir: String) {
        // config 已作为 PACKAGE_CONFIG rustic 快照备份，无需再经 AWS SDK 单独上传对象
        Log.d(mTAG, "onConfigSaved: skip object upload; config is backed up as PACKAGE_CONFIG restic snapshot")
    }

    override suspend fun onCleanupFailedBackup(archivesRelativeDir: String) {
        val remoteFileDir = getRemoteFileDir(archivesRelativeDir)
        Log.d(mTAG, "S3 Restic backup failed at: $remoteFileDir")
        Log.d(mTAG, "No cleanup needed - Restic manages block storage automatically")
    }

    override suspend fun onCleanupIncompleteBackup(currentIndex: Int) {
        Log.d(mTAG, "S3 Restic backup incomplete - no cleanup needed")
        Log.d(mTAG, "Restic will handle partial blocks during restore")
    }

    override suspend fun onItselfSaved(path: String, entity: ProcessingInfoEntity) {
        entity.update(state = OperationState.UPLOADING)
        var flag = true
        var progress = 0f
        var speed = 0L
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()

        with(CoroutineScope(coroutineContext)) {
            launch {
                while (flag) {
                    val speedText = if (speed > 0) speed.formatToStorageSizePerSecond() else ""
                    val content = if (speedText.isNotEmpty()) {
                        "$speedText | ${(progress * 100).toInt()}%"
                    } else {
                        "${(progress * 100).toInt()}%"
                    }
                    entity.update(content = content)
                    delay(500)
                }
            }
        }

        mCloudRepo.upload(
            client = mClient,
            src = path,
            dstDir = mRemoteConfigsDir,
            onUploading = { read, total ->
                progress = read.toFloat() / total
                val currentTime = System.currentTimeMillis()
                val timeDiff = currentTime - lastTime
                if (timeDiff >= 500) {
                    val bytesDiff = read - lastBytes
                    speed = if (timeDiff > 0) (bytesDiff * 1000 / timeDiff) else 0L
                    lastTime = currentTime
                    lastBytes = read
                }
            },
            isCanceled = { isCanceled() }
        ).apply {
            flag = false
            entity.update(
                state = if (isSuccess) OperationState.DONE else OperationState.ERROR,
                log = if (isSuccess) null else outString,
                content = "100%"
            )
        }
    }

    override suspend fun clear() {
        mRootService.deleteRecursively(mRootDir)
        mClient.disconnect()
        cleanupStopFiles()
    }

    @Inject
    override lateinit var mMediaDao: MediaDao

    @Inject
    override lateinit var mMediaRepo: MediaRepository

    @Inject
    override lateinit var mMediumBackupUtil: MediumBackupUtil

    override val mRootDir by lazy { mContext.localBackupSaveDir() }
    override val mFilesDir by lazy { mPathUtil.getLocalBackupFilesDir() }
    override val mConfigsDir by lazy { mPathUtil.getLocalBackupConfigsDir() }

    @Inject
    lateinit var mCloudRepo: CloudRepository

    @Inject
    lateinit var mUploadIdDao: UploadIdDao

    private lateinit var mCloudEntity: CloudEntity
    private lateinit var mClient: CloudClient
    private lateinit var mRemotePath: String
    private lateinit var mRemoteFilesDir: String
    private lateinit var mRemoteConfigsDir: String
}