package com.xayah.core.service.medium.restore

import android.util.Log
import android.content.Intent
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readResticActiveIsOtg
import com.xayah.core.datastore.readResticOtgPassword
import com.xayah.core.datastore.readResticOtgRepoPath
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.model.CloudType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.model.restic.ResticMediaRestoreQueueItem
import com.xayah.core.model.util.formatSize
import com.xayah.core.model.util.formatToStorageSizePerSecond
import com.xayah.core.restic.CloudResticBackend
import com.xayah.core.restic.ResticRepository
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.MediumRestoreUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@AndroidEntryPoint
internal class RestoreServiceLocalImpl @Inject constructor() : AbstractRestoreService() {
    override val mTAG: String = "RestoreServiceLocalImpl"

    private var mTargetMediaName: String = ""

    /** 媒体恢复队列：mediaName -> 队列项（含 media.tar 的 snapshotId/fullPath/accountName） */
    private var mMediaRestoreQueue: Map<String, ResticMediaRestoreQueueItem> = emptyMap()

    // 云端 restic 后端注册表：Hilt 多绑定 Map<CloudType, CloudResticBackend>
    // 注意 @JvmSuppressWildcards，否则 Dagger 生成 Map<CloudType, ? extends CloudResticBackend> 找不到绑定
    @Inject
    lateinit var resticBackends: Map<CloudType, @JvmSuppressWildcards CloudResticBackend>

    @Inject
    lateinit var mCloudRepo: CloudRepository

    // 本地 restic 仓库（accountName 为空的队列项走这里）
    @Inject
    lateinit var mResticRepo: ResticRepository

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

        // 读取媒体恢复队列（列表页「下一步」写入）。缺失/异常置空，兼容旧流程（media.tar 已预先解好）。
        try {
            val queueFile = File(mContext.cacheDir, MEDIA_RESTORE_QUEUE_FILE)
            if (queueFile.exists()) {
                val list = QUEUE_JSON.decodeFromString<List<ResticMediaRestoreQueueItem>>(queueFile.readText())
                mMediaRestoreQueue = list.groupBy { it.mediaName }.mapValues { it.value.first() }
                Log.d(mTAG, "读取媒体恢复队列成功，共 ${list.size} 条，媒体 ${mMediaRestoreQueue.size} 个")
            } else {
                mMediaRestoreQueue = emptyMap()
                Log.d(mTAG, "无媒体恢复队列文件，media.tar 需已预先解好")
            }
        } catch (e: Exception) {
            mMediaRestoreQueue = emptyMap()
            Log.e(mTAG, "读取媒体恢复队列失败，按旧流程处理", e)
        }

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

