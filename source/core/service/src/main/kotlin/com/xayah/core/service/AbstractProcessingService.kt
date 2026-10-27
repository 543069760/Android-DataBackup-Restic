package com.xayah.core.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.SurfaceControlHidden
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.ConstantUtil.DEFAULT_IDLE_TIMEOUT
import com.xayah.core.datastore.readAutoScreenOff
import com.xayah.core.datastore.readScreenOffTimeout
import com.xayah.core.datastore.saveScreenOffCountDown
import com.xayah.core.datastore.saveScreenOffTimeout
import com.xayah.core.model.OperationState
import com.xayah.core.model.ProgressStat
import com.xayah.core.model.dedupRatioOf
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.model.util.set
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.rootservice.util.withIOContext
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.withLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal abstract class AbstractProcessingService : Service() {
    override fun onBind(intent: Intent): IBinder {
        startForegroundCompat()
        return mBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 会触发 onStartCommand，需在此处及时调用 startForeground
        startForegroundCompat()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, NotificationUtil.getForegroundNotification(applicationContext), FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, NotificationUtil.getForegroundNotification(applicationContext))
            }
        } catch (e: Exception) {
            LogUtil.log { mTAG to "startForeground failed: ${e.message}" }
        }
    }

    inner class OperationLocalBinder : Binder() {
        fun getService(): AbstractProcessingService = this@AbstractProcessingService
    }

    protected fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { mTAG to msg }
        msg
    }

    protected suspend fun runCatchingOnService(block: suspend () -> Unit): Boolean = runCatching { block() }.onFailure { mRootService.onFailure(it) }.withLog().isSuccess

    protected suspend fun ProcessingInfoEntity.update(
        bytes: Long? = null,
        log: String? = null,
        title: String? = null,
        content: String? = null,
        progress: Float? = null,
        state: OperationState? = null,
    ) {
        set(bytes, log, title, content, progress, state)
        mTaskDao.upsert(this)
    }

    protected suspend fun TaskEntity.update(
        startTimestamp: Long? = null,
        endTimestamp: Long? = null,
        rawBytes: Double? = null,
        availableBytes: Double? = null,
        totalBytes: Double? = null,
        totalCount: Int? = null,
        successCount: Int? = null,
        failureCount: Int? = null,
        preprocessingIndex: Int? = null,
        processingIndex: Int? = null,
        postProcessingIndex: Int? = null,
        isProcessing: Boolean? = null,
        cloud: String? = null,
        backupDir: String? = null,
    ) {
        set(startTimestamp, endTimestamp, rawBytes, availableBytes, totalBytes, totalCount, successCount, failureCount, preprocessingIndex, processingIndex, postProcessingIndex, isProcessing, cloud, backupDir)
        mTaskDao.upsert(this)
    }

    /**
     * To wait for animation.
     */
    protected suspend fun executeAtLeast(minTime: Int = 1000, block: suspend () -> Unit) {
        val startTimestamp = DateUtil.getTimestamp()
        block()
        val endTimestamp = DateUtil.getTimestamp()
        val diff = endTimestamp - startTimestamp
        if (diff < minTime) {
            delay(minTime - diff)
        }
    }

    abstract suspend fun onInitializingPreprocessingEntities(entities: MutableList<ProcessingInfoEntity>)
    abstract suspend fun onInitializingPostProcessingEntities(entities: MutableList<ProcessingInfoEntity>)
    abstract suspend fun onInitializing()
    protected open suspend fun beforePreprocessing() {}
    abstract suspend fun onPreprocessing(entity: ProcessingInfoEntity)
    protected open suspend fun afterPreprocessing() {}
    protected open suspend fun beforePostProcessing() {}
    abstract suspend fun onPostProcessing(entity: ProcessingInfoEntity)
    protected open suspend fun afterPostProcessing() {}
    abstract suspend fun onProcessing()

    private val mBinder = OperationLocalBinder()
    private val mMutex = Mutex()
    protected val mContext: Context by lazy { applicationContext }
    abstract val mTAG: String
    abstract val mRootService: RemoteRootService
    abstract val mPathUtil: PathUtil
    abstract val mCommonBackupUtil: CommonBackupUtil
    abstract val mTaskDao: TaskDao
    abstract val mTaskRepo: TaskRepository
    abstract val mTaskEntity: TaskEntity

    protected val mNotificationBuilder by lazy { NotificationUtil.getProgressNotificationBuilder(mContext) }
    private val mPreprocessingEntities: MutableList<ProcessingInfoEntity> = mutableListOf()
    private val mPostProcessingEntities: MutableList<ProcessingInfoEntity> = mutableListOf()
    private var mIsInitialized: Boolean = false
    protected var mStartTimestamp: Long = 0
    protected var mEndTimestamp: Long = 0

    // 取消标志
    @Volatile
    private var mIsCanceled = false

    /**
     * 请求取消当前操作(立即生效)
     */
    fun requestCancel() {
        log { "Cancel requested - immediate termination" }
        mIsCanceled = true
    }

    /**
     * 取消当前操作
     * 子类可以重写此方法来添加额外的取消逻辑(如写入 Rustic 停止文件)
     */
    open fun cancel() {
        requestCancel()
    }

    /**
     * 检查是否已请求取消
     */
    protected fun isCanceled(): Boolean = mIsCanceled

    /**
     * 内存实时进度：key 见 [progressStatKey]，值为该数据项的 { 源读取累积 / 去重后写入累积 / 速度 }。
     *
     * 与 Room 完全解耦——native（binder）线程只做一次 volatile 写，UI 侧直接收集，
     * 不产生任何 UPDATE 语句，也不引入新的数据库列或 schema 迁移。
     * 任务级/项级的最终持久化仍走现有列（见各备份实现结束时的 update）。
     */
    private val mProgressStats = MutableStateFlow<Map<String, ProgressStat>>(emptyMap())
    val progressStats: StateFlow<Map<String, ProgressStat>> = mProgressStats.asStateFlow()

    /**
     * 更新某个数据项的实时进度。可在任意线程调用（仅 volatile 写，无阻塞）。
     */
    fun updateProgressStat(key: String, stat: ProgressStat) {
        mProgressStats.value = HashMap(mProgressStats.value).apply { put(key, stat) }
    }

    /**
     * 定格某个数据项的终值。
     *
     * 源读取总量用 native 回传的 [total]（fedBytes）覆盖，
     * 而写入累积（increment）必须沿用过程中最后一次上报的真实写入量——
     * native 只回传 fedBytes，若这里把 increment 也写成 fedBytes，
     * 就会出现「增量 == 总量」的假象（全去重备份本应增量远小于总量）。
     *
     * @param key 见 [progressStatKey]
     * @param total 源读取总字节（fedBytes）
     * @param speed 平均速度（B/s）
     */
    fun freezeProgressStat(key: String, total: Long, speed: Long) {
        val increment = (mProgressStats.value[key]?.increment ?: 0L).coerceAtMost(total)
        updateProgressStat(
            key = key,
            stat = ProgressStat(
                total = total,
                increment = increment,
                speed = speed,
                dedupRatio = dedupRatioOf(total = total, increment = increment),
            ),
        )
    }

    /**
     * 清空内存进度快照。
     */
    fun clearProgressStats() {
        mProgressStats.value = emptyMap()
    }
    /**
     * 子类可重写此方法来实现具体的清理逻辑
     */
    protected open suspend fun onCleanupIncompleteBackup(currentIndex: Int) {}

    @SuppressLint("StringFormatInvalid")
    suspend fun initialize(): Long {
        mMutex.withLock {
            if (mIsInitialized.not()) {
                mTaskDao.upsert(mTaskEntity).apply {
                    // Initialize task id.
                    mTaskEntity.id = this
                }

                onInitializingPreprocessingEntities(mPreprocessingEntities)
                onInitializingPostProcessingEntities(mPostProcessingEntities)
                onInitializing()

                mIsInitialized = true
            }
            return mTaskEntity.id
        }
    }

    suspend fun preprocessing(): Unit = withIOContext {
        mMutex.withLock {
            mStartTimestamp = DateUtil.getTimestamp()
            mTaskEntity.update(startTimestamp = mStartTimestamp)
            log { "Preprocessing is starting." }
            beforePreprocessing()

            if (mContext.readAutoScreenOff().first()) {
                mContext.saveScreenOffTimeout(mRootService.getScreenOffTimeout())
                mContext.saveScreenOffCountDown(3)
            }

            mPreprocessingEntities.forEach {
                it.update(state = OperationState.PROCESSING)
                executeAtLeast {
                    onPreprocessing(it)
                }
                mTaskEntity.update(preprocessingIndex = mTaskEntity.preprocessingIndex + 1)
            }

            afterPreprocessing()
            mTaskEntity.update(processingIndex = mTaskEntity.processingIndex + 1)
        }
    }


    suspend fun processing() = withIOContext {
        mMutex.withLock {
            log { "Processing is starting." }
            onProcessing()
        }
    }

    suspend fun postProcessing() = withIOContext {
        // 边界日志：已进入 withIOContext（IO 线程切换完成），即将抢 mMutex。
        // 与抢锁后的 "PostProcessing is starting." 配合区分卡点：
        //   - 两条都不出现 → 卡在 withIOContext 调度 / 上游没调用 postProcessing()
        //   - 只有 entered、没有 starting → 卡在 mMutex.withLock 抢锁（processing() 还没放锁）
        //   - 两条都出现、后续卡住 → 卡在 beforePostProcessing / onPostProcessing(clear) / root 调用
        log { "postProcessing() entered, acquiring mutex" }
        mMutex.withLock {
            log { "PostProcessing is starting." }
            beforePostProcessing()

            mPostProcessingEntities.forEach {
                it.update(state = OperationState.PROCESSING)
                executeAtLeast {
                    onPostProcessing(it)
                }
                mTaskEntity.update(postProcessingIndex = mTaskEntity.postProcessingIndex + 1)
            }

            mRootService.setScreenOffTimeout(mContext.readScreenOffTimeout().first())
            mContext.saveScreenOffTimeout(DEFAULT_IDLE_TIMEOUT)
            mRootService.setDisplayPowerMode(SurfaceControlHidden.POWER_MODE_NORMAL)

            mEndTimestamp = DateUtil.getTimestamp()
            afterPostProcessing()
            mTaskEntity.update(endTimestamp = mEndTimestamp, isProcessing = false, processingIndex = mTaskEntity.processingIndex + 1)
        }
    }

    // 新增清理方法
    suspend fun cleanupIncompleteBackup(currentIndex: Int) = withIOContext {
        mMutex.withLock {
            log { "Cleaning up incomplete backup from index: $currentIndex" }
            onCleanupIncompleteBackup(currentIndex)
        }
    }
}