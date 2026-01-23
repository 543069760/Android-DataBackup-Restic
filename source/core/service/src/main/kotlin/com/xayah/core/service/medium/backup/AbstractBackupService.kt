package com.xayah.core.service.medium.backup

import android.util.Log
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
import com.xayah.core.service.R
import com.xayah.core.service.medium.AbstractMediumService
import com.xayah.core.service.util.MediumBackupUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.restic.ResticRepository
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.datastore.readResticPassword
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.delay
import java.io.File

@AndroidEntryPoint
internal abstract class AbstractBackupService : AbstractMediumService() {
    protected var mBackupTimestamp: Long = 0L

    @Inject
    protected lateinit var resticRepo: ResticRepository

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

    // Restic 辅助方法：获取仓库路径
    protected suspend fun getResticRepoPath(): String {
        return mContext.readResticRepoPath() ?: File(mFilesDir, "restic_repo").absolutePath
    }

    // Restic 辅助方法：生成密码
    protected suspend fun getResticPassword(): String {
        return mContext.readResticPassword() ?: "backup_${mBackupTimestamp}"
    }

    // Restic 无状态备份方法 - 支持DataType参数
    protected suspend fun backupWithRestic(
        mediaName: String,
        compressedFile: File,
        dataType: DataType
    ): Boolean {
        Log.d("ResticFlow", "backupWithRestic() ENTRY - mediaName: $mediaName, file: ${compressedFile.absolutePath}, type: $dataType")
        val repoPath = getResticRepoPath()
        val password = getResticPassword()

        // 动态检查仓库是否已初始化
        if (!resticRepo.checkRepository(repoPath, password)) {
            log { "Restic repository not initialized, skipping backup for $mediaName" }
            return false
        }

        return try {
            val filePath = compressedFile.absolutePath
            // 根据文件类型确定标签后缀
            val tagSuffix = when (dataType) {
                DataType.PACKAGE_MEDIA -> "filesbackup"
                DataType.PACKAGE_CONFIG -> "filesconfig"
                else -> "filesbackup"
            }

            // 新的文件备份标签格式：mediaName-timestamp-filesbackup/filesconfig
            val tag = "$mediaName-$mBackupTimestamp-$tagSuffix"
            val tags = listOf(tag)

            log { "Starting Restic backup for $mediaName with tag: $tag" }
            val result = resticRepo.backupWithResticToLocal(repoPath, password, filePath, tags)

            if (result.first == 0) {
                log { "Restic backup completed successfully for $mediaName" }
                val snapshotId = extractSnapshotIdFromJson(result.second)
                if (snapshotId != null) {
                    updateResticInfo(mediaName, snapshotId, dataType)
                } else {
                    Log.e("ResticFlow", "Failed to extract snapshot ID from: ${result.second}")
                }
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
    protected open suspend fun updateResticInfo(mediaName: String, snapshotId: String, dataType: DataType) {
        log { "Updated Restic info for $mediaName ($dataType): snapshotId=$snapshotId" }
    }

    // 从JSON输出中提取快照ID
    private fun extractSnapshotIdFromJson(jsonOutput: String): String? {
        return jsonOutput.lines()
            .find { it.contains("\"message_type\":\"summary\"") }
            ?.let { line ->
                Regex("\"snapshot_id\":\"([^\"]+)\"").find(line)?.groupValues?.get(1)
            }
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
            else -> {}
        }
    }

    override suspend fun onProcessing() {
        mTaskEntity.update(rawBytes = mTaskRepo.getRawBytes(TaskType.MEDIA), availableBytes = mTaskRepo.getAvailableBytes(OpType.BACKUP), totalBytes = mTaskRepo.getTotalBytes(OpType.BACKUP), totalCount = mMediaEntities.size)
        log { "Task count: ${mMediaEntities.size}." }

        for (index in mMediaEntities.indices) {
            if (isCanceled()) {
                log { "Backup canceled by user at media index: $index" }
                break
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

                    // 只有在未取消且备份成功时才保存配置和进行Restic备份
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

                        // 双文件Restic备份
                        val tarFile = File("$dstDir/media.tar")
                        val configFile = File("$dstDir/media_restore_config.json")

                        if (tarFile.exists() && configFile.exists()) {
                            Log.d("ResticFlow", "两个文件都存在，开始Restic备份: ${m.name}")

                            // 备份tar文件 - 使用新的标签格式
                            val tarSuccess = backupWithRestic("${m.name}-filesbackup", tarFile, DataType.PACKAGE_MEDIA)
                            Log.d("ResticFlow", "tar文件Restic备份结果: $tarSuccess")

                            // 备份配置文件 - 使用新的标签格式
                            val configSuccess = backupWithRestic("${m.name}-filesconfig", configFile, DataType.PACKAGE_CONFIG)
                            Log.d("ResticFlow", "配置文件Restic备份结果: $configSuccess")
                        } else {
                            Log.d("ResticFlow", "文件缺失，跳过Restic备份")
                        }

                        mTaskEntity.update(successCount = mTaskEntity.successCount + 1)
                    } else {
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

            if (isCanceled()) {
                log { "Backup canceled after media backup, skipping remaining items" }
                break
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