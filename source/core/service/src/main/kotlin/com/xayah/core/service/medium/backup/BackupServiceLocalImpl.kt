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
import com.xayah.core.model.util.formatSize
import com.xayah.core.model.util.formatToStorageSizePerSecond
import com.xayah.core.model.OperationState
import com.xayah.core.model.util.get
import com.xayah.core.model.DataType
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

    override suspend fun backupMediaConfig(
        configFile: File,
        m: MediaEntity,
        t: TaskDetailMediaEntity,
    ): Boolean = backupWithRestic(
        mediaName = m.name,
        compressedFile = configFile,
        dataType = DataType.PACKAGE_CONFIG,
        t = t,
    )

    override suspend fun streamBackupMedia(
        ready: MediumBackupUtil.ArgvResult.Ready,
        m: MediaEntity,
        t: TaskDetailMediaEntity,
        tag: String,
        cancelId: Long,
    ): MediumBackupUtil.StreamBackupResult {
        val repoPath = getResticRepoPath()
        val password = getResticPassword()
        // 节流时间戳：poll 回调频率高，≥500ms 才刷一次内存进度
        var lastContentUpdate = 0L
        return mMediumBackupUtil.streamBackupToRustic(
            ready = ready,
            m = m,
            repositoryPath = repoPath,
            password = password,
            tags = listOf(tag),
            cancelId = cancelId,
            // 实时进度只写内存 StateFlow（进程内），不再高频 upsert Room content
            onProgress = { readBytes, writtenSpeed, writtenBytes ->
                val now = System.currentTimeMillis()
                if (now - lastContentUpdate >= 500) {
                    lastContentUpdate = now
                    updateMediaProgressStat(
                        m = m,
                        dataType = DataType.PACKAGE_MEDIA,
                        total = readBytes,
                        increment = writtenBytes,
                        speed = writtenSpeed,
                    )
                }
            }
        )
    }

    override suspend fun onCleanupIncompleteBackup(currentIndex: Int) {
        Log.d(mTAG, "Cleaning up incomplete local file backup from index: $currentIndex")

        // 只按文件名精确清理本次未完成的 fork 中转产物（media.tar / media_restore_config.json）；
        // 绝不 deleteRecursively(mRootDir)——迁移窗口内 mRootDir 仍可能是与原版共享的 DataBackup，
        // 整删会误删原版数据。localFileDir 虽是子目录，但共享窗口内原版也用 files/<media>/ 结构，
        // 故不整删子目录，只删 fork 自己写入的两个文件名。
        mMediaEntities.forEachIndexed { index, media ->
            if (index >= currentIndex) {
                val localFileDir = "${mFilesDir}/${media.mediaEntity.archivesRelativeDir}"
                listOf("media.tar", "media_restore_config.json").forEach { name ->
                    val f = "$localFileDir/$name"
                    runCatching {
                        if (mRootService.exists(f)) mRootService.deleteRecursively(f)
                    }.onSuccess {
                        Log.d(mTAG, "Successfully cleaned up fork transit file: $f")
                    }.onFailure { e ->
                        Log.e(mTAG, "Failed to cleanup $f: ${e.message}")
                    }
                }
            }
        }

        log { "onCleanupIncompleteBackup: skip deleting shared root $mRootDir" }
        cleanupStopFiles()
    }

    override suspend fun clear() {
        // 流式/新链路不往共享根落中转；旧版 fork(中转式)媒体备份会留下 media.tar / media_restore_config.json。
        // mRootDir 与原版 DataBackup 共享（迁移窗口内尤甚），绝不整目录删除，只按文件名精确清理 fork 自己的中转文件。
        log { "clear(): skip deleting shared root $mRootDir, only remove fork media transit files" }
        cleanupForkMediaTransitFiles()
        cleanupStopFiles()
    }

    /** 清理 fork 自己写入的媒体中转产物（media.tar / media_restore_config.json），不触碰原版数据 */
    private suspend fun cleanupForkMediaTransitFiles() {
        mMediaEntities.forEach { media ->
            val dir = "${mFilesDir}/${media.mediaEntity.archivesRelativeDir}"
            listOf("media.tar", "media_restore_config.json").forEach { name ->
                val f = "$dir/$name"
                runCatching {
                    if (mRootService.exists(f)) mRootService.deleteRecursively(f)
                }.onSuccess { log { "清理旧版 fork 媒体中转文件: $f" } }
                    .onFailure { e -> log { "清理媒体中转文件失败 $f: ${e.message}" } }
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