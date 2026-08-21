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
import com.xayah.core.network.client.S3ClientImpl
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.MediumBackupUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.restic.ResticRepository
import com.xayah.core.restic.ResticRepositoryCos
import com.xayah.core.restic.ResticRepository.ResticProgressCallback
import com.xayah.core.restic.ResticSnapshot
import com.xayah.core.datastore.readS3ResticRepoPath
import com.xayah.core.datastore.readS3ResticPassword
import com.xayah.core.model.DataType
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.util.formatSize
import com.xayah.core.restic.ResticRepositoryFtp
import com.xayah.core.model.database.FTPExtra
import com.xayah.core.datastore.readFtpResticPassword
import com.xayah.core.restic.ResticRepositoryWebdav
import com.xayah.core.model.database.WebDAVExtra
import com.xayah.core.datastore.readWebdavResticPassword
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
    lateinit var resticRepoCos: ResticRepositoryCos

    @Inject
    lateinit var resticRepoFtp: ResticRepositoryFtp

    @Inject
    lateinit var resticRepoWebdav: ResticRepositoryWebdav

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
                            CloudType.S3, CloudType.FTP, CloudType.WEBDAV -> {
                                Log.d(mTAG, "Using Restic backup for ${m.name}")
                                // 只备份媒体文件
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
                            CloudType.SFTP -> {
                                Log.d(mTAG, "Using SFTP upload for ${m.name}")
                                mMediumBackupUtil.upload(
                                    client = mClient,
                                    m = m,
                                    t = t,
                                    srcDir = dstDir,
                                    dstDir = remoteFileDir,
                                    isCanceled = { isCanceled() }
                                )
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
     * 按云类型分派到对应的 Restic 备份实现。
     * 目前仅收敛走 restic 的 S3 与 FTP：FTP 走 opendal:ftp，其余（默认）走 COS/S3。
     * SFTP/WEBDAV/SMB 尚未迁移到 restic，仍在 backup() 的 when 分支各自走 upload，
     * 待后续迁移到 opendal 后再并入本方法。
     */
    private suspend fun backupFileWithResticByType(
        mediaName: String,
        compressedFile: File,
        dataType: DataType,
        remotePath: String,
        t: TaskDetailMediaEntity? = null
    ): Boolean {
        return when (mCloudEntity.type) {
            CloudType.FTP -> {
                val ftpExtra = json.decodeFromString<FTPExtra>(mCloudEntity.extra)
                backupFileWithResticToFtp(
                    mediaName = mediaName,
                    compressedFile = compressedFile,
                    dataType = dataType,
                    ftpExtra = ftpExtra,
                    remotePath = remotePath,
                    t = t
                )
            }
            CloudType.WEBDAV -> {
                val webdavExtra = json.decodeFromString<WebDAVExtra>(mCloudEntity.extra)
                backupFileWithResticToWebdav(
                    mediaName = mediaName,
                    compressedFile = compressedFile,
                    dataType = dataType,
                    webdavExtra = webdavExtra,
                    remotePath = remotePath,
                    t = t
                )
            }
            else -> {
                val s3Extra = json.decodeFromString<S3Extra>(mCloudEntity.extra)
                backupFileWithResticToS3(
                    mediaName = mediaName,
                    compressedFile = compressedFile,
                    dataType = dataType,
                    s3Extra = s3Extra,
                    remotePath = remotePath,
                    t = t
                )
            }
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
     * 使用 Restic 备份文件到 S3
     */
    private suspend fun backupFileWithResticToS3(
        mediaName: String,
        compressedFile: File,
        dataType: DataType,
        s3Extra: S3Extra,
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

            // 新的文件备份标签格式:mediaName-timestamp-filesbackup/filesconfig
            val tag = "$mediaName-$mBackupTimestamp-$tagSuffix"
            val tags = listOf(tag)

            // 【新增】设置当前处理标签,用于取消机制
            Log.d("ResticTag", "Setting current tag: $tag")
            mCurrentProcessingTag = tag

            // 【取消】生成并记录本次备份的取消令牌 id
            val cancelId = System.nanoTime()
            mCurrentBackupCancelId = cancelId
            Log.i("RusticCancel", "backupFileWithResticToS3 enter, media=$mediaName, tag=$tag, cancelId=$cancelId")

            val unifiedRepoPath = mCloudEntity.remote

            val result = resticRepoCos.backupFileToCos(
                extra = s3Extra,
                remotePath = unifiedRepoPath,
                filePath = compressedFile.absolutePath,
                tags = tags,
                password = s3Extra.resticPassword.ifEmpty { mContext.readS3ResticPassword() ?: getResticPassword() },
                progressCallback = object : ResticProgressCallback {
                    override fun onBackupProgress(
                        percentDone: Float, bytesDone: Long,
                        bytesTotal: Long, filesDone: Long, filesTotal: Long,
                        speed: Long
                    ) {
                        val speedText = if (speed > 0) speed.formatToStorageSizePerSecond() else ""
                        val bytesText = bytesDone.toDouble().formatSize()
                        val content = if (speedText.isNotEmpty()) "$speedText | $bytesText" else bytesText
                        Log.d(mTAG, "Restic S3 backup progress: $content")
                        runBlocking {
                            // 不再传 progress = percentDone(恒为 0),避免 UI 进度条卡 0%
                            t?.update(content = "$speedText | $bytesText")
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

            // 【新增】清除当前处理标签(正常完成)
            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L

            if (result.first == 0) {
                val snapshotId = extractSnapshotIdFromJson(result.second)
                if (snapshotId != null) {
                    Log.d(mTAG, "Restic S3 backup successful for $mediaName, snapshotId: $snapshotId")
                    updateCloudResticInfo(mediaName, snapshotId, remotePath)
                }
                true
            } else {
                Log.i("RusticCancel", "backupFileWithResticToS3 non-zero result, media=$mediaName, code=${result.first}, msg=${result.second}")
                false
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // 【新增】异常时也要清除标签
            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L

            Log.e("RusticCancel", "backupFileWithResticToS3 failed/cancelled, media=$mediaName, msg=${e.message}")
            Log.e(mTAG, "Error during S3 file Restic backup", e)
            false
        }
    }

    /**
     * 使用 Restic 备份文件到 FTP（opendal:ftp，纯 JNI）
     */
    private suspend fun backupFileWithResticToFtp(
        mediaName: String,
        compressedFile: File,
        dataType: DataType,
        ftpExtra: FTPExtra,
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

            Log.d("ResticTag", "Setting current tag: $tag")
            mCurrentProcessingTag = tag

            val cancelId = System.nanoTime()
            mCurrentBackupCancelId = cancelId
            Log.i("RusticCancel", "backupFileWithResticToFtp enter, media=$mediaName, tag=$tag, cancelId=$cancelId")

            val unifiedRepoPath = mCloudEntity.remote

            val result = resticRepoFtp.backupFileToFtp(
                cloudEntity = mCloudEntity,
                remotePath = unifiedRepoPath,
                filePath = compressedFile.absolutePath,
                tags = tags,
                password = ftpExtra.resticPassword.ifEmpty { mContext.readFtpResticPassword() ?: getResticPassword() },
                progressCallback = object : ResticProgressCallback {
                    override fun onBackupProgress(
                        percentDone: Float, bytesDone: Long,
                        bytesTotal: Long, filesDone: Long, filesTotal: Long,
                        speed: Long
                    ) {
                        val speedText = if (speed > 0) speed.formatToStorageSizePerSecond() else ""
                        val bytesText = bytesDone.toDouble().formatSize()
                        val content = if (speedText.isNotEmpty()) "$speedText | $bytesText" else bytesText
                        Log.d(mTAG, "Restic FTP backup progress: $content")
                        runBlocking {
                            t?.update(content = "$speedText | $bytesText")
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

            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L

            if (result.first == 0) {
                val snapshotId = extractSnapshotIdFromJson(result.second)
                if (snapshotId != null) {
                    Log.d(mTAG, "Restic FTP backup successful for $mediaName, snapshotId: $snapshotId")
                    updateCloudResticInfo(mediaName, snapshotId, remotePath)
                }
                true
            } else {
                Log.i("RusticCancel", "backupFileWithResticToFtp non-zero result, media=$mediaName, code=${result.first}, msg=${result.second}")
                false
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L

            Log.e("RusticCancel", "backupFileWithResticToFtp failed/cancelled, media=$mediaName, msg=${e.message}")
            Log.e(mTAG, "Error during FTP file Restic backup", e)
            false
        }
    }

    /**
     * 使用 Restic 备份文件到 WebDAV（opendal:webdav，纯 JNI）
     */
    private suspend fun backupFileWithResticToWebdav(
        mediaName: String,
        compressedFile: File,
        dataType: DataType,
        webdavExtra: WebDAVExtra,
        remotePath: String,
        t: TaskDetailMediaEntity? = null
    ): Boolean {
        return try {
            val tagSuffix = when (dataType) {
                DataType.PACKAGE_MEDIA -> "filesbackup"
                DataType.PACKAGE_CONFIG -> "filesconfig"
                else -> "filesbackup"
            }

            val tag = "$mediaName-$mBackupTimestamp-$tagSuffix"
            val tags = listOf(tag)

            Log.d("ResticTag", "Setting current tag: $tag")
            mCurrentProcessingTag = tag

            val cancelId = System.nanoTime()
            mCurrentBackupCancelId = cancelId
            Log.i("RusticCancel", "backupFileWithResticToWebdav enter, media=$mediaName, tag=$tag, cancelId=$cancelId")

            val unifiedRepoPath = mCloudEntity.remote

            val result = resticRepoWebdav.backupFileToWebdav(
                cloudEntity = mCloudEntity,
                remotePath = unifiedRepoPath,
                filePath = compressedFile.absolutePath,
                tags = tags,
                password = webdavExtra.resticPassword.ifEmpty { mContext.readWebdavResticPassword() ?: getResticPassword() },
                progressCallback = object : ResticProgressCallback {
                    override fun onBackupProgress(
                        percentDone: Float, bytesDone: Long,
                        bytesTotal: Long, filesDone: Long, filesTotal: Long,
                        speed: Long
                    ) {
                        val speedText = if (speed > 0) speed.formatToStorageSizePerSecond() else ""
                        val bytesText = bytesDone.toDouble().formatSize()
                        val content = if (speedText.isNotEmpty()) "$speedText | $bytesText" else bytesText
                        Log.d(mTAG, "Restic WebDAV backup progress: $content")
                        runBlocking {
                            t?.update(content = "$speedText | $bytesText")
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

            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L

            if (result.first == 0) {
                val snapshotId = extractSnapshotIdFromJson(result.second)
                if (snapshotId != null) {
                    Log.d(mTAG, "Restic WebDAV backup successful for $mediaName, snapshotId: $snapshotId")
                    updateCloudResticInfo(mediaName, snapshotId, remotePath)
                }
                true
            } else {
                Log.i("RusticCancel", "backupFileWithResticToWebdav non-zero result, media=$mediaName, code=${result.first}, msg=${result.second}")
                false
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L

            Log.e("RusticCancel", "backupFileWithResticToWebdav failed/cancelled, media=$mediaName, msg=${e.message}")
            Log.e(mTAG, "Error during WebDAV file Restic backup", e)
            false
        }
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
        mCloudRepo.upload(
            client = mClient,
            src = path,
            dstDir = getRemoteFileDir(archivesRelativeDir),
            onUploading = { _, _ -> },
            isCanceled = { isCanceled() }
        )
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