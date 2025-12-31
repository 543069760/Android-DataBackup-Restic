package com.xayah.core.service.medium.backup

import android.content.Context
import android.content.Intent
import com.xayah.core.service.AbstractProcessingServiceProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ProcessingServiceProxyCloudImpl @Inject constructor() : AbstractProcessingServiceProxy() {
    @Inject
    @ApplicationContext
    override lateinit var context: Context

    override val intent by lazy { Intent(context, BackupServiceCloudImpl::class.java) }

    // 保留现有的 startRestore 方法
    override fun startRestore(packageName: String) {
        throw UnsupportedOperationException("startRestore with package name not supported")
    }

    // 修复 startMediaRestore 方法（移除默认参数）
    override suspend fun startMediaRestore(mediaName: String) {
        throw UnsupportedOperationException("startMediaRestore not supported in backup service")
    }
}
