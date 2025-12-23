package com.xayah.core.service.medium.backup

import android.util.Log
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.OpType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.MediumBackupUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.model.OperationState
import com.xayah.core.model.util.get
import java.io.File
import dagger.hilt.android.AndroidEntryPoint
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
            taskType = TaskType.MEDIA,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    override suspend fun backup(m: MediaEntity, r: MediaEntity?, t: TaskDetailMediaEntity, dstDir: String) {
        Log.d(mTAG, "=== 开始文件备份: ${m.name} ===")

        val result = mMediumBackupUtil.backupMedia(
            m = m, t = t, r = r, dstDir = dstDir, isCanceled = { isCanceled() }
        )

        Log.d(mTAG, "tar打包结果: ${result.isSuccess}, 输出: ${result.outString}")

        if (!result.isSuccess) {
            Log.w(mTAG, "COMPRESSION_FAILED: Backup compression failed for ${m.name}, skipping Restic backup")
            return
        }

        t.update(progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }


    override suspend fun onCleanupIncompleteBackup(currentIndex: Int) {
        Log.d(mTAG, "Cleaning up incomplete local file backup from index: $currentIndex")

        mMediaEntities.forEachIndexed { index, media ->
            if (index >= currentIndex) {
                val localFileDir = "${mFilesDir}/${media.mediaEntity.archivesRelativeDir}"
                Log.d(mTAG, "Cleaning up incomplete backup at: $localFileDir")
                runCatching {
                    mRootService.deleteRecursively(localFileDir)
                }.onSuccess {
                    Log.d(mTAG, "Successfully cleaned up: $localFileDir")
                }.onFailure { e ->
                    Log.e(mTAG, "Failed to cleanup: ${e.message}")
                }
            }
        }
    }

    @Inject
    override lateinit var mMediaDao: MediaDao

    @Inject
    override lateinit var mMediaRepo: MediaRepository

    @Inject
    override lateinit var mMediumBackupUtil: MediumBackupUtil

    override val mRootDir by lazy { mContext.localBackupSaveDir() }
    override val mFilesDir by lazy { mPathUtil.getLocalBackupFilesDir() }
    override val mConfigsDir by lazy { mPathUtil.getLocalBackupConfigsDir() }
}