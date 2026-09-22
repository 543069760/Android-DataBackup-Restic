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

    override suspend fun onTargetDirsCreated() {
        super.onTargetDirsCreated()
        if (!onPreBackupRepositoryCheck()) {
            throw IllegalStateException(mContext.getString(com.xayah.core.data.R.string.pre_backup_repository_check_failed))
        }
    }

    override suspend fun onPreBackupRepositoryCheck(): Boolean {
        // 先做 OTG 前置对齐:失败(未插盘/换盘/多盘歧义)直接判不通过,保守中止备份
        if (!resolveAndAlignResticRepo()) {
            Log.e(mTAG, "备份前置对齐失败,终止本次备份")
            return false
        }
        // 对齐后再读路径(可能已被更新为当前 OTG 挂载点),做仓库可用性/密码校验
        val repoPath = getResticRepoPath()
        val password = getResticPassword()
        val ok = resticRepo.verifyRepository(repoPath, password)
        if (!ok) {
            Log.e(mTAG, "备份前本地仓库检查失败: repoPath=$repoPath（仓库不存在/损坏/密码错/config 不可读）")
        }
        return ok
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

        log { "Cleaning up temporary directory: $mRootDir" }
        runCatching {
            mRootService.deleteRecursively(mRootDir)
        }.onSuccess {
            log { "Successfully deleted temporary directory" }
        }.onFailure { e ->
            log { "Failed to delete temporary directory: ${e.message}" }
        }

        // 【新增】清理停止文件
        cleanupStopFiles()
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

        // 【新增】清理停止文件
        cleanupStopFiles()
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