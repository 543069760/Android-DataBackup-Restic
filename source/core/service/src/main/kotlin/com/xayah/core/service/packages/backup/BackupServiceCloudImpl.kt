package com.xayah.core.service.packages.backup

import android.util.Log
import com.xayah.core.restic.ResticRepository
import com.xayah.core.restic.ResticRepositoryCos
import com.xayah.core.restic.ResticRepository.ResticProgressCallback
import com.xayah.core.restic.ResticSnapshot
import com.xayah.core.model.util.formatToStorageSizePerSecond
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readS3ResticRepoPath
import com.xayah.core.datastore.readS3ResticPassword
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.CloudType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.util.get
import com.xayah.core.model.util.formatToStorageSizePerSecond
import com.xayah.core.model.util.formatSize
import com.xayah.core.network.client.CloudClient
import com.xayah.core.network.client.S3ClientImpl
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.restic.ResticRepositoryFtp
import com.xayah.core.model.database.FTPExtra
import com.xayah.core.datastore.readFtpResticPassword
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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

    override val mTaskEntity by lazy {
        TaskEntity(
            id = 0,
            opType = OpType.BACKUP,
            taskType = TaskType.PACKAGE,
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
        mRemoteAppsDir = mPathUtil.getCloudRemoteAppsDir(mRemotePath)
        mRemoteConfigsDir = mPathUtil.getCloudRemoteConfigsDir(mRemotePath)
        mTaskEntity.update(cloud = mCloudEntity.name, backupDir = mRemotePath)

        Log.d(mTAG, "Trying to create: $mRemoteAppsDir.")
        Log.d(mTAG, "Trying to create: $mRemoteConfigsDir.")
        mClient.mkdirRecursively(mRemoteAppsDir)
        mClient.mkdirRecursively(mRemoteConfigsDir)
    }

    private fun getRemoteAppDir(archivesRelativeDir: String) = "${mRemoteAppsDir}/${archivesRelativeDir}"

    override suspend fun onAppDirCreated(archivesRelativeDir: String): Boolean = runCatchingOnService {
        mClient.mkdirRecursively(getRemoteAppDir(archivesRelativeDir))
    }

    override suspend fun backup(type: DataType, p: PackageEntity, r: PackageEntity?, t: TaskDetailPackageEntity, dstDir: String) {
        try {
            // 在开始备份前检查取消标志
            if (isCanceled()) {
                Log.d(mTAG, "Backup canceled before processing $type")
                return
            }

            Log.d(mTAG, "Starting backup for ${p.packageName}, type: $type")
            val remoteAppDir = getRemoteAppDir(p.archivesRelativeDir)

            val result = if (type == DataType.PACKAGE_APK) {
                Log.d(mTAG, "Backing up APK for ${p.packageName}")
                try {
                    mPackagesBackupUtil.backupApk(
                        p = p,
                        t = t,
                        r = r,
                        dstDir = dstDir,
                        isCanceled = { isCanceled() }
                    )
                } catch (e: Exception) {
                    Log.e(mTAG, "APK backup failed for ${p.packageName}", e)
                    throw e
                }
            } else {
                Log.d(mTAG, "Backing up data for ${p.packageName}, type: $type")
                try {
                    mPackagesBackupUtil.backupData(
                        p = p,
                        t = t,
                        r = r,
                        dataType = type,
                        dstDir = dstDir,
                        isCanceled = { isCanceled() }
                    )
                } catch (e: Exception) {
                    Log.e(mTAG, "Data backup failed for ${p.packageName}, type: $type", e)
                    throw e
                }
            }

            Log.d(mTAG, "Backup compression completed for ${p.packageName}, success: ${result.isSuccess}")

            // 压缩后再次检查取消标志
            if (isCanceled()) {
                Log.d(mTAG, "Backup canceled after compression for $type")
                return
            }

            if (result.isSuccess && t.get(type).state != OperationState.SKIP) {
                // 查找压缩文件
                val compressedFile = findCompressedFile(dstDir, type)
                if (compressedFile != null) {
                    Log.d(mTAG, "Found compressed file: ${compressedFile.absolutePath}")

                    // 根据云存储类型选择备份方式
                    try {
                        when (mCloudEntity.type) {
                            CloudType.S3 -> {
                                Log.d(mTAG, "Using Restic S3 backup for ${p.packageName}")
                                val s3Extra = json.decodeFromString<S3Extra>(mCloudEntity.extra)
                                val resticSuccess = backupWithResticToS3(
                                    packageName = p.packageName,
                                    compressedFile = compressedFile,
                                    dataType = type,
                                    s3Extra = s3Extra,
                                    remotePath = "${p.archivesRelativeDir}",
                                    t = t
                                )
                                if (resticSuccess) {
                                    Log.d(mTAG, "Restic S3 backup successful for ${p.packageName} $type")
                                } else {
                                    Log.e(mTAG, "Restic S3 backup failed for ${p.packageName} $type")
                                }
                            }
                            CloudType.FTP -> {
                                Log.d(mTAG, "Using Restic FTP backup for ${p.packageName}")
                                val ftpExtra = json.decodeFromString<FTPExtra>(mCloudEntity.extra)
                                val resticSuccess = backupWithResticToFtp(
                                    packageName = p.packageName,
                                    compressedFile = compressedFile,
                                    dataType = type,
                                    ftpExtra = ftpExtra,
                                    remotePath = "${p.archivesRelativeDir}",
                                    t = t
                                )
                                if (resticSuccess) {
                                    Log.d(mTAG, "Restic FTP backup successful for ${p.packageName} $type")
                                } else {
                                    Log.e(mTAG, "Restic FTP backup failed for ${p.packageName} $type")
                                }
                            }
                            CloudType.SFTP -> {
                                Log.d(mTAG, "Using SFTP upload for ${p.packageName}")
                                mPackagesBackupUtil.upload(
                                    client = mClient,
                                    p = p,
                                    t = t,
                                    dataType = type,
                                    srcDir = dstDir,
                                    dstDir = remoteAppDir,
                                    isCanceled = { isCanceled() }
                                )
                            }
                            CloudType.WEBDAV -> {
                                Log.d(mTAG, "Using WebDAV upload for ${p.packageName}")
                                mPackagesBackupUtil.upload(
                                    client = mClient,
                                    p = p,
                                    t = t,
                                    dataType = type,
                                    srcDir = dstDir,
                                    dstDir = remoteAppDir,
                                    isCanceled = { isCanceled() }
                                )
                            }
                            CloudType.SMB -> {
                                Log.d(mTAG, "Using SMB upload for ${p.packageName}")
                                mPackagesBackupUtil.upload(
                                    client = mClient,
                                    p = p,
                                    t = t,
                                    dataType = type,
                                    srcDir = dstDir,
                                    dstDir = remoteAppDir,
                                    isCanceled = { isCanceled() }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(mTAG, "Upload failed for ${p.packageName}, type: $type", e)
                        t.update(dataType = type, state = OperationState.ERROR, log = e.message)
                        return
                    }
                } else {
                    Log.w(mTAG, "No compressed file found for ${p.packageName}, type: $type")
                }
            }

            t.update(dataType = type, progress = 1f)
            t.update(processingIndex = t.processingIndex + 1)
            Log.d(mTAG, "Backup completed successfully for ${p.packageName}, type: $type")

        } catch (e: Exception) {
            Log.e(mTAG, "Backup failed for ${p.packageName}, type: $type", e)
            t.update(dataType = type, state = OperationState.ERROR, log = e.message)
            throw e
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 使用 Restic 备份到 S3
     */
    private suspend fun backupWithResticToS3(
        packageName: String,
        compressedFile: File,
        dataType: DataType,
        s3Extra: S3Extra,
        remotePath: String,
        t: TaskDetailPackageEntity
    ): Boolean {
        return try {
            // 从路径中提取用户信息
            val userId = extractUserIdFromPath(compressedFile.absolutePath)
            val backupType = dataType.type

            // 构建标签: userId-packageName-timestamp-dataType
            val tag = "$userId-$packageName-$mBackupTimestamp-$backupType"
            val tags = listOf(tag)

            // 【关键】设置当前处理标签,用于取消机制
            Log.d("ResticTag", "Setting current tag: $tag")
            mCurrentProcessingTag = tag

            // 【取消】生成并记录本次备份的取消令牌 id
            val cancelId = System.nanoTime()
            mCurrentBackupCancelId = cancelId
            Log.i("RusticCancel", "backupWithResticToS3 enter, package=$packageName, tag=$tag, cancelId=$cancelId")

            val unifiedRepoPath = mCloudEntity.remote

            val result = resticRepoCos.backupFileToCos(
                extra = s3Extra,
                remotePath = unifiedRepoPath,
                filePath = compressedFile.absolutePath,
                tags = tags,
                password = s3Extra.resticPassword.ifEmpty { mContext.readS3ResticPassword() ?: getResticPassword() },
                progressCallback = object : ResticProgressCallback {
                    override fun onRestoreProgress(
                        filesFinished: Long, filesTotal: Long,
                        bytesWritten: Long, bytesTotal: Long,
                        filesSkipped: Long, bytesSkipped: Long
                    ) {
                        // 恢复进度,备份时不使用
                    }

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
                            t.update(dataType = dataType, content = content)
                        }
                    }
                },
                cancelId = cancelId
            )

            // 【关键】备份完成后清除标签
            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L

            if (result.first == 0) {
                val snapshotId = extractSnapshotIdFromJson(result.second)
                if (snapshotId != null) {
                    // 仅记录日志,不更新数据库
                    Log.d(mTAG, "Restic S3 backup successful for $packageName, snapshotId: $snapshotId")
                    updateCloudResticInfo(packageName, snapshotId, remotePath)
                }
                true
            } else {
                Log.i("RusticCancel", "backupWithResticToS3 non-zero result, package=$packageName, code=${result.first}, msg=${result.second}")
                false
            }
        } catch (e: Exception) {
            // 【关键】异常时也要清除标签
            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L

            Log.e("RusticCancel", "backupWithResticToS3 failed/cancelled, package=$packageName, msg=${e.message}")
            Log.e(mTAG, "Error during S3 Restic backup", e)
            false
        }
    }

    /**
     * 使用 Restic 备份到 FTP（opendal:ftp，纯 JNI）
     */
    private suspend fun backupWithResticToFtp(
        packageName: String,
        compressedFile: File,
        dataType: DataType,
        ftpExtra: FTPExtra,
        remotePath: String,
        t: TaskDetailPackageEntity
    ): Boolean {
        return try {
            // 从路径中提取用户信息
            val userId = extractUserIdFromPath(compressedFile.absolutePath)
            val backupType = dataType.type

            // 构建标签: userId-packageName-timestamp-dataType
            val tag = "$userId-$packageName-$mBackupTimestamp-$backupType"
            val tags = listOf(tag)

            // 【关键】设置当前处理标签,用于取消机制
            Log.d("ResticTag", "Setting current tag: $tag")
            mCurrentProcessingTag = tag

            // 【取消】生成并记录本次备份的取消令牌 id
            val cancelId = System.nanoTime()
            mCurrentBackupCancelId = cancelId
            Log.i("RusticCancel", "backupWithResticToFtp enter, package=$packageName, tag=$tag, cancelId=$cancelId")

            val unifiedRepoPath = mCloudEntity.remote

            val result = resticRepoFtp.backupFileToFtp(
                cloudEntity = mCloudEntity,
                remotePath = unifiedRepoPath,
                filePath = compressedFile.absolutePath,
                tags = tags,
                password = ftpExtra.resticPassword.ifEmpty { mContext.readFtpResticPassword() ?: getResticPassword() },
                progressCallback = object : ResticProgressCallback {
                    override fun onRestoreProgress(
                        filesFinished: Long, filesTotal: Long,
                        bytesWritten: Long, bytesTotal: Long,
                        filesSkipped: Long, bytesSkipped: Long
                    ) {
                        // 恢复进度,备份时不使用
                    }

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
                            t.update(dataType = dataType, content = content)
                        }
                    }
                },
                cancelId = cancelId
            )

            // 【关键】备份完成后清除标签
            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L

            if (result.first == 0) {
                val snapshotId = extractSnapshotIdFromJson(result.second)
                if (snapshotId != null) {
                    Log.d(mTAG, "Restic FTP backup successful for $packageName, snapshotId: $snapshotId")
                    updateCloudResticInfo(packageName, snapshotId, remotePath)
                }
                true
            } else {
                Log.i("RusticCancel", "backupWithResticToFtp non-zero result, package=$packageName, code=${result.first}, msg=${result.second}")
                false
            }
        } catch (e: Exception) {
            // 【关键】异常时也要清除标签
            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L

            Log.e("RusticCancel", "backupWithResticToFtp failed/cancelled, package=$packageName, msg=${e.message}")
            Log.e(mTAG, "Error during FTP Restic backup", e)
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

    // 辅助方法：查找压缩文件
    private fun findCompressedFile(dstDir: String, dataType: DataType): File? {
        return when (dataType) {
            DataType.PACKAGE_APK -> File("$dstDir/${DataType.PACKAGE_APK.type}.tar")
            DataType.PACKAGE_USER -> File("$dstDir/${DataType.PACKAGE_USER.type}.tar")
            DataType.PACKAGE_USER_DE -> File("$dstDir/${DataType.PACKAGE_USER_DE.type}.tar")
            DataType.PACKAGE_DATA -> File("$dstDir/${DataType.PACKAGE_DATA.type}.tar")
            DataType.PACKAGE_OBB -> File("$dstDir/${DataType.PACKAGE_OBB.type}.tar")
            DataType.PACKAGE_MEDIA -> File("$dstDir/${DataType.PACKAGE_MEDIA.type}.tar")
            DataType.PACKAGE_CONFIG -> File("$dstDir/package_restore_config.json")
            else -> null
        }.takeIf { it?.exists() == true }
    }

    private suspend fun updateCloudResticInfo(packageName: String, snapshotId: String, repoPath: String) {
        Log.d(mTAG, "Updating cloud Restic info for $packageName: snapshotId=$snapshotId")
    }

    override suspend fun onConfigSaved(path: String, archivesRelativeDir: String) {
        mCloudRepo.upload(
            client = mClient,
            src = path,
            dstDir = getRemoteAppDir(archivesRelativeDir),
            onUploading = { _, _ -> },
            isCanceled = { isCanceled() }
        )
    }

    override suspend fun onCleanupFailedBackup(archivesRelativeDir: String) {
        val remoteAppDir = getRemoteAppDir(archivesRelativeDir)
        Log.d(mTAG, "S3 Restic backup failed at: $remoteAppDir")
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
    override lateinit var mPackageDao: PackageDao

    @Inject
    override lateinit var mPackageRepo: PackageRepository

    @Inject
    override lateinit var mPackagesBackupUtil: PackagesBackupUtil

    override val mRootDir by lazy { mContext.localBackupSaveDir() }
    override val mAppsDir by lazy { mPathUtil.getLocalBackupAppsDir() }
    override val mConfigsDir by lazy { mPathUtil.getLocalBackupConfigsDir() }

    @Inject
    lateinit var mCloudRepo: CloudRepository

    private lateinit var mCloudEntity: CloudEntity
    private lateinit var mClient: CloudClient
    private lateinit var mRemotePath: String
    private lateinit var mRemoteAppsDir: String
    private lateinit var mRemoteConfigsDir: String
}