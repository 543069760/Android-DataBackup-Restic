package com.xayah.core.work.workers

import android.util.Log
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.work.ProgressThrottle
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.util.NotificationUtil
import com.xayah.core.work.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@HiltWorker
internal class AppsFastInitWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val appsRepo: AppsRepo,
) : CoroutineWorker(appContext, workerParams) {
    private val mNotificationBuilder by lazy { NotificationUtil.getProgressNotificationBuilder(appContext) }
    private var mNotificationInfo: ForegroundInfo? = null

    override suspend fun getForegroundInfo(): ForegroundInfo {
        if (mNotificationInfo == null) {
            mNotificationInfo = NotificationUtil.createForegroundInfo(
                appContext,
                mNotificationBuilder,
                appContext.getString(R.string.initializing_app_list),
                ""
            )
        }

        return mNotificationInfo!!
    }

    override suspend fun doWork(): Result = withContext(defaultDispatcher) {
        Log.d("LoadDiag", "[Worker] AppsFastInitWorker start")
        try {
            val throttle = ProgressThrottle()
            val onProgress: suspend (Int, Int, String) -> Unit = { cur, max, content ->
                Log.d("LoadDiag", "[Worker] AppsFastInitWorker progress $cur/$max: $content")
                if (throttle.shouldEmit(cur, max)) {
                    mNotificationInfo = NotificationUtil.createForegroundInfo(
                        appContext,
                        mNotificationBuilder,
                        appContext.getString(R.string.initializing_app_list),
                        content,
                        max,
                        cur
                    )
                    setForeground(mNotificationInfo!!)
                }
            }
            // 无变化时 syncInstalledApps 直接返回 true 且不产生任何进度回调。
            if (!appsRepo.syncInstalledApps(forceFull = false, onInit = onProgress)) {
                appsRepo.fastInitialize(onProgress)
            }
            Log.d("LoadDiag", "[Worker] AppsFastInitWorker done")
            Result.success()
        } catch (e: Throwable) {
            Log.e("LoadDiag", "[Worker] AppsFastInitWorker failed", e)
            Result.failure()
        }
    }

    companion object {
        fun buildRequest() = OneTimeWorkRequestBuilder<AppsFastInitWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    }
}
