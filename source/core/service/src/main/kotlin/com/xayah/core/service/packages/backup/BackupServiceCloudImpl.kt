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
import com.xayah.core.model.ProgressStat
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.CloudType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.model.util.get
import com.xayah.core.model.util.formatSize
import com.xayah.core.model.progressStatKey
import com.xayah.core.model.dedupRatioOf
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
        // 开始前检查取消标志
        if (isCanceled()) {
            Log.d(mTAG, "Backup canceled before processing $type")
            return
        }

        // CONFIG 是 JSON 小文件不是 tar 流，走文件路径快照；tag 四段式与数据快照同批次。
        // 关键：tag 必须带 user_ 前缀，否则 ResticShared.parseAppsDb 会静默跳过，云端恢复列表看不到。
        if (type == DataType.PACKAGE_CONFIG) {
            t.update(dataType = type, state = OperationState.PROCESSING)
            val configFile = File("$dstDir/package_restore_config.json")
            if (!configFile.exists()) {
                t.update(dataType = type, state = OperationState.ERROR, log = "config json missing: ${configFile.absolutePath}")
                t.update(dataType = type, progress = 1f)
                t.update(processingIndex = t.processingIndex + 1)
                return
            }
            val backend = backend(mCloudEntity)
            val password = backend.resolveResticPassword(mCloudEntity)
            val tag = "user_${p.userId}-${p.packageName}-$mBackupTimestamp-config"
            val cancelId = System.nanoTime()
            mCurrentBackupCancelId = cancelId
            mCurrentProcessingTag = tag
            try {
                val result = backend.backupFile(
                    cloudEntity = mCloudEntity,
                    remotePath = mCloudEntity.remote,
                    filePath = configFile.absolutePath,
                    tags = listOf(tag),
                    password = password,
                    cancelId = cancelId,
                )
                if (result.first == 0) {
                    val snapshotId = extractSnapshotIdFromJson(result.second)
                    if (snapshotId != null) updateCloudResticInfo(p.packageName, snapshotId, p.archivesRelativeDir)
                    t.update(dataType = type, state = OperationState.DONE, log = "config snapshot ok tag=$tag")
                    Log.d(mTAG, "config snapshot ok for ${p.packageName} tag=$tag")
                } else {
                    t.update(dataType = type, state = OperationState.ERROR, log = "config snapshot failed code=${result.first} msg=${result.second}")
                    runCatching { t.update(state = OperationState.ERROR) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                t.update(dataType = type, state = OperationState.ERROR, log = "canceled")
                runCatching {
                    t.update(state = OperationState.ERROR)
                    mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                }
                throw e
            } catch (e: Exception) {
                val isCancel = e.message?.contains("rustic backup canceled") == true ||
                        e.message?.contains("cancelled by the user") == true
                t.update(dataType = type, state = OperationState.ERROR,
                    log = if (isCancel) "canceled" else (e.message ?: "unknown"))
                if (isCancel) {
                    runCatching {
                        t.update(state = OperationState.ERROR)
                        mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                    }
                    throw kotlinx.coroutines.CancellationException("backup canceled: ${e.message}")
                }
                Log.w(mTAG, "config snapshot exception for ${p.packageName}: ${e.message}")
            } finally {
                mCurrentBackupCancelId = 0L
                mCurrentProcessingTag = null
            }
            t.update(dataType = type, progress = 1f)
            t.update(processingIndex = t.processingIndex + 1)
            return
        }

        // fd 流式链路：prepareXxxArgv → withStreamBackupContext → streamBackupToRustic，不再落盘、不跑 testArchive
        val argvResult = if (type == DataType.PACKAGE_APK) {
            mPackagesBackupUtil.prepareApkArgv(p = p, r = r, t = t)
        } else {
            mPackagesBackupUtil.prepareDataArgv(p = p, r = r, t = t, dataType = type)
        }

        when (argvResult) {
            is PackagesBackupUtil.ArgvResult.Skip -> {
                // prepare 内部已 updateInfo(SKIP)，仅推进进度
            }

            is PackagesBackupUtil.ArgvResult.Error -> {
                // prepare 内部已 updateInfo(ERROR, log)，透传日志即可
                Log.e(mTAG, "prepareArgv failed for ${p.packageName} $type: ${argvResult.out}")
            }

            is PackagesBackupUtil.ArgvResult.Ready -> {
                if (isCanceled()) {
                    Log.d(mTAG, "canceled before stream for ${p.packageName} $type")
                    t.update(dataType = type, progress = 1f)
                    t.update(processingIndex = t.processingIndex + 1)
                    return
                }

                val backend = backend(mCloudEntity)
                val password = backend.resolveResticPassword(mCloudEntity)
                val tag = "user_${p.userId}-${p.packageName}-$mBackupTimestamp-${type.type}"
                val cancelId = System.nanoTime()
                // 登记取消链路：cancel() 通过 mCurrentBackupCancelId 调 cancelRusticBackup
                mCurrentBackupCancelId = cancelId
                mCurrentProcessingTag = tag

                Log.i(mTAG, "cloud fd-backup start pkg=${p.packageName} type=${type.type} sizeBytes=${argvResult.sizeBytes} cancelId=$cancelId")
                t.update(dataType = type, state = OperationState.PROCESSING)
                // 节流时间戳：poll 回调频率高，≥500ms 才刷一次内存进度
                var lastContentUpdate = 0L
                val statKey = progressStatKey(key = p.packageName, dataType = type)
                val startMs = System.currentTimeMillis()

                try {
                    val streamResult = backend.withStreamBackupContext(mCloudEntity, mCloudEntity.remote) { repoPath, options ->
                        mPackagesBackupUtil.streamBackupToRustic(
                            ready = argvResult,
                            p = p,
                            dataType = type,
                            repositoryPath = repoPath,
                            password = password,
                            tags = listOf(tag),
                            options = options,
                            cancelId = cancelId,
                            // 实时进度只写内存 StateFlow（进程内），不再高频 upsert Room content
                            onProgress = { readBytes, _, writtenBytes ->
                                val now = System.currentTimeMillis()
                                if (now - lastContentUpdate >= 500) {
                                    lastContentUpdate = now
                                    // 云端沿用"平均速度"口径，无分母（fd 流 readTotal 恒 0）
                                    val elapsedMs = (now - startMs).coerceAtLeast(1L)
                                    val avgSpeed = if (readBytes > 0) readBytes * 1000L / elapsedMs else 0L
                                    updateProgressStat(
                                        key = statKey,
                                        stat = ProgressStat(
                                            total = readBytes,
                                            increment = writtenBytes,
                                            speed = avgSpeed,
                                            dedupRatio = dedupRatioOf(total = readBytes, increment = writtenBytes),
                                        ),
                                    )
                                }
                            }
                        )
                    }

                    if (streamResult.shell.isSuccess) {
                        streamResult.snapshotId?.let { updateCloudResticInfo(p.packageName, it, p.archivesRelativeDir) }
                        // 定格 DONE，副标题 "平均速度 | 大小"
                        val bytesText = streamResult.fedBytes.toDouble().formatSize()
                        val elapsedMs = System.currentTimeMillis() - startMs
                        val avgSpeed = if (elapsedMs > 0 && streamResult.fedBytes > 0) streamResult.fedBytes * 1000 / elapsedMs else 0L
                        val speedText = if (avgSpeed > 0) avgSpeed.formatToStorageSizePerSecond() else ""
                        // 终值落内存 + 已有列（content/bytes），不新增列、不做迁移
                        // 总量用 fedBytes，增量沿用最后一次真实写入量（不能被 fedBytes 覆盖）
                        freezeProgressStat(key = statKey, total = streamResult.fedBytes, speed = avgSpeed)
                        t.update(
                            dataType = type,
                            state = OperationState.DONE,
                            bytes = streamResult.fedBytes,
                            content = if (speedText.isNotEmpty()) "$speedText | $bytesText" else bytesText,
                        )
                        Log.d(mTAG, "cloud fd-backup ok for ${p.packageName} $type snapshot=${streamResult.snapshotId}")
                    } else {
                        t.update(dataType = type, state = OperationState.ERROR, log = streamResult.shell.outString)
                        runCatching { t.update(state = OperationState.ERROR) }
                        Log.e(mTAG, "cloud fd-backup failed for ${p.packageName} $type: ${streamResult.shell.outString}")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    t.update(dataType = type, state = OperationState.ERROR, log = "canceled")
                    runCatching {
                        t.update(state = OperationState.ERROR)
                        mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                    }
                    throw e
                } catch (e: Exception) {
                    // impl 侧抛的 CancellationException 过 binder 会被包成 IllegalStateException，
                    // 类型丢失但 message 保留；命中消息匹配必须重抛 CancellationException。
                    val isCancel = e.message?.contains("rustic backup canceled") == true ||
                            e.message?.contains("cancelled by the user") == true ||
                            e.message?.contains("tar aborted via cancelFd") == true
                    t.update(dataType = type, state = OperationState.ERROR,
                        log = if (isCancel) "canceled" else (e.message ?: "unknown"))
                    if (isCancel) {
                        runCatching {
                            t.update(state = OperationState.ERROR)
                            mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                        }
                        throw kotlinx.coroutines.CancellationException("backup canceled: ${e.message}")
                    }
                    Log.e(mTAG, "cloud fd-backup exception for ${p.packageName} $type: ${e.message}", e)
                } finally {
                    mCurrentBackupCancelId = 0L
                    mCurrentProcessingTag = null
                }
            }
        }

        t.update(dataType = type, progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    private val json = Json { ignoreUnknownKeys = true }

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