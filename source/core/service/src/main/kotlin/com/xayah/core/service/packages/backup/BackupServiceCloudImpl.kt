package com.xayah.core.service.packages.backup

import android.util.Log
import com.xayah.core.model.util.formatToStorageSizePerSecond
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.database.dao.UploadIdDao
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.model.util.get
import com.xayah.core.network.client.CloudClient
import com.xayah.core.network.client.S3ClientImpl
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.rclone.RcloneRepository
import com.xayah.core.restic.ResticRepository
import dagger.hilt.android.AndroidEntryPoint
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
    lateinit var rcloneRepo: RcloneRepository

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
        // 在开始备份前检查取消标志
        if (isCanceled()) {
            Log.d(mTAG, "Backup canceled before processing $type")
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
            Log.d(mTAG, "Backup canceled after compression for $type")
            return
        }

        Log.d(mTAG, "Compression completed, checking for Restic backup")
        if (result.isSuccess && t.get(type).state != OperationState.SKIP) {
            val compressedFile = findCompressedFile(dstDir, type)
            if (compressedFile != null) {
                Log.d(mTAG, "Starting Restic backup for ${p.packageName}")
                val userId = extractUserIdFromPath(compressedFile.absolutePath)
                val backupType = type.type
                val resticResult = resticRepo.backupFileWithResticBackend(
                    resticServerUrl = "http://127.0.0.1:38080/",
                    password = getResticPassword(),
                    filePath = compressedFile.absolutePath,
                    tags = listOf("$userId-$packageName-$mBackupTimestamp-$backupType")
                )

                val resticSuccess = resticResult.first == 0
                Log.d(mTAG, "Cloud Restic backup completed: $resticSuccess")
            }

            Log.d(mTAG, "Starting cloud upload for ${p.packageName}")
            mPackagesBackupUtil.upload(
                client = mClient,
                p = p,
                t = t,
                dataType = type,
                srcDir = dstDir,
                dstDir = remoteAppDir,
                isCanceled = { isCanceled() }
            )
            Log.d(mTAG, "Cloud upload completed")
        }

        Log.d(mTAG, "Backup method completed for ${p.packageName} $type")

        t.update(dataType = type, progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    private suspend fun startRcloneServer(): Boolean {
        return rcloneRepo.startRcloneServer(
            remote = mCloudEntity.name,   // 修正：使用配置名称"COS"
            path = "",                     // 修正：移除额外目录层
            addr = "127.0.0.1:38080"
        ).isSuccess
    }

    private suspend fun stopRcloneServer() {
        rcloneRepo.stopRcloneServer()
    }

    private suspend fun backupWithResticBackend(
        packageName: String,
        compressedFile: File,
        dataType: DataType
    ): Boolean {
        val repoPath = "rest:http://localhost:38080/"
        val password = getResticPassword()

        if (!resticRepo.checkRepository(repoPath, password)) {
            // 初始化 REST 仓库
            resticRepo.initRepositoryWithResticBackend(repoPath, password)
        }

        val userId = extractUserIdFromPath(compressedFile.absolutePath)
        val backupType = dataType.type
        val tag = "$userId-$packageName-$mBackupTimestamp-$backupType"
        val tags = listOf(tag)

        val result = resticRepo.backupFileWithResticBackend(
            resticServerUrl = "http://localhost:38080/",
            password = password,
            filePath = compressedFile.absolutePath,
            tags = tags
        )

        return result.first == 0
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
            else -> null
        }.takeIf { it?.exists() == true }
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
        Log.d(mTAG, "Cleaning up failed backup at: $remoteAppDir")

        runCatching {
            // 使用 deleteRecursively 删除目录下所有对象
            mClient.deleteRecursively(remoteAppDir)

            // 清理未完成的分块上传
            Log.d(mTAG, "Cleaning up incomplete multipart uploads for: $remoteAppDir")
            val uploadIds = mUploadIdDao.getAll()
            uploadIds.forEach { uploadIdEntity ->
                if (uploadIdEntity.key.startsWith(remoteAppDir)) {
                    Log.d(mTAG, "Aborting multipart upload: ${uploadIdEntity.uploadId}")
                    runCatching {
                        // 需要类型转换为 S3ClientImpl 才能调用 abortMultipartUpload
                        if (mClient is S3ClientImpl) {
                            (mClient as S3ClientImpl).abortMultipartUpload(
                                uploadIdEntity.bucket,
                                uploadIdEntity.key,
                                uploadIdEntity.uploadId
                            )
                        }
                        mUploadIdDao.deleteById(uploadIdEntity.id)
                    }.onFailure { e ->
                        Log.d(mTAG, "Failed to abort upload: ${e.message}")
                    }
                }
            }
        }.onSuccess {
            Log.d(mTAG, "Successfully cleaned up: $remoteAppDir")
        }.onFailure { e ->
            Log.d(mTAG, "Failed to cleanup: ${e.message}")
        }
    }

    override suspend fun onCleanupIncompleteBackup(currentIndex: Int) {
        Log.d(mTAG, "Cleaning up incomplete backups from index: $currentIndex")

        val timestamp = mBackupTimestamp
        Log.d(mTAG, "Using timestamp: $timestamp for cleanup")

        // 删除未完成的包(基于状态判断)
        mPkgEntities.forEachIndexed { index, pkg ->
            val p = pkg.packageEntity
            // 只删除时间戳匹配且状态不是 DONE 的包
            if (index >= currentIndex - 1 && pkg.packageEntity.indexInfo.backupTimestamp == timestamp) {
                val remoteAppDir = getRemoteAppDir(p.archivesRelativeDir)
                Log.d(mTAG, "Cleaning up incomplete backup: ${p.packageName} at $remoteAppDir")

                // 删除远程文件
                runCatching {
                    mClient.deleteRecursively(remoteAppDir)
                }.onSuccess {
                    Log.d(mTAG, "Successfully cleaned up: $remoteAppDir")
                }.onFailure { e ->
                    Log.d(mTAG, "Failed to cleanup: ${e.message}")
                }

                // 标记和删除数据库记录
                runCatching {
                    mPackageDao.markAsCanceledByTimestamp(timestamp, p.packageName, p.userId)
                    mPackageDao.deleteCanceledByTimestamp(timestamp, OpType.RESTORE, p.packageName, p.userId)
                }.onSuccess {
                    Log.d(mTAG, "Successfully deleted package from database")
                }.onFailure { e ->
                    Log.d(mTAG, "Failed to delete package: ${e.message}")
                }
            }
        }

        // 清理相关的 uploadId
        Log.d(mTAG, "Cleaning up uploadId records for timestamp: $timestamp")
        runCatching {
            val allUploadIds = mUploadIdDao.getAll()
            allUploadIds.forEach { uploadIdEntity ->
                // 检查 uploadId 是否属于本次备份
                val belongsToThisBackup = mPkgEntities.any { pkg ->
                    val remoteAppDir = getRemoteAppDir(pkg.packageEntity.archivesRelativeDir)
                    uploadIdEntity.key.startsWith(remoteAppDir)
                }

                if (belongsToThisBackup) {
                    Log.d(mTAG, "Aborting multipart upload: ${uploadIdEntity.uploadId} for key: ${uploadIdEntity.key}")
                    runCatching {
                        if (mClient is S3ClientImpl) {
                            (mClient as S3ClientImpl).abortMultipartUpload(
                                bucket = uploadIdEntity.bucket,
                                key = uploadIdEntity.key,
                                uploadId = uploadIdEntity.uploadId
                            )
                        }
                        mUploadIdDao.deleteById(uploadIdEntity.id)
                    }.onFailure { e ->
                        Log.d(mTAG, "Failed to abort upload ${uploadIdEntity.uploadId}: ${e.message}")
                    }
                }
            }
        }.onSuccess {
            Log.d(mTAG, "Successfully cleaned up uploadId records")
        }.onFailure { e ->
            Log.d(mTAG, "Failed to cleanup uploadId records: ${e.message}")
        }
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

    @Inject
    lateinit var mUploadIdDao: UploadIdDao

    private lateinit var mCloudEntity: CloudEntity
    private lateinit var mClient: CloudClient
    private lateinit var mRemotePath: String
    private lateinit var mRemoteAppsDir: String
    private lateinit var mRemoteConfigsDir: String
}