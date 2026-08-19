package com.xayah.feature.main.processing

import android.content.Context
import android.view.SurfaceControlHidden
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.datastore.readScreenOffCountDown
import com.xayah.core.model.OperationState
import com.xayah.core.model.ProcessingType
import com.xayah.core.model.StorageMode
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.AbstractProcessingServiceProxy
import com.xayah.core.ui.model.ProcessingCardItem
import com.xayah.core.ui.model.ProcessingDataCardItem
import com.xayah.core.ui.util.addInfo
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.util.LogUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext

data class IndexUiState(
    val state: OperationState,
    val storageIndex: Int,
    val storageType: StorageMode,
    val cloudEntity: CloudEntity?,
) : UiState

open class ProcessingUiIntent : UiIntent {
    data object Process : ProcessingUiIntent()
    data object Initialize : ProcessingUiIntent()
    data object DestroyService : ProcessingUiIntent()
    data object TurnOffScreen : ProcessingUiIntent()
    data object CancelAndCleanup : ProcessingUiIntent()
}

@ExperimentalCoroutinesApi
@ExperimentalMaterial3Api
abstract class AbstractProcessingViewModel(
    @ApplicationContext private val mContext: Context,
    private val mRootService: RemoteRootService,
    private val mTaskRepo: TaskRepository,
    protected val mLocalService: AbstractProcessingServiceProxy,
    protected val mCloudService: AbstractProcessingServiceProxy,
) : BaseViewModel<IndexUiState, ProcessingUiIntent, IndexUiEffect>(
    IndexUiState(
        state = OperationState.IDLE,
        storageIndex = 0,
        storageType = StorageMode.Local,
        cloudEntity = null
    )
) {

    companion object {
        private const val TAG = "AbstractProcessingViewModel"
        // 取消兜底：发出协作式取消后，等待任务真正结束的最长时间；超时即强杀 rootservice
        private const val CANCEL_GRACE_TIMEOUT_MS = 8000L
        private const val CANCEL_POLL_INTERVAL_MS = 200L
        // 硬杀 root 守护进程的保护性超时：即使 forceStopSelf 的 binder 调用本身也卡住，
        // 也不能让兜底流程被再次阻塞
        private const val FORCE_STOP_TIMEOUT_MS = 3000L
        // 兜底后 cleanup 的保护性超时，避免 cleanup 自身再次卡住
        private const val CLEANUP_TIMEOUT_MS = 3000L
        // 统一日志前缀，便于 logcat 过滤：adb logcat | grep CancelFallback
        private const val CANCEL_LOG = "CancelFallback"
    }

    private var mProcessJob: Job? = null

    private fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { TAG to msg }
        msg
    }

    open suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {}

    init {
        mRootService.onFailure = {
            val msg = it.message
            if (msg != null)
                emitEffectOnIO(IndexUiEffect.ShowSnackbar(message = msg))
        }
    }

    override suspend fun onEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            is ProcessingUiIntent.Initialize -> {
                _taskId.value = if (uiState.value.storageType == StorageMode.Cloud) mCloudService.initialize() else mLocalService.initialize()
            }

            is ProcessingUiIntent.Process -> {
                mProcessJob = coroutineContext[Job]
                try {
                    emitState(state.copy(state = OperationState.PROCESSING))
                    if (state.storageType == StorageMode.Cloud) {
                        // Cloud
                        mCloudService.preprocessing()
                        mCloudService.processing()
                        mCloudService.postProcessing()
                        mCloudService.destroyService()
                    } else {
                        // Local
                        mLocalService.preprocessing()
                        mLocalService.processing()
                        mLocalService.postProcessing()
                        mLocalService.destroyService()
                    }
                    emitState(state.copy(state = OperationState.DONE))
                } finally {
                    mProcessJob = null
                }
            }

            is ProcessingUiIntent.CancelAndCleanup -> {
                // 获取当前任务ID用于后续清理
                val currentTaskId = _taskId.value
                val currentIndex = task.value?.processingIndex ?: 0

                // 显示取消提示
                emitEffectOnIO(
                    IndexUiEffect.ShowSnackbar(
                        message = mContext.getString(R.string.canceling),
                        type = SnackbarType.Loading
                    )
                )

                // 1. 发出协作式取消（不阻塞，仅置标志/写 stop 文件/请求 cancelRusticBackup）
                val service = if (state.storageType == StorageMode.Cloud) mCloudService else mLocalService
                service.cancel()

                // 2. 轮询等待任务真正结束（协作式取消）
                val finishedInTime = withTimeoutOrNull(CANCEL_GRACE_TIMEOUT_MS) {
                    while (isActive && task.value?.isProcessing == true) {
                        delay(CANCEL_POLL_INTERVAL_MS)
                    }
                    true
                } ?: false

                // 3. 超时 => 底层网络调用卡死（restic 协作式令牌回不到检查点），强杀 rootservice 守护进程
                // 兜底：协作式取消在阈值内未生效（如 FTP 网络卡死，控制流回不到 check_cancel）
                if (!finishedInTime) {
                    // 协作式取消超时 → 硬杀 root 进程
                    log { "[CancelFallback] cooperative cancel timed out, force stopping daemon" }
                    withTimeoutOrNull(FORCE_STOP_TIMEOUT_MS) {
                        runCatching { mRootService.forceStopDaemon() }
                            .onSuccess { log { "[CancelFallback] forceStopDaemon done" } }
                            .onFailure { log { "[CancelFallback] forceStopDaemon threw (expected on process death): ${it.message}" } }
                    } ?: log { "[CancelFallback] forceStopDaemon itself timed out" }

                    // ★ 强杀 root 进程后，主动取消 Process 协程：
                    // ★ 此时卡死的同步 binder 调用已因进程死亡抛 DeadObjectException 解栈，
                    // ★ cancel 可在下一个挂起点阻止协程续跑 postProcessing/destroyService，避免重建 root。
                    log { "[CancelFallback] canceling Process job to prevent root re-spawn" }        // ★
                    mProcessJob?.cancel(CancellationException("User canceled backup"))               // ★
                } else {
                    log { "[CancelFallback] cooperative cancel finished in time, no force stop needed" }
                }

                // 4. 显示"删除中"提示
                emitEffectOnIO(
                    IndexUiEffect.ShowSnackbar(
                        message = mContext.getString(R.string.deleting_incomplete_backups),
                        type = SnackbarType.Loading
                    )
                )

                // 5. 清理未完成的备份（加保护性超时，避免强杀后 cleanup 再次卡住）
                log { "[$CANCEL_LOG] Calling cleanupIncompleteBackup with index: $currentIndex" }
                runCatching {
                    withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
                        service.cleanupIncompleteBackup(currentIndex)
                    } ?: log { "[$CANCEL_LOG] cleanupIncompleteBackup TIMEOUT, skipped" }
                }.onFailure { e ->
                    log { "[$CANCEL_LOG] cleanupIncompleteBackup error: ${e.message}" }
                }
                log { "[$CANCEL_LOG] cleanupIncompleteBackup done" }

                // 6. 解绑处理服务 + 清通知（app 进程侧）
                runCatching { service.destroyService(true) }
                    .onFailure { e -> log { "[$CANCEL_LOG] proxy destroyService error: ${e.message}" } }

                if (currentTaskId > 0) {
                    log { "[$CANCEL_LOG] deleting task record, taskId=$currentTaskId" }
                    mTaskRepo.deleteTask(currentTaskId)
                }
                _taskId.value = -1

                // 7. 关闭 loading 提示
                emitEffect(IndexUiEffect.DismissSnackbar)

                // 8. 更新状态为IDLE
                emitState(state.copy(state = OperationState.IDLE))

                // 9. 发送导航返回Effect
                emitEffect(IndexUiEffect.NavBack)
            }

            is ProcessingUiIntent.DestroyService -> {
                if (state.storageType == StorageMode.Cloud) {
                    mCloudService.destroyService(true)
                } else {
                    mLocalService.destroyService(true)
                }
            }

            is ProcessingUiIntent.TurnOffScreen -> {
                if (uiState.value.state == OperationState.PROCESSING) {
                    mRootService.setScreenOffTimeout(Int.MAX_VALUE)
                    mRootService.setDisplayPowerMode(SurfaceControlHidden.POWER_MODE_OFF)
                }
            }

            else -> {
                onOtherEvent(state, intent)
            }
        }  // 结束 when 表达式
    }

    protected abstract val _dataItems: Flow<List<ProcessingDataCardItem>>

    protected val _taskId: MutableStateFlow<Long> = MutableStateFlow(-1)
    private var _task: Flow<TaskEntity?> = _taskId.flatMapLatest { id -> mTaskRepo.queryTaskFlow(id).flowOnIO() }
    private val _preItemsProgress: MutableStateFlow<Float> = MutableStateFlow(0F)
    private var _preItems: Flow<List<ProcessingCardItem>> = _taskId.flatMapLatest { id ->
        mTaskRepo.queryProcessingInfoFlow(id, ProcessingType.PREPROCESSING)
            .map { infoList ->
                _preItemsProgress.value = infoList.sumOf { it.progress.toDouble() }.toFloat() / infoList.size
                val items = mutableListOf<ProcessingCardItem>()
                infoList.forEach {
                    items.addInfo(it)
                }
                items
            }
            .flowOnIO()
    }
    private val _postItemsProgress: MutableStateFlow<Float> = MutableStateFlow(0F)
    private val _postItems: Flow<List<ProcessingCardItem>> = _taskId.flatMapLatest { id ->
        mTaskRepo.queryProcessingInfoFlow(id, ProcessingType.POST_PROCESSING)
            .map { infoList ->
                _postItemsProgress.value = infoList.sumOf { it.progress.toDouble() }.toFloat() / infoList.size
                val items = mutableListOf<ProcessingCardItem>()
                infoList.forEach {
                    items.addInfo(it)
                }
                items
            }
            .flowOnIO()
    }
    private val _screenOffCountDown = mContext.readScreenOffCountDown().flowOnIO()

    val task: StateFlow<TaskEntity?> = _task.stateInScope(null)
    val preItemsProgress: StateFlow<Float> = _preItemsProgress.stateInScope(0F)
    val preItems: StateFlow<List<ProcessingCardItem>> = _preItems.stateInScope(listOf())
    val dataItems: StateFlow<List<ProcessingDataCardItem>> by lazy { _dataItems.stateInScope(listOf()) }
    val postItemsProgress: StateFlow<Float> = _postItemsProgress.stateInScope(0F)
    val postItems: StateFlow<List<ProcessingCardItem>> = _postItems.stateInScope(listOf())
    val screenOffCountDown: StateFlow<Int> = _screenOffCountDown.stateInScope(0)
}
