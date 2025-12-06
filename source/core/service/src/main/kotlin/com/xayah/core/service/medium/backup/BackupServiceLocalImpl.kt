package com.xayah.core.service.medium.backup

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
        val result = mMediumBackupUtil.backupMedia(
            m = m,
            t = t,
            r = r,
            dstDir = dstDir,
            isCanceled = { isCanceled() }
        )

        // 检查备份结果,如果失败则立即返回
        if (!result.isSuccess) {
            log { "COMPRESSION_FAILED: Backup compression failed for ${m.name}, skipping Restic backup" }
            return
        }

        // 新增：在压缩完成后使用 Restic 进行块备份
        if (result.isSuccess && t.mediaInfo.state != OperationState.SKIP) {
            // 查找压缩文件
            val compressedFile = findCompressedFile(dstDir)
            if (compressedFile != null) {
                log { "COMPRESSED_FILE_FOUND: Found compressed file at ${compressedFile.absolutePath}" }
                // 调用 Restic 备份
                val resticSuccess = backupWithRestic(m.name, compressedFile)
                if (resticSuccess) {
                    log { "Restic backup successful for ${m.name}" }
                } else {
                    log { "Restic backup failed for ${m.name}" }
                }
            } else {
                log { "COMPRESSED_FILE_NOT_FOUND: No compressed file found at ${dstDir}/media.tar.zst" }
            }
        }

        t.update(progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    // 辅助方法：查找压缩文件
    private fun findCompressedFile(dstDir: String): File? {
        val file = File("$dstDir/media.tar.zst")
        return if (file.exists()) {
            file
        } else {
            null
        }
    }

    override suspend fun onCleanupIncompleteBackup(currentIndex: Int) {
        log { "Cleaning up incomplete local file backup from index: $currentIndex" }

        mMediaEntities.forEachIndexed { index, media ->
            if (index >= currentIndex) {
                val localFileDir = "${mFilesDir}/${media.mediaEntity.archivesRelativeDir}"
                log { "Cleaning up incomplete backup at: $localFileDir" }
                runCatching {
                    mRootService.deleteRecursively(localFileDir)
                }.onSuccess {
                    log { "Successfully cleaned up: $localFileDir" }
                }.onFailure { e ->
                    log { "Failed to cleanup: ${e.message}" }
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