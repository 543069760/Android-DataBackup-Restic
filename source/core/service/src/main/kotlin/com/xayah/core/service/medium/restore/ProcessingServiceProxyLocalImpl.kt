package com.xayah.core.service.medium.restore

import android.content.Context
import android.content.Intent
import com.xayah.core.service.AbstractProcessingServiceProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ProcessingServiceProxyLocalImpl @Inject constructor() : AbstractProcessingServiceProxy() {
    @Inject
    @ApplicationContext
    override lateinit var context: Context

    override val intent by lazy { Intent(context, RestoreServiceLocalImpl::class.java) }

    // 修复 startRestore 方法（移除 suspend 关键字）
    override fun startRestore(packageName: String) {
        throw UnsupportedOperationException("Package restore not supported in media service")
    }

    // 保留 startMediaRestore 方法
    override suspend fun startMediaRestore(mediaName: String) {
        val intent = Intent(context, RestoreServiceLocalImpl::class.java)
        if (mediaName.isNotEmpty()) {
            intent.putExtra("TARGET_MEDIA_NAME", mediaName)
        }
        context.startService(intent)
    }
}