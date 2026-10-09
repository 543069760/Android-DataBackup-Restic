package com.xayah.core.service.packages.backup

import android.util.Log
import android.os.SystemClock
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.model.util.get
import com.xayah.core.model.util.formatSize
import com.xayah.core.model.util.formatToStorageSizePerSecond
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
internal class BackupServiceLocalImpl @Inject constructor() : AbstractBackupService() {
    override val mTAG: String = "BackupServiceLocalImpl"

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

    override suspend fun backup(type: DataType, p: PackageEntity, r: PackageEntity?, t: TaskDetailPackageEntity, dstDir: String) {
        // CONFIG 是 JSON 小文件不是 tar 流，走文件路径快照；tag 四段式与数据快照同批次
        if (type == DataType.PACKAGE_CONFIG) {
            t.update(dataType = type, state = OperationState.PROCESSING)
            val configFile = File("$dstDir/package_restore_config.json")
            if (!configFile.exists()) {
                t.update(dataType = type, state = OperationState.ERROR, log = "config json missing: ${configFile.absolutePath}")
                t.update(dataType = type, progress = 1f)
                t.update(processingIndex = t.processingIndex + 1)
                return
            }
            val repoPath = getResticRepoPath()
            val password = getResticPassword()
            val tag = "user_${p.userId}-${p.packageName}-$mBackupTimestamp-config"
            val cancelId = System.nanoTime()
            mCurrentBackupCancelId = cancelId
            mCurrentProcessingTag = tag
            try {
                val result = resticRepo.backupWithResticToLocal(
                    repoPath = repoPath,
                    password = password,
                    filePath = configFile.absolutePath,
                    tags = listOf(tag),
                    cancelId = cancelId,
                )
                if (result.first == 0) {
                    t.update(dataType = type, state = OperationState.DONE, log = "config snapshot ok tag=$tag")
                    log { "[FdBackup] config snapshot ok for ${p.packageName} tag=$tag" }
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
                log { "[FdBackup] config snapshot exception for ${p.packageName}: ${e.message}" }
            } finally {
                mCurrentBackupCancelId = 0L
                mCurrentProcessingTag = null
            }
            t.update(dataType = type, progress = 1f)
            t.update(processingIndex = t.processingIndex + 1)
            return
        }
        // fd 流式链路：prepareXxxArgv → streamBackupToRustic，不再落盘、不跑 testArchive
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
                log { "[FdBackup] prepareArgv failed for ${p.packageName} $type: ${argvResult.out}" }
            }

            is PackagesBackupUtil.ArgvResult.Ready -> {
                if (isCanceled()) {
                    log { "[FdBackup] canceled before stream for ${p.packageName} $type" }
                    t.update(dataType = type, progress = 1f)
                    t.update(processingIndex = t.processingIndex + 1)
                    return
                }

                val repoPath = getResticRepoPath()
                val password = getResticPassword()
                val tag = "user_${p.userId}-${p.packageName}-$mBackupTimestamp-${type.type}"
                val cancelId = System.nanoTime()

                // 登记到取消链路：cancel() 通过 mCurrentBackupCancelId 调 cancelRusticBackup；
                // 不登记则 cancel() 读到 0，用户在 UI 点取消无效
                mCurrentBackupCancelId = cancelId
                mCurrentProcessingTag = tag

                android.util.Log.i(
                    "FdBackup",
                    "local fd-backup start pkg=${p.packageName} type=${type.type} sizeBytes=${argvResult.sizeBytes} cancelId=$cancelId"
                )
                t.update(dataType = type, state = OperationState.PROCESSING)
                // 节流时间戳：poll 回调频率高，≥500ms 才刷一次 content，避免高频 upsert 打 DB
                var lastContentUpdate = 0L

                try {
                    val startMs = SystemClock.elapsedRealtime()
                    val streamResult = mPackagesBackupUtil.streamBackupToRustic(
                        ready = argvResult,
                        p = p,
                        dataType = type,
                        repositoryPath = repoPath,
                        password = password,
                        tags = listOf(tag),
                        cancelId = cancelId,
                        onProgress = { readBytes, writtenSpeed ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastContentUpdate >= 500) {
                                lastContentUpdate = now
                                // fd 流无分母，进行中副标题显示"速度 | 累积量"
                                val bytesText = readBytes.toDouble().formatSize()
                                val speedText =
                                    if (writtenSpeed > 0) writtenSpeed.formatToStorageSizePerSecond() else ""
                                val content =
                                    if (speedText.isNotEmpty()) "$speedText | $bytesText" else bytesText
                                // t.update 是 suspend——onProgress 在 binder 线程被调，用 runBlocking 桥接
                                kotlinx.coroutines.runBlocking {
                                    t.update(
                                        dataType = type,
                                        state = OperationState.PROCESSING,
                                        content = content,
                                    )
                                }
                            }
                        }
                    )
                    if (streamResult.shell.isSuccess) {
                        streamResult.snapshotId?.let { updateResticInfo(p.packageName, it) }

                        // 新增：定格 DONE 画对勾，副标题显示 "平均速度 | 大小"（旧链路格式）
                        val bytesText = streamResult.fedBytes.toDouble().formatSize()
                        val elapsedMs = SystemClock.elapsedRealtime() - startMs
                        val speedText = if (elapsedMs > 0 && streamResult.fedBytes > 0)
                            (streamResult.fedBytes * 1000 / elapsedMs).formatToStorageSizePerSecond()
                        else ""
                        t.update(
                            dataType = type,
                            state = OperationState.DONE,
                            bytes = streamResult.fedBytes,
                            content = if (speedText.isNotEmpty()) "$speedText | $bytesText" else bytesText,
                        )
                        log { "[FdBackup] local fd-backup ok for ${p.packageName} $type snapshot=${streamResult.snapshotId}" }
                    } else {
                        t.update(
                            dataType = type,
                            state = OperationState.ERROR,
                            log = streamResult.shell.outString,
                        )
                        // 包级也记失败，否则完成页汇总觉得"没失败也没成功"
                        runCatching { t.update(state = OperationState.ERROR) }
                        log { "[FdBackup] local fd-backup failed for ${p.packageName} $type: ${streamResult.shell.outString}" }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    t.update(dataType = type, state = OperationState.ERROR, log = "canceled")
                    // 取消也要计入任务级失败——否则完成页显示"0成功0失败"且导航异常
                    runCatching {
                        t.update(state = OperationState.ERROR)
                        mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                    }
                    throw e
                } catch (e: Exception) {
                    // impl 侧抛的 CancellationException 过 binder 会被包成 IllegalStateException——
                    // 类型丢失但 message 保留（"rustic backup canceled: ..." / "cancelled by the user"）。
                    // 取消 ≠ 单 type 失败：命中消息匹配必须重抛 CancellationException，
                    // 否则上层继续跑剩余 dataType，"取消"只停掉了当前这一个。
                    val isCancel = e.message?.contains("rustic backup canceled") == true ||
                            e.message?.contains("cancelled by the user") == true ||
                            e.message?.contains("tar aborted via cancelFd") == true
                    t.update(
                        dataType = type,
                        state = OperationState.ERROR,   // UI 只有 5 态无 CANCELED，先用 ERROR 收圈
                        log = if (isCancel) "canceled" else (e.message ?: "unknown")
                    )
                    if (isCancel) {
                        runCatching {
                            t.update(state = OperationState.ERROR)
                            mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                        }
                        throw kotlinx.coroutines.CancellationException("backup canceled: ${e.message}")
                    }
                    log { "[FdBackup] local fd-backup exception for ${p.packageName} $type: ${e.message}" }
                } finally {
                    mCurrentBackupCancelId = 0L
                    mCurrentProcessingTag = null
                }
            }
        }

        t.update(dataType = type, progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    // 辅助方法：查找APK backup文件
    private fun findCompressedFile(dstDir: String, dataType: DataType): File? {
        val file = when (dataType) {
            DataType.PACKAGE_APK -> File("$dstDir/${DataType.PACKAGE_APK.type}.tar")
            DataType.PACKAGE_USER -> File("$dstDir/${DataType.PACKAGE_USER.type}.tar")
            DataType.PACKAGE_USER_DE -> File("$dstDir/${DataType.PACKAGE_USER_DE.type}.tar")
            DataType.PACKAGE_DATA -> File("$dstDir/${DataType.PACKAGE_DATA.type}.tar")
            DataType.PACKAGE_OBB -> File("$dstDir/${DataType.PACKAGE_OBB.type}.tar")
            DataType.PACKAGE_MEDIA -> File("$dstDir/${DataType.PACKAGE_MEDIA.type}.tar")
            DataType.PACKAGE_CONFIG -> File("$dstDir/package_restore_config.json")
            else -> null
        }

        return file?.takeIf {
            if (it.exists()) {
                log { "COMPRESSED_FILE_FOUND: Found compressed file for $dataType at ${it.absolutePath}" }
                true
            } else {
                log { "COMPRESSED_FILE_NOT_FOUND: No compressed file found for $dataType at ${it.absolutePath}" }
                false
            }
        }
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

    override suspend fun onCleanupIncompleteBackup(currentIndex: Int) {
        log { "Cleaning up incomplete local backup from index: $currentIndex" }

        mPkgEntities.forEachIndexed { index, pkg ->
            if (index >= currentIndex) {
                val localAppDir = "${mAppsDir}/${pkg.packageEntity.archivesRelativeDir}"
                log { "Cleaning up incomplete backup at: $localAppDir" }
                runCatching {
                    mRootService.deleteRecursively(localAppDir)
                }.onSuccess {
                    log { "Successfully cleaned up: $localAppDir" }
                }.onFailure { e ->
                    log { "Failed to cleanup: ${e.message}" }
                }
            }
        }

        log { "Cleaning up temporary directory: $mRootDir" }
        runCatching {
            mRootService.deleteRecursively(mRootDir)
        }.onSuccess {
            log { "Successfully deleted temporary directory" }
        }.onFailure { e ->
            log { "Failed to delete temporary directory: ${e.message}" }
        }

        cleanupStopFiles()
    }

    override suspend fun onTargetDirsCreated() {
        super.onTargetDirsCreated()
        // 备份前一次性仓库可用性检查：不通过直接终止本次备份
        if (!onPreBackupRepositoryCheck()) {
            throw IllegalStateException(mContext.getString(com.xayah.core.data.R.string.pre_backup_repository_check_failed))
        }
    }

    override suspend fun onPreBackupRepositoryCheck(): Boolean {
        // 1. OTG 前置对齐：非 OTG(内部存储/云端)直接放行；OTG 命中(必要时更新 repoPath)放行；
        //    扫不到目标 config_id 的挂载点(NotFound)或多盘歧义(Ambiguous)→ 保守中止，不猜路径写入
        if (!resolveAndAlignResticRepo()) {
            Log.e(mTAG, "备份前 OTG 仓库对齐失败：未匹配到目标 config_id 的挂载点或存在歧义，终止备份")
            return false
        }
        // 2. 对齐通过后再做原有的仓库可用性校验（仓库存在 / 密码正确 / config 可读）
        val repoPath = getResticRepoPath()
        val password = getResticPassword()
        val ok = resticRepo.verifyRepository(repoPath, password)
        if (!ok) {
            Log.e(mTAG, "备份前本地仓库检查失败: repoPath=$repoPath（仓库不存在/损坏/密码错/config 不可读）")
        }
        return ok
    }

    override suspend fun onIconsSaved(path: String, entity: ProcessingInfoEntity) {
        val iconFile = File(path)
        if (!iconFile.exists()) {
            log { "SAVE_ICONS: icon.tar not found at $path, skip icon snapshot" }
            return
        }

        try {
            val repoPath = getResticRepoPath()
            val password = getResticPassword()
            // 专用标签，非 user_X-包名-时间戳-类型 四段式，避免被 parseAppsDb 当成应用
            val tag = "__icons__-local-$mBackupTimestamp"

            val result = resticRepo.backupWithResticToLocal(
                repoPath = repoPath,
                password = password,
                filePath = path,
                tags = listOf(tag),
                cancelId = System.nanoTime()
            )

            if (result.first == 0) {
                log { "SAVE_ICONS: icon snapshot pushed to local repo, tag=$tag" }
            } else {
                log { "SAVE_ICONS: icon snapshot failed, code=${result.first}, msg=${result.second}" }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 图标失败不影响主备份流程
            log { "SAVE_ICONS: icon snapshot exception: ${e.message}" }
        }
    }

    override suspend fun clear() {
        log { "Attempting to delete local backup directory: $mRootDir" }
        val result = runCatching {
            mRootService.deleteRecursively(mRootDir)
        }
        if (result.isSuccess) {
            log { "Successfully deleted local backup directory" }
        } else {
            log { "Failed to delete local backup directory: ${result.exceptionOrNull()?.message}" }
        }

        cleanupStopFiles()
    }
}