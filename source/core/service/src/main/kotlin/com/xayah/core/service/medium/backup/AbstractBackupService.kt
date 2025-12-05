package com.xayah.core.service.medium.backup

import android.annotation.SuppressLint
import com.xayah.core.common.util.toLineString
import com.xayah.core.datastore.readBackupConfigs
import com.xayah.core.datastore.readBackupItself
import com.xayah.core.datastore.readResetBackupList
import com.xayah.core.datastore.saveLastBackupTime
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.ProcessingInfoType
import com.xayah.core.model.ProcessingType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.Info
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.util.set
import com.xayah.core.restic.ResticRepository
import com.xayah.core.service.R
import com.xayah.core.service.medium.AbstractMediumService
import com.xayah.core.service.util.MediumBackupUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.PathUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

internal abstract class AbstractBackupService : AbstractMediumService() {
    protected var mBackupTimestamp: Long = 0L

    @Inject
    protected lateinit var resticRepo: ResticRepository
    private var isResticInitialized = false

    override suspend fun onInitializingPreprocessingEntities(entities: MutableList<ProcessingInfoEntity>) {
        entities.apply {
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.necessary_preparations),
                type = ProcessingType.PREPROCESSING,
                infoType = ProcessingInfoType.NECESSARY_PREPARATIONS
            ).apply {
                id = mTaskDao.upsert(this)
            })
            // 添加 Restic 初始化步骤
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = "Restic 初始化",
                type = ProcessingType.PREPROCESSING,
                infoType = ProcessingInfoType.RESTIC_INITIALIZATION
            ).apply {
                id = mTaskDao.upsert(this)
            })
        }
    }

    override suspend fun onInitializingPostProcessingEntities(entities: MutableList<ProcessingInfoEntity>) {
        entities.apply {
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.backup_itself),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.BACKUP_ITSELF
            ).apply {
                id = mTaskDao.upsert(this)
            })
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.necessary_remaining_data_processing),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING
            ).apply {
                id = mTaskDao.upsert(this)
            })
        }
    }

    @SuppressLint("StringFormatInvalid")
    override suspend fun onInitializing() {
        // 生成本次备份的统一时间戳
        mBackupTimestamp = DateUtil.getTimestamp()
        val medium = mMediaRepo.queryActivated(OpType.BACKUP)

        medium.forEach { media ->
            media.indexInfo.backupTimestamp = mBackupTimestamp
            mMediaEntities.add(
                TaskDetailMediaEntity(
                    taskId = mTaskEntity.id,
                    mediaEntity = media,
                    mediaInfo = Info(title = mContext.getString(R.string.args_backup, DataType.PACKAGE_MEDIA.type.uppercase())),
                ).apply {
                    id = mTaskDao.upsert(this)
                })
        }
    }

    override suspend fun beforePreprocessing() {
        NotificationUtil.notify(mContext, mNotificationBuilder, mContext.getString(R.string.backing_up), mContext.getString(R.string.preprocessing))
    }

    // 获取 Restic 仓库路径
    protected fun getResticRepoPath(): String {
        return "${mFilesDir}/restic"
    }

    // 获取 Restic 密码（基于时间戳）
    protected fun getResticPassword(): String {
        return "backup_${mBackupTimestamp}"
    }

    // Restic 初始化方法 - 更新为无状态调用
    protected suspend fun initResticRepository() {
        if (!isResticInitialized) {
            val repoPath = getResticRepoPath()
            val password = getResticPassword()

            resticRepo.initRepository(repoPath, password) { success, message ->
                if (success) {
                    isResticInitialized = true
                    log { "Restic repository initialized successfully" }
                } else {
                    log { "Failed to initialize Restic repository: $message" }
                }
            }
        }
    }

    // Restic 备份方法 - 更新为无状态调用
    protected suspend fun backupWithRestic(
        mediaName: String,
        compressedFile: java.io.File
    ): Boolean {
        if (!isResticInitialized) {
            log { "Restic not initialized, skipping backup for $mediaName" }
            return false
        }

        return try {
            val repoPath = getResticRepoPath()
            val password = getResticPassword()
            val filePath = compressedFile.absolutePath

            // 构建标签
            val mediaTag = "media:$mediaName"
            val timestampTag = "timestamp:$mBackupTimestamp"
            val tags = listOf(mediaTag, timestampTag, "compression:zstd")

            log { "Starting Restic backup for $mediaName: $filePath" }
            val result = resticRepo.backupFile(repoPath, password, filePath, tags)

            if (result.first == 0) {
                log { "Restic backup completed successfully for $mediaName" }
                // 更新数据库中的 Restic 信息
                updateResticInfo(mediaName, result.second)
                true
            } else {
                val errorMsg = result.second
                log { "Restic backup failed for $mediaName: $errorMsg" }
                false
            }
        } catch (e: Exception) {
            val baseMessage = "Error during Restic backup"
            log { "$baseMessage for $mediaName" }
            log { "Exception type: ${e.javaClass.simpleName}" }
            log { "Exception message: ${e.message}" }
            false
        }
    }

    // 更新 Restic 信息到数据库
    protected open suspend fun updateResticInfo(mediaName: String, snapshotInfo: String) {
        log { "Updated Restic info for $mediaName: $snapshotInfo" }
    }
    protected open suspend fun onTargetDirsCreated() {}
    protected open suspend fun onFileDirCreated(archivesRelativeDir: String): Boolean = true
    abstract suspend fun backup(m: MediaEntity, r: MediaEntity?, t: TaskDetailMediaEntity, dstDir: String)
    protected open suspend fun onConfigSaved(path: String, archivesRelativeDir: String) {}
    protected open suspend fun onItselfSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun onConfigsSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun clear() {}
    protected open suspend fun onCleanupFailedBackup(archivesRelativeDir: String) {}
    override suspend fun onCleanupIncompleteBackup(currentIndex: Int) {}
    protected abstract val mMediumBackupUtil: MediumBackupUtil

    override suspend fun onPreprocessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.NECESSARY_PREPARATIONS -> {
                log { "Trying to create: $mFilesDir." }
                mRootService.mkdirs(mFilesDir)
                val isSuccess = runCatchingOnService { onTargetDirsCreated() }
                entity.update(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR)
            }

            ProcessingInfoType.RESTIC_INITIALIZATION -> {
                log { "Initializing Restic for backup operations" }
                initResticRepository()
                entity.update(progress = 1f, state = if (isResticInitialized) OperationState.DONE else OperationState.ERROR)
            }

            else -> {}
        }
    }

    override suspend fun onProcessing() {
        mTaskEntity.update(rawBytes = mTaskRepo.getRawBytes(TaskType.MEDIA), availableBytes = mTaskRepo.getAvailableBytes(OpType.BACKUP), totalBytes = mTaskRepo.getTotalBytes(OpType.BACKUP), totalCount = mMediaEntities.size)
        log { "Task count: ${mMediaEntities.size}." }

        for (index in mMediaEntities.indices) {
            // 1. 循环开始时检查取消标志
            if (isCanceled()) {
                log { "Backup canceled by user at media index: $index" }
                break  // 直接退出循环
            }

            val media = mMediaEntities[index]
            executeAtLeast {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    media.mediaEntity.name,
                    mMediaEntities.size,
                    index
                )
                log { "Current media: ${media.mediaEntity}" }

                media.update(state = OperationState.PROCESSING)
                val m = media.mediaEntity
                val dstDir = "${mFilesDir}/${m.archivesRelativeDir}"
                var restoreEntity = mMediaDao.query(OpType.RESTORE, m.preserveId, m.name, m.indexInfo.compressionType, mTaskEntity.cloud, mTaskEntity.backupDir)
                mRootService.mkdirs(dstDir)

                if (onFileDirCreated(archivesRelativeDir = m.archivesRelativeDir)) {
                    // 执行备份
                    backup(m = m, r = restoreEntity, t = media, dstDir = dstDir)

                    // 只有在未取消且备份成功时才保存配置
                    if (media.isSuccess) {
                        // 保存配置文件和创建恢复记录
                        m.extraInfo.lastBackupTime = DateUtil.getTimestamp()
                        val id = restoreEntity?.id ?: 0
                        restoreEntity = m.copy(
                            id = id,
                            indexInfo = m.indexInfo.copy(opType = OpType.RESTORE, cloud = mTaskEntity.cloud, backupDir = mTaskEntity.backupDir),
                            extraInfo = m.extraInfo.copy(existed = true, activated = false)
                        )
                        val configDst = PathUtil.getMediaRestoreConfigDst(dstDir = dstDir)
                        mRootService.writeJson(data = restoreEntity, dst = configDst)
                        onConfigSaved(path = configDst, archivesRelativeDir = m.archivesRelativeDir)
                        mMediaDao.upsert(restoreEntity)
                        mMediaDao.upsert(m)
                        media.update(mediaEntity = m)
                        mTaskEntity.update(successCount = mTaskEntity.successCount + 1)
                    } else {
                        // 备份失败,清理已上传的文件
                        log { "Backup failed for ${m.name}, cleaning up remote files..." }
                        runCatching {
                            onCleanupFailedBackup(archivesRelativeDir = m.archivesRelativeDir)
                        }.onFailure { e ->
                            log { "Failed to cleanup remote files: ${e.message}" }
                        }
                        mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                    }

                    media.update(state = if (media.isSuccess) OperationState.DONE else OperationState.ERROR)
                } else {
                    media.update(state = OperationState.ERROR)
                }
            }

            // 2. 备份完成后检查取消标志(在保存配置前)
            if (isCanceled()) {
                log { "Backup canceled after media backup, skipping remaining items" }
                break  // 退出循环
            }

            mTaskEntity.update(processingIndex = mTaskEntity.processingIndex + 1)
        }
    }

    override suspend fun onPostProcessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.BACKUP_ITSELF -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.backup_itself)
                )
                if (mContext.readBackupItself().first()) {
                    log { "Backup itself enabled." }
                    mCommonBackupUtil.backupItself(dstDir = mRootDir).apply {
                        entity.set(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = outString)
                        if (isSuccess) {
                            onItselfSaved(path = mCommonBackupUtil.getItselfDst(mRootDir), entity = entity)
                        }
                    }
                    entity.update(progress = 1f)
                } else {
                    entity.update(progress = 1f, state = OperationState.SKIP)
                }
            }

            ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.wait_for_remaining_data_processing)
                )

                var isSuccess = true
                val out = mutableListOf<String>()
                if (mContext.readBackupConfigs().first()) {
                    log { "Backup configs enabled." }
                    mCommonBackupUtil.backupConfigs(dstDir = mConfigsDir).also { result ->
                        if (result.isSuccess.not()) {
                            isSuccess = false
                        }
                        out.add(result.outString)
                        if (result.isSuccess) {
                            onConfigsSaved(path = mCommonBackupUtil.getConfigsDst(mConfigsDir), entity = entity)
                        }
                    }
                }
                entity.update(progress = 0.5f)

                if (mContext.readResetBackupList().first() && mTaskEntity.failureCount == 0) {
                    mMediaDao.clearActivated(OpType.BACKUP)
                }
                if (runCatchingOnService { clear() }.not()) {
                    isSuccess = false
                }
                entity.set(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
            }

            else -> {}
        }
    }

    override suspend fun afterPostProcessing() {
        mContext.saveLastBackupTime(mEndTimestamp)
        val time = DateUtil.getShortRelativeTimeSpanString(context = mContext, time1 = mStartTimestamp, time2 = mEndTimestamp)
        NotificationUtil.notify(
            mContext,
            mNotificationBuilder,
            mContext.getString(R.string.backup_completed),
            "${time}, ${mTaskEntity.successCount} ${mContext.getString(R.string.succeed)}, ${mTaskEntity.failureCount} ${mContext.getString(R.string.failed)}",
            ongoing = false
        )
    }
}