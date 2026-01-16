package com.xayah.core.service.medium.backup

import android.util.Log
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
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoPath
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

    // 辅助方法：查找压缩文件
    private fun findCompressedFile(dstDir: String): File? {
        val tarFile = File("$dstDir/media.tar")
        val configFile = File("$dstDir/media_restore_config.json")

        Log.d(mTAG, "检查tar文件: ${tarFile.absolutePath}, 存在: ${tarFile.exists()}")
        Log.d(mTAG, "检查配置文件: ${configFile.absolutePath}, 存在: ${configFile.exists()}")

        return if (tarFile.exists() && configFile.exists()) {
            Log.d(mTAG, "两个文件都存在，返回tar文件进行Restic备份")
            tarFile
        } else {
            Log.d(mTAG, "文件缺失，跳过Restic备份")
            null
        }
    }


    // 获取 Restic 仓库路径
    protected suspend fun getResticRepoPath(): String {
        // 从 DataStore 读取用户配置的路径，与 ResticViewModel 保持一致
        return mContext.readResticRepoPath() ?: File(mFilesDir, "restic_repo").absolutePath
    }

    // 获取 Restic 密码（基于时间戳）
    protected suspend fun getResticPassword(): String {
        // 从 DataStore 读取用户配置的密码，如果没有则使用默认值
        return mContext.readResticPassword() ?: "backup_${mBackupTimestamp}"
    }

    // Restic 备份方法 - 更新为无状态调用
    protected suspend fun backupWithRestic(mediaName: String, compressedFile: File): Boolean {
        Log.d("ResticFlow", "=== 开始Restic备份: $mediaName ===")

        val repoPath = getResticRepoPath()
        val password = getResticPassword()

        Log.d("ResticFlow", "Restic仓库路径: $repoPath")
        Log.d("ResticFlow", "备份文件: ${compressedFile.absolutePath}")
        Log.d("ResticFlow", "备份时间戳: $mBackupTimestamp")

        if (!resticRepo.checkRepository(repoPath, password)) {
            Log.w("ResticFlow", "Restic仓库未初始化，跳过备份: $mediaName")
            return false
        }

        Log.d("ResticFlow", "Restic仓库已初始化，开始备份...")

        return try {
            val filePath = compressedFile.absolutePath
            val tag = "$mediaName-$mBackupTimestamp"
            val tags = listOf(tag)

            Log.d("ResticFlow", "Restic标签: $tag")
            Log.d("ResticFlow", "执行restic backup命令...")

            val result = resticRepo.backupWithResticToLocal(repoPath, password, filePath, tags)

            Log.d("ResticFlow", "Restic命令退出码: ${result.first}")
            Log.d("ResticFlow", "Restic命令输出: ${result.second}")

            if (result.first == 0) {
                Log.d("ResticFlow", "Restic备份成功: $mediaName")
                updateResticInfo(mediaName, result.second)
                true
            } else {
                Log.e("ResticFlow", "Restic备份失败: $mediaName, 错误: ${result.second}")
                false
            }
        } catch (e: Exception) {
            Log.e("ResticFlow", "Restic备份异常: $mediaName")
            Log.e("ResticFlow", "异常类型: ${e.javaClass.simpleName}")
            Log.e("ResticFlow", "异常信息: ${e.message}")
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
            else -> {}
        }
    }

    override suspend fun onProcessing() {
        mTaskEntity.update(rawBytes = mTaskRepo.getRawBytes(TaskType.MEDIA), availableBytes = mTaskRepo.getAvailableBytes(OpType.BACKUP), totalBytes = mTaskRepo.getTotalBytes(OpType.BACKUP), totalCount = mMediaEntities.size)
        Log.d(mTAG, "Task count: ${mMediaEntities.size}.")

        for (index in mMediaEntities.indices) {
            // 1. 循环开始时检查取消标志
            if (isCanceled()) {
                Log.d(mTAG, "Backup canceled by user at media index: $index")
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
                Log.d(mTAG, "Current media: ${media.mediaEntity}")

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

                        // 新增：双文件Restic备份
                        val tarFile = File("$dstDir/media.tar")
                        val configFile = File("$dstDir/media_restore_config.json")

                        if (tarFile.exists() && configFile.exists()) {
                            Log.d("ResticFlow", "两个文件都存在，开始Restic备份: ${m.name}")

                            // 备份tar文件
                            val tarSuccess = backupWithRestic("${m.name}-media", tarFile)
                            Log.d("ResticFlow", "tar文件Restic备份结果: $tarSuccess")

                            // 备份配置文件
                            val configSuccess = backupWithRestic("${m.name}-config", configFile)
                            Log.d("ResticFlow", "配置文件Restic备份结果: $configSuccess")
                        } else {
                            Log.d("ResticFlow", "文件缺失，跳过Restic备份")
                        }

                        mTaskEntity.update(successCount = mTaskEntity.successCount + 1)
                    } else {
                        // 备份失败,清理已上传的文件
                        Log.d(mTAG, "Backup failed for ${m.name}, cleaning up remote files...")
                        runCatching {
                            onCleanupFailedBackup(archivesRelativeDir = m.archivesRelativeDir)
                        }.onFailure { e ->
                            Log.e(mTAG, "Failed to cleanup remote files: ${e.message}")
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
                Log.d(mTAG, "Backup canceled after media backup, skipping remaining items")
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