package com.xayah.core.service.packages.backup

import android.util.Log
import com.xayah.core.restic.ResticRepository
import com.xayah.core.restic.ResticRepository.ResticProgressCallback   // 添加
import com.xayah.core.restic.ResticSnapshot  // 添加
import com.xayah.core.model.util.formatToStorageSizePerSecond
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.CloudType  // 添加
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.util.get
import com.xayah.core.network.client.CloudClient
import com.xayah.core.network.client.S3ClientImpl
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.PathUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json  // 添加
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

        log { "Trying to create: $mRemoteAppsDir." }
        log { "Trying to create: $mRemoteConfigsDir." }
        mClient.mkdirRecursively(mRemoteAppsDir)
        mClient.mkdirRecursively(mRemoteConfigsDir)
    }

    private fun getRemoteAppDir(archivesRelativeDir: String) = "${mRemoteAppsDir}/${archivesRelativeDir}"

    override suspend fun onAppDirCreated(archivesRelativeDir: String): Boolean = runCatchingOnService {
        mClient.mkdirRecursively(getRemoteAppDir(archivesRelativeDir))
    }

    override suspend fun backup(type: DataType, p: PackageEntity, r: PackageEntity?, t: TaskDetailPackageEntity, dstDir: String) {
        // 在开始备份前检查取消标志
        if (isCanceled()) {
            log { "Backup canceled before processing $type" }
            return
        }

        val remoteAppDir = getRemoteAppDir(p.archivesRelativeDir)
        val result = if (type == DataType.PACKAGE_APK) {
            mPackagesBackupUtil.backupApk(p = p, t = t, r = r, dstDir = dstDir, isCanceled = { isCanceled() })
        } else {
            mPackagesBackupUtil.backupData(p = p, t = t, r = r, dataType = type, dstDir = dstDir, isCanceled = { isCanceled() })
        }

        // 压缩后再次检查取消标志
        if (isCanceled()) {
            log { "Backup canceled after compression for $type" }
            return
        }

        if (result.isSuccess && t.get(type).state != OperationState.SKIP) {
            // 查找压缩文件
            val compressedFile = findCompressedFile(dstDir, type)
            if (compressedFile != null) {
                // 根据云存储类型选择备份方式
                when (mCloudEntity.type) {
                    CloudType.S3 -> {
                        // S3 使用 Restic 备份
                        val s3Extra = json.decodeFromString<S3Extra>(mCloudEntity.extra)
                        val resticSuccess = backupWithResticToS3(
                            packageName = p.packageName,
                            compressedFile = compressedFile,
                            dataType = type,
                            s3Extra = s3Extra,
                            remotePath = "${p.archivesRelativeDir}"
                        )
                        if (resticSuccess) {
                            log { "Restic S3 backup successful for ${p.packageName} $type" }
                        }
                    }
                    CloudType.FTP -> {
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
                    CloudType.SFTP -> {
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
            }
        }

        t.update(dataType = type, progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
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
        remotePath: String
    ): Boolean {
        return try {
            val userId = extractUserIdFromPath(compressedFile.absolutePath)
            val backupType = dataType.type
            val tag = "$userId-$packageName-$mBackupTimestamp-$backupType"
            val tags = listOf(tag)

            val result = resticRepo.backupFileToS3(
                extra = s3Extra,
                remotePath = remotePath,
                filePath = compressedFile.absolutePath,
                tags = tags,
                password = getResticPassword(),
                progressCallback = object : ResticProgressCallback {
                    override fun onRestoreProgress(
                        filesFinished: Long, filesTotal: Long,
                        bytesWritten: Long, bytesTotal: Long,
                        filesSkipped: Long, bytesSkipped: Long
                    ) {
                        // 恢复进度，备份时不使用
                    }

                    override fun onBackupProgress(
                        percentDone: Float, bytesDone: Long,
                        bytesTotal: Long, filesDone: Long, filesTotal: Long
                    ) {
                        // 更新进度到 UI
                        log { "Restic S3 backup progress: ${percentDone * 100}%" }
                    }
                }
            )

            if (result.first == 0) {
                val snapshotId = extractSnapshotIdFromJson(result.second)
                if (snapshotId != null) {
                    // 仅记录日志，不更新数据库
                    log { "Restic S3 backup successful for $packageName, snapshotId: $snapshotId" }
                    updateCloudResticInfo(packageName, snapshotId, remotePath)  // 仅记录日志
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(mTAG, "Error during S3 Restic backup", e)
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
            DataType.PACKAGE_USER -> File("$dstDir/${DataType.PACKAGE_USER.type}.tar.zst")
            DataType.PACKAGE_USER_DE -> File("$dstDir/${DataType.PACKAGE_USER_DE.type}.tar.zst")
            DataType.PACKAGE_DATA -> File("$dstDir/${DataType.PACKAGE_DATA.type}.tar")
            DataType.PACKAGE_OBB -> File("$dstDir/${DataType.PACKAGE_OBB.type}.tar")
            DataType.PACKAGE_MEDIA -> File("$dstDir/${DataType.PACKAGE_MEDIA.type}.tar")
            DataType.PACKAGE_CONFIG -> File("$dstDir/package_restore_config.json")
            else -> null
        }.takeIf { it?.exists() == true }
    }

    private suspend fun updateCloudResticInfo(packageName: String, snapshotId: String, repoPath: String) {
        log { "Updating cloud Restic info for $packageName: snapshotId=$snapshotId" }
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
        log { "S3 Restic backup failed at: $remoteAppDir" }
        log { "No cleanup needed - Restic manages block storage automatically" }
    }

    override suspend fun onCleanupIncompleteBackup(currentIndex: Int) {
        log { "S3 Restic backup incomplete - no cleanup needed" }
        log { "Restic will handle partial blocks during restore" }
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
    }

    @Inject
    override lateinit var mPackageDao: PackageDao

    @Inject
    override lateinit var mPackageRepo: PackageRepository

    @Inject
    override lateinit var mPackagesBackupUtil: PackagesBackupUtil

    override val mRootDir by lazy { mPathUtil.getCloudTmpDir() }
    override val mAppsDir by lazy { mPathUtil.getCloudTmpAppsDir() }
    override val mConfigsDir by lazy { mPathUtil.getCloudTmpConfigsDir() }

    @Inject
    lateinit var mCloudRepo: CloudRepository

    private lateinit var mCloudEntity: CloudEntity
    private lateinit var mClient: CloudClient
    private lateinit var mRemotePath: String
    private lateinit var mRemoteAppsDir: String
    private lateinit var mRemoteConfigsDir: String
}