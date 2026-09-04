package com.xayah.core.service.medium.restore

import android.util.Log
import android.content.Intent
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.MediumRestoreUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import java.io.File

@AndroidEntryPoint
internal class RestoreServiceLocalImpl @Inject constructor() : AbstractRestoreService() {
    override val mTAG: String = "RestoreServiceLocalImpl"

    private var mTargetMediaName: String = ""

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
            opType = OpType.RESTORE,
            taskType = TaskType.MEDIA,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mTargetMediaName = intent?.getStringExtra("TARGET_MEDIA_NAME") ?: ""
        Log.d(mTAG, "接收到目标媒体名: $mTargetMediaName")
        return super.onStartCommand(intent, flags, startId)
    }

    override suspend fun getMedium(): List<MediaEntity> {
        // 检查是否为 Restic 恢复场景
        val restoreDir = File("${mRootDir}/restore")
        val backupDir = if (restoreDir.exists()) {
            Log.d(mTAG, "检测到 Restic 恢复场景，使用 restore 子目录")
            "${mRootDir}/restore/"
        } else {
            Log.d(mTAG, "使用标准恢复路径")
            mRootDir
        }

        Log.d(mTAG, "查询参数: cloud=, backupDir=$backupDir")
        val allMedia = mMediaRepo.queryActivated(OpType.RESTORE, "", backupDir)

        // 使用传递的媒体名进行筛选
        val media = if (mTargetMediaName.isNotEmpty()) {
            allMedia.filter { it.name == mTargetMediaName }
        } else {
            allMedia
        }

        Log.d(mTAG, "筛选后查询到 ${media.size} 个媒体")
        return media
    }

    override suspend fun restore(m: MediaEntity, t: TaskDetailMediaEntity, srcDir: String) {
        if (m.path.isEmpty()) {
            t.update(state = OperationState.ERROR, log = "Path is empty.")
            return
        }

        // 修正源目录路径，确保在Restic恢复场景下使用restore子目录
        val actualSrcDir = if (srcDir.contains("/files/") && !srcDir.contains("/restore/")) {
            val correctedDir = srcDir.replace("/files/", "/restore/files/")
            Log.d(mTAG, "修正源目录路径: $srcDir -> $correctedDir")
            correctedDir
        } else {
            srcDir
        }

        // 验证文件存在性
        val mediaFile = File("$actualSrcDir/media.tar")
        if (!mediaFile.exists()) {
            Log.e(mTAG, "MEDIA Not exist: ${mediaFile.absolutePath}")
            t.update(state = OperationState.ERROR, log = "MEDIA Not exist: ${mediaFile.absolutePath}")
            return
        }

        mMediumRestoreUtil.restoreMedia(m = m, t = t, srcDir = actualSrcDir)
        t.update(progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    override suspend fun clear() {
        if (mTaskEntity.failureCount != 0) {
            Log.d(mTAG, "存在失败项(failureCount=${mTaskEntity.failureCount})，保留中转目录用于排查/重试，跳过清理")
            return
        }
        val restoreFilesDir = "${mRootDir}/restore/files"
        if (File(restoreFilesDir).exists()) {
            Log.d(mTAG, "清理临时恢复目录: $restoreFilesDir")
            mRootService.deleteRecursively(restoreFilesDir)
        } else {
            Log.d(mTAG, "临时恢复目录不存在，跳过清理: $restoreFilesDir")
        }
    }

    @Inject
    override lateinit var mMediaDao: MediaDao

    @Inject
    override lateinit var mMediaRepo: MediaRepository

    @Inject
    override lateinit var mMediumRestoreUtil: MediumRestoreUtil

    override val mRootDir by lazy { mContext.localBackupSaveDir() }
    override val mFilesDir by lazy { mPathUtil.getLocalBackupFilesDir() }
    override val mConfigsDir by lazy { mPathUtil.getLocalBackupConfigsDir() }
}
