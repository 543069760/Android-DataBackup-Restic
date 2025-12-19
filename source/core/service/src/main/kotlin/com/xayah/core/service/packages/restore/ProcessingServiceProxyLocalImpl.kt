package com.xayah.core.service.packages.restore

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
        context.startForegroundService(intent)
    }
}