package com.xayah.core.service.packages.restore

import android.os.Build
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

    // 实现支持包名参数的启动方法
    override fun startRestore(packageName: String) {
        val intent = Intent(context, RestoreServiceLocalImpl::class.java)
        if (packageName.isNotEmpty()) {
            intent.putExtra("TARGET_PACKAGE_NAME", packageName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
    // 修复 startMediaRestore 方法（移除默认参数）
    override suspend fun startMediaRestore(mediaName: String) {
        throw UnsupportedOperationException("startMediaRestore not supported in backup service")
    }
}