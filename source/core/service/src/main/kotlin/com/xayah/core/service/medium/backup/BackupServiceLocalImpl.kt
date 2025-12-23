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

        if (result.isSuccess && t.mediaInfo.state != OperationState.SKIP) {
            Log.d(mTAG, "开始查找压缩文件...")
            val compressedFile = findCompressedFile(dstDir)
            if (compressedFile != null) {
                Log.d(mTAG, "COMPRESSED_FILE_FOUND: 找到压缩文件 ${compressedFile.absolutePath}")
                Log.d(mTAG, "文件大小: ${compressedFile.length()} bytes")

                Log.d(mTAG, "开始Restic备份...")
                val resticSuccess = backupWithRestic(m.name, compressedFile)
                Log.d(mTAG, "Restic备份结果: $resticSuccess")
            } else {
                Log.w(mTAG, "COMPRESSED_FILE_NOT_FOUND: 未找到压缩文件")
                Log.d(mTAG, "检查目录内容: ${File(dstDir).listFiles()?.map { it.name }}")
            }
        }

        t.update(progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
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