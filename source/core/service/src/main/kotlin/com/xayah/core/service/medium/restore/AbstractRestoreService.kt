package com.xayah.core.service.medium.restore

import android.annotation.SuppressLint
import com.xayah.core.datastore.readResetRestoreList
import com.xayah.core.datastore.saveLastRestoreTime
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
import com.xayah.core.service.R
import com.xayah.core.service.medium.AbstractMediumService
import com.xayah.core.service.util.MediumRestoreUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.NotificationUtil
import kotlinx.coroutines.flow.first

internal abstract class AbstractRestoreService : AbstractMediumService() {
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
        val medium = getMedium()
        medium.forEach { media ->
            mMediaEntities.add(
                TaskDetailMediaEntity(
                    taskId = mTaskEntity.id,
                    mediaEntity = media,
                    mediaInfo = Info(title = mContext.getString(R.string.args_restore, DataType.PACKAGE_MEDIA.type.uppercase())),
                ).apply {
                    id = mTaskDao.upsert(this)
                }
            )
        }
    }

    override suspend fun beforePreprocessing() {
        NotificationUtil.notify(mContext, mNotificationBuilder, mContext.getString(R.string.restoring), mContext.getString(R.string.preprocessing))
    }

    abstract suspend fun getMedium(): List<MediaEntity>
    abstract suspend fun restore(m: MediaEntity, t: TaskDetailMediaEntity, srcDir: String)
    protected open suspend fun clear() {}

    abstract val mMediumRestoreUtil: MediumRestoreUtil

    override suspend fun onPreprocessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.NECESSARY_PREPARATIONS -> {
                entity.update(progress = 1f, state = OperationState.DONE)
            }

            else -> {}
        }
    }

    override suspend fun onProcessing() {
        mTaskEntity.update(rawBytes = mTaskRepo.getRawBytes(TaskType.MEDIA), availableBytes = mTaskRepo.getAvailableBytes(OpType.RESTORE), totalBytes = mTaskRepo.getTotalBytes(OpType.RESTORE), totalCount = mMediaEntities.size)
        log { "Task count: ${mMediaEntities.size}." }

        // 每个文件固定 2 个阶段：开始 + 恢复完成（媒体恢复内部只有一次 restore 调用，无更细分的钩子）
        val stagesPerMedia = 2
        val totalStages = mMediaEntities.size * stagesPerMedia

        mMediaEntities.forEachIndexed { index, media ->
            // 当前文件内已完成的阶段数，以及按阶段推进任务栏进度条的局部函数
            var stageInMedia = 0
            fun notifyStage() {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.restoring),
                    media.mediaEntity.name,
                    totalStages,
                    index * stagesPerMedia + stageInMedia
                )
            }

            executeAtLeast {
                notifyStage()
                log { "Current media: ${media.mediaEntity}" }

                media.update(state = OperationState.PROCESSING)
                val m = media.mediaEntity
                val srcDir = "${mFilesDir}/${m.archivesRelativeDir}"
                restore(m = m, t = media, srcDir = srcDir)
                stageInMedia++
                notifyStage()

                if (media.isSuccess) {
                    media.update(mediaEntity = m)
                    mTaskEntity.update(successCount = mTaskEntity.successCount + 1)
                } else {
                    mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                }
                media.update(state = if (media.isSuccess) OperationState.DONE else OperationState.ERROR)
            }

            // 补齐到满阶段：即便中途异常也保证进度单调不回退
            stageInMedia = stagesPerMedia
            notifyStage()

            mTaskEntity.update(processingIndex = mTaskEntity.processingIndex + 1)
        }
    }

    override suspend fun onPostProcessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.restoring),
                    mContext.getString(R.string.wait_for_remaining_data_processing)
                )

                if (mContext.readResetRestoreList().first() && mTaskEntity.failureCount == 0) {
                    mMediaDao.clearActivated(OpType.RESTORE)
                }
                val isSuccess = runCatchingOnService { clear() }
                entity.update(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR)
            }

            else -> {}
        }
    }

    override suspend fun afterPostProcessing() {
        mContext.saveLastRestoreTime(mEndTimestamp)
        val time = DateUtil.getShortRelativeTimeSpanString(context = mContext, time1 = mStartTimestamp, time2 = mEndTimestamp)
        NotificationUtil.notify(
            mContext,
            mNotificationBuilder,
            mContext.getString(R.string.restore_completed),
            "${time}, ${mTaskEntity.successCount} ${mContext.getString(R.string.succeed)}, ${mTaskEntity.failureCount} ${mContext.getString(R.string.failed)}",
            ongoing = false
        )
    }
}