        // 使用传递的媒体名进行筛选（支持逗号拼接的多个媒体名，供文件恢复列表页批量恢复使用）
        val nameFilters = mTargetMediaName.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val media = if (nameFilters.isNotEmpty()) {
            allMedia.filter { it.name in nameFilters }
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

        // media.tar 可能尚未下载（列表页「下一步」只解 config 并统计预期大小），
        // 此时按队列从云端补解，实现「setup 之后才真正恢复」。
        val mediaFile = File("$actualSrcDir/media.tar")
        if (!mediaFile.exists() && !mRootService.exists(mediaFile.path)) {
            Log.d(mTAG, "media.tar 未落盘，按队列从云端补解: ${mediaFile.absolutePath}")
            if (!ensureMediaTar(m.name, actualSrcDir, t)) {
                Log.e(mTAG, "MEDIA Not exist: ${mediaFile.absolutePath}")
                t.update(state = OperationState.ERROR, log = "MEDIA Not exist: ${mediaFile.absolutePath}")
                return
            }
        }

        // 与 app 恢复一致：下载阶段定格的「速度 | 已下载 / 总大小」保留到解包/完成，
        // 解包阶段由 MediumRestoreUtil 把 state 切回 PROCESSING，完成后置 DONE。
        mMediumRestoreUtil.restoreMedia(m = m, t = t, srcDir = actualSrcDir)
        t.update(progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    /**
     * 按队列把 media.tar 解到 [dstDir]。
     * accountName 为空 → 本地 restic 仓库；否则走云端后端。
     * 队列项缺失（旧流程）或账户/密码解析失败时返回 false，由调用方报错。
     */
    private suspend fun ensureMediaTar(mediaName: String, dstDir: String, t: TaskDetailMediaEntity): Boolean {
        val item = mMediaRestoreQueue[mediaName]
        if (item == null) {
            Log.e(mTAG, "队列中无该媒体: $mediaName")
            return false
        }
        if (item.snapshotId.isEmpty()) {
            Log.e(mTAG, "队列项 snapshotId 为空: $mediaName")
            return false
        }
        val tStart = System.currentTimeMillis()

        // 与 app 恢复一致：下载阶段先置 PROCESSING，使卡片进度条门控 (state == PROCESSING && progress > 0f) 成立
        // 带 content 参数用于消歧（两个 update 重载都有 state）
        t.update(state = OperationState.PROCESSING, content = "")

        val bytesWrittenRef = AtomicLong(0L)
        val bytesTotalRef = AtomicLong(0L)
        val speedRef = AtomicLong(0L)
        val polling = AtomicBoolean(true)

        // 速度差分基准（仅回调线程访问）
        var lastTime = System.currentTimeMillis()
        var lastBytes = 0L

        val callback = object : ResticRepository.ResticProgressCallback {
            override fun onRestoreProgress(
                filesFinished: Long,
                filesTotal: Long,
                bytesWritten: Long,
                bytesTotal: Long,
                filesSkipped: Long,
                bytesSkipped: Long,
            ) {
                bytesWrittenRef.set(bytesWritten)
                bytesTotalRef.set(bytesTotal)
                val now = System.currentTimeMillis()
                val timeDiff = now - lastTime
                if (timeDiff > 0 && bytesWritten > lastBytes) {
                    speedRef.set((bytesWritten - lastBytes) * 1000 / timeDiff)
                }
                lastTime = now
                lastBytes = bytesWritten
            }

            override fun onBackupProgress(
                percentDone: Float,
                bytesDone: Long,
                bytesTotal: Long,
                filesDone: Long,
                filesTotal: Long,
                speed: Long,
            ) = Unit
        }
        val ok = coroutineScope {
            // 与 app 恢复一致：每 500ms 把「速度 | 已下载 / 总大小」+ 真实百分比写回卡片
            val pollingJob = launch {
                while (polling.get()) {
                    val speed = speedRef.get()
                    val speedText = if (speed > 0) speed.formatToStorageSizePerSecond() else ""
                    val written = bytesWrittenRef.get()
                    val total = bytesTotalRef.get()
                    val downloaded = written.toDouble().formatSize()
                    val totalText = total.toDouble().formatSize()
                    val content = if (speedText.isNotEmpty()) {
                        "$speedText | $downloaded / $totalText"
                    } else {
                        "$downloaded / $totalText"
                    }
                    val progress = if (total > 0) written.toFloat() / total else 0f
                    t.update(progress = progress, content = content)
                    delay(500)
                }
            }
            try {
                if (item.accountName.isBlank()) {
                    restoreLocalMediaTar(item, dstDir, callback)
                } else {
                    restoreCloudMediaTar(item, dstDir, callback)
                }
            } finally {
                polling.set(false)
                pollingJob.cancelAndJoin()
            }
        }
        // 与 app 恢复一致：下载结束定格最终速度/大小，state 切到 DOWNLOADING（绿色下载箭头），进度条满格
        val finalSpeed = speedRef.get()
        val finalSpeedText = if (finalSpeed > 0) finalSpeed.formatToStorageSizePerSecond() else ""
        val finalWritten = bytesWrittenRef.get()
        val finalTotal = bytesTotalRef.get()
        val finalContent = if (finalSpeedText.isNotEmpty()) {
            "$finalSpeedText | ${finalWritten.toDouble().formatSize()} / ${finalTotal.toDouble().formatSize()}"
        } else {
            "${finalWritten.toDouble().formatSize()} / ${finalTotal.toDouble().formatSize()}"
        }
        if (ok) {
            t.update(state = OperationState.DOWNLOADING, content = finalContent, progress = 1f)
        } else {
            // 与 app 恢复一致：失败也保留定格的速度/大小，错误详情走 log
            t.update(state = OperationState.ERROR, content = finalContent, progress = 1f)
        }
        Log.d(
            mTAG,
            "补解 media.tar 完成: media=$mediaName ok=$ok 耗时=${System.currentTimeMillis() - tStart}ms " +
                "source=${if (item.accountName.isBlank()) "local:${item.repoPath}" else "cloud:${item.accountName}"}"
        )
        return ok
    }

    /** 本地 restic 仓库：按 fullPath 精确命中，失败再退回裸名（fd 流快照） */
    private suspend fun restoreLocalMediaTar(
        item: ResticMediaRestoreQueueItem,
        dstDir: String,
        callback: ResticRepository.ResticProgressCallback,
    ): Boolean {
        // 队列项自带 repoPath（prepare 时写入）；旧队列缺失时按当前 OTG 状态回退，
        // 与 ResticFilesRestoreViewModel.resolveRestoreRepo 的判据保持一致。
        val repoPath = item.repoPath.ifBlank {
            if (mContext.readResticActiveIsOtg()) {
                mContext.readResticOtgRepoPath() ?: ""
            } else {
                mContext.readResticRepoPath() ?: ""
            }
        }
        if (repoPath.isEmpty()) {
            Log.e(mTAG, "本地仓库路径为空: ${item.mediaName}")
            return false
        }
        val password = resolveLocalRepoPassword(repoPath)
        if (password.isEmpty()) {
            Log.e(mTAG, "本地仓库密码为空: $repoPath")
            return false
        }
        val primary = item.fullPath.takeIf { it.isNotBlank() && it != "/" }
        val candidates: List<String?> = if (primary != null) listOf(primary, null) else listOf(null)
        for (subPath in candidates) {
            val ok = mResticRepo.restoreSnapshot(
                repoPath = repoPath,
                password = password,
                snapshotId = item.snapshotId,
                targetPath = dstDir,
                snapshotSubPath = subPath,
                includePath = "media.tar",
                progressCallback = callback,
            )
            val landed = File("$dstDir/media.tar").exists() || mRootService.exists("$dstDir/media.tar")
            Log.d(mTAG, "本地补解 media=${item.mediaName} subPath=${subPath ?: "<裸名>"} ok=$ok landed=$landed")
            if (ok && landed) return true
        }
        return false
    }

    /** 云端：按账户解析后端与密码 */
    private suspend fun restoreCloudMediaTar(
        item: ResticMediaRestoreQueueItem,
        dstDir: String,
        callback: ResticRepository.ResticProgressCallback,
    ): Boolean {
        val cloudEntity = mCloudRepo.queryByName(item.accountName)
        if (cloudEntity == null) {
            Log.e(mTAG, "云端账户未找到: ${item.accountName}")
            return false
        }
        val backend = resticBackends[cloudEntity.type]
        if (backend == null) {
            Log.e(mTAG, "无对应 restic 后端: ${cloudEntity.type}")
            return false
        }
        val password = backend.resolveResticPassword(cloudEntity)
        if (password.isEmpty()) {
            Log.e(mTAG, "云端 restic 密码解析失败: ${item.accountName}")
            return false
        }
        val subPath = item.fullPath.takeIf { it.isNotBlank() && it != "/" }
        return backend.restoreSnapshot(
            cloudEntity = cloudEntity,
            password = password,
            snapshotId = item.snapshotId,
            targetPath = dstDir,
            snapshotSubPath = subPath,
            includePath = "media.tar",
            progressCallback = callback,
        )
    }

    /**
     * 本地仓库密码：按仓库路径是否等于 OTG 路径选择对应密码。
     * 缺省值与全仓一致（databackup_default），否则 OTG 密码未落盘时会误判为「密码为空」而失败。
     */
    private suspend fun resolveLocalRepoPassword(repoPath: String): String {
        val otgPath = mContext.readResticOtgRepoPath()
        return if (!otgPath.isNullOrEmpty() && otgPath == repoPath) {
            mContext.readResticOtgPassword() ?: DEFAULT_RESTIC_PASSWORD
        } else {
            mContext.readResticPassword() ?: DEFAULT_RESTIC_PASSWORD
        }
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
        // 队列已消费完毕，删除避免下次启动误用
        val queueFile = File(mContext.cacheDir, MEDIA_RESTORE_QUEUE_FILE)
        if (queueFile.exists()) {
            Log.d(mTAG, "删除媒体恢复队列文件: ${queueFile.path}")
            queueFile.delete()
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

    companion object {
        /** 与 CloudFilesRestoreViewModel.MEDIA_RESTORE_QUEUE_FILE 保持一致 */
        private const val MEDIA_RESTORE_QUEUE_FILE = "restic_media_restore_queue.json"
        private val QUEUE_JSON = Json { ignoreUnknownKeys = true }

        /** 与全仓其它 restic 密码读取点保持一致的缺省值 */
        private const val DEFAULT_RESTIC_PASSWORD = "databackup_default"
    }
}
