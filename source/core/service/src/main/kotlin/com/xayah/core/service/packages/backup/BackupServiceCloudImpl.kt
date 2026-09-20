package com.xayah.core.service.packages.backup

import android.util.Log
import com.xayah.core.restic.ResticRepository
import com.xayah.core.restic.ResticRepository.ResticProgressCallback
import com.xayah.core.restic.ResticSnapshot
import com.xayah.core.restic.CloudResticBackend
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
import com.xayah.core.model.CloudType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.model.util.get
import com.xayah.core.model.util.formatSize
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.encodeAccountId
import com.xayah.core.util.command.Tar
import com.xayah.core.util.IconRelativeDir
import com.xayah.core.model.CompressionType
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
    lateinit var resticBackends: Map<CloudType, @JvmSuppressWildcards CloudResticBackend>

    private fun backend(entity: CloudEntity) = resticBackends.getValue(entity.type)

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
            Log.e(
                mTAG,
                "备份前云端仓库检查失败: type=${entity.type} remote=${entity.remote}（仓库不存在/损坏/密码错/不可访问）"
            )
        }
        return ok
    }

    private fun getRemoteAppDir(archivesRelativeDir: String) =
        "${mRemoteAppsDir}/${archivesRelativeDir}"

    override suspend fun onAppDirCreated(archivesRelativeDir: String): Boolean =
        runCatchingOnService {
            mClient.mkdirRecursively(getRemoteAppDir(archivesRelativeDir))
        }

    override suspend fun backup(
        type: DataType,
        p: PackageEntity,
        r: PackageEntity?,
        t: TaskDetailPackageEntity,
        dstDir: String
    ) {
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
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(mTAG, "Data backup failed for ${p.packageName}, type: $type", e)
                    throw e
                }
            }

            Log.d(
                mTAG,
                "Backup compression completed for ${p.packageName}, success: ${result.isSuccess}"
            )

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
                            CloudType.S3, CloudType.FTP, CloudType.WEBDAV, CloudType.SFTP, CloudType.AWSS3 -> {
                                Log.d(mTAG, "Using Restic backup for ${p.packageName}")
                                val resticSuccess = backupWithResticByType(
                                    packageName = p.packageName,
                                    compressedFile = compressedFile,
                                    dataType = type,
                                    remotePath = "${p.archivesRelativeDir}",
                                    t = t
                                )
                                if (resticSuccess) {
                                    Log.d(
                                        mTAG,
                                        "Restic backup successful for ${p.packageName} $type"
                                    )
                                } else {
                                    Log.e(mTAG, "Restic backup failed for ${p.packageName} $type")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(mTAG, "Backup failed for ${p.packageName}, type: $type", e)
            t.update(dataType = type, state = OperationState.ERROR, log = e.message)
            throw e
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 统一 Restic 备份分派：S3/FTP/WEBDAV/SFTP 均通过 CloudResticBackend 注册表多态调用，
     * 各后端在实现内部自行选择 opendal / librclone serve，密码由 resolveResticPassword 统一解析。
     */
    private suspend fun backupWithResticByType(
        packageName: String,
        compressedFile: File,
        dataType: DataType,
        remotePath: String,
        t: TaskDetailPackageEntity
    ): Boolean {
        return try {
            val userId = extractUserIdFromPath(compressedFile.absolutePath)
            val backupType = dataType.type
            val tag = "$userId-$packageName-$mBackupTimestamp-$backupType"
            val tags = listOf(tag)

            Log.d("ResticTag", "Setting current tag: $tag")
            mCurrentProcessingTag = tag

            val cancelId = System.nanoTime()
            mCurrentBackupCancelId = cancelId
            Log.i(
                "RusticCancel",
                "backupWithRestic enter, package=$packageName, tag=$tag, cancelId=$cancelId"
            )

            val unifiedRepoPath = mCloudEntity.remote
            val backupStartAt = System.currentTimeMillis()

            val backend = backend(mCloudEntity)
            val password = backend.resolveResticPassword(mCloudEntity)

            val result = backend.backupFile(
                cloudEntity = mCloudEntity,
                remotePath = unifiedRepoPath,
                filePath = compressedFile.absolutePath,
                tags = tags,
                password = password,
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
                        val elapsedMs =
                            (System.currentTimeMillis() - backupStartAt).coerceAtLeast(1L)
                        val avgSpeed = if (bytesDone > 0) bytesDone * 1000L / elapsedMs else 0L
                        val speedText =
                            if (avgSpeed > 0) avgSpeed.formatToStorageSizePerSecond() else ""
                        val bytesText = bytesDone.toDouble().formatSize()
                        val content =
                            if (speedText.isNotEmpty()) "$speedText | $bytesText" else bytesText
                        Log.d(mTAG, "Restic backup progress: $content")
                        runBlocking {
                            t.update(dataType = dataType, content = content)
                        }
                    }
                },
                cancelId = cancelId
            )

            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L

            if (result.first == 0) {
                val snapshotId = extractSnapshotIdFromJson(result.second)
                if (snapshotId != null) {
                    Log.d(
                        mTAG,
                        "Restic backup successful for $packageName, snapshotId: $snapshotId"
                    )
                    updateCloudResticInfo(packageName, snapshotId, remotePath)
                }
                true
            } else {
                Log.i(
                    "RusticCancel",
                    "backupWithRestic non-zero result, package=$packageName, code=${result.first}, msg=${result.second}"
                )
                false
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            mCurrentProcessingTag = null
            mCurrentBackupCancelId = 0L
            Log.e(
                "RusticCancel",
                "backupWithRestic failed/cancelled, package=$packageName, msg=${e.message}"
            )
            Log.e(mTAG, "Error during Restic backup", e)
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
        // config 已作为 PACKAGE_CONFIG rustic 快照备份，无需再经 AWS SDK 单独上传对象
        Log.d(mTAG, "onConfigSaved: skip object upload; config is backed up as PACKAGE_CONFIG restic snapshot")
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

    private fun sanitizeTag(raw: String): String = encodeAccountId(raw)

    override suspend fun onIconsSaved(path: String, entity: ProcessingInfoEntity) {
        val iconFile = File(path)
        if (!iconFile.exists()) {
            Log.d(mTAG, "SAVE_ICONS: icon.tar not found at $path, skip cloud icon snapshot")
            return
        }

        val tag = "__icons__-${sanitizeTag(mCloudEntity.name)}-$mBackupTimestamp"
        val tags = listOf(tag)
        val unifiedRepoPath = mCloudEntity.remote
        val cancelId = System.nanoTime()

        try {
            val backend = backend(mCloudEntity)
            val password = backend.resolveResticPassword(mCloudEntity)
            val result: Pair<Int, String> = backend.backupFile(
                cloudEntity = mCloudEntity,
                remotePath = unifiedRepoPath,
                filePath = path,
                tags = tags,
                password = password,
                cancelId = cancelId
            )

            if (result.first == 0) {
                Log.d(mTAG, "SAVE_ICONS: cloud icon snapshot pushed, tag=$tag, type=${mCloudEntity.type}")
            } else {
                Log.i(mTAG, "SAVE_ICONS: cloud icon snapshot failed, code=${result.first}, msg=${result.second}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(mTAG, "SAVE_ICONS: cloud icon snapshot exception: ${e.message}", e)
        }
    }

    /**
     * 云端多端合并：拉取远端最新 __icons__ 快照并解压为本地目录，供 backupIconsAndLabels 做并集。
     * 任何环节失败均返回 null（回退为纯本机备份，不阻断主流程）。
     */
    override suspend fun prepareRemoteIconsForMerge(): File? {
        // 前缀算法必须与 onIconsSaved 的 tag、恢复端 accountId 完全一致（复用 sanitizeTag）
        val prefix = "__icons__-${sanitizeTag(mCloudEntity.name)}-"
        Log.d(mTAG, "ICON_MERGE: enter, prefix=$prefix, type=${mCloudEntity.type}")

        val backend = backend(mCloudEntity)

        // 密码解析：统一走注册表后端
        val password: String = try {
            backend.resolveResticPassword(mCloudEntity)
        } catch (e: Exception) {
            Log.w(mTAG, "ICON_MERGE: resolve password failed, skip merge: ${e.message}")
            return null
        }

        var restoreTmp: File? = null
        try {
            // 1. 列快照
            val snapshots: List<ResticSnapshot> = backend.listSnapshots(mCloudEntity, password)
            Log.d(mTAG, "ICON_MERGE: snapshots total=${snapshots.size}")

            // 2. 筛 __icons__-<accountId>- 前缀，取 time 最新
            val matched = snapshots.filter { snap -> snap.tags.any { it.startsWith(prefix) } }
            val latest = matched.maxByOrNull { it.time }
            if (latest == null) {
                Log.d(mTAG, "ICON_MERGE: no remote icon snapshot, skip merge (matched=${matched.size})")
                return null
            }
            val snapshotId = latest.id
            Log.d(mTAG, "ICON_MERGE: matched=${matched.size}, selected=$snapshotId, tags=${latest.tags}")

            // 3. 整快照还原到临时目录
            restoreTmp = File(mContext.cacheDir, "icon_remote_merge_restore").apply {
                deleteRecursively(); mkdirs()
            }
            val ok = backend.restoreSnapshot(mCloudEntity, password, snapshotId, restoreTmp.absolutePath)
            if (!ok) {
                Log.w(mTAG, "ICON_MERGE: restore snapshot failed, snapshotId=$snapshotId")
                restoreTmp.deleteRecursively()
                return null
            }

            // 4. 递归找 icon.tar
            val iconTar = restoreTmp.walkTopDown().firstOrNull {
                it.isFile && it.name == "$IconRelativeDir.${CompressionType.TAR.suffix}"
            }
            if (iconTar == null) {
                Log.w(mTAG, "ICON_MERGE: icon.tar not found in ${restoreTmp.absolutePath}")
                restoreTmp.deleteRecursively()
                return null
            }
            Log.d(mTAG, "ICON_MERGE: found icon.tar=${iconTar.absolutePath}, size=${iconTar.length()}")

            // 5. 解压到独立合并目录（供 backupIconsAndLabels 读取；由调用方 finally 清理）
            val mergeDir = File(mContext.cacheDir, "icon_remote_merge").apply {
                deleteRecursively(); mkdirs()
            }
            Tar.decompress(
                cacheDir = mContext.cacheDir.path,
                callTar = { o, e, argv -> mRootService.callTarCli(o, e, argv) },
                src = iconTar.absolutePath,
                dst = mergeDir.absolutePath,
                stripComponents = 1,
            )
            restoreTmp.deleteRecursively()

            // 5.1 关键修复：Tar 由 root 解压，产物属主为 root，App UID 无读权限（EACCES）。
            //      递归把 mergeDir 的 SELinux context 与属主改回 App，使 backupIconsAndLabels 能读 png/labels.json。
            PathUtil.setDirSELinux(mContext, mergeDir.absolutePath)
            val canList = mergeDir.listFiles()?.size ?: -1
            Log.d(mTAG, "ICON_MERGE: fixed permissions on ${mergeDir.absolutePath}, files=$canList")

            val remotePng = mergeDir.listFiles()?.count { it.isFile && it.name.endsWith(".png") } ?: 0
            val remoteLabels = File(mergeDir, "labels.json").exists()
            Log.d(mTAG, "ICON_MERGE: decompressed to ${mergeDir.absolutePath}, remotePng=$remotePng, labels.json=$remoteLabels")
            return mergeDir
        } catch (e: kotlinx.coroutines.CancellationException) {
            restoreTmp?.deleteRecursively()
            throw e
        } catch (e: Exception) {
            Log.w(mTAG, "ICON_MERGE: exception, fallback to local-only: ${e.message}", e)
            restoreTmp?.deleteRecursively()
            return null
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