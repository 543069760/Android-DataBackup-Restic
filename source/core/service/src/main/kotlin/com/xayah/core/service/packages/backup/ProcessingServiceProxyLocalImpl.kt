package com.xayah.core.service.packages.backup

import android.content.Context
import android.content.Intent
import com.xayah.core.service.AbstractProcessingServiceProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ProcessingServiceProxyLocalImpl @Inject constructor() : AbstractProcessingServiceProxy() {
    @Inject
    @ApplicationContext
    override lateinit var context: Context

    override val intent by lazy { Intent(context, BackupServiceLocalImpl::class.java) }
    // 在所有其他 ProcessingServiceProxy 实现中添加
    override fun startRestore(packageName: String) {
        // 暂时不支持，抛出异常或记录日志
        throw UnsupportedOperationException("startRestore with package name not supported")
    }
    // 修复 startMediaRestore 方法（移除默认参数）
    override suspend fun startMediaRestore(mediaName: String) {
        throw UnsupportedOperationException("startMediaRestore not supported in backup service")
    }
}
