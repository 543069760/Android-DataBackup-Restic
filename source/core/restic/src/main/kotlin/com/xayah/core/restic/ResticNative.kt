package com.xayah.core.restic

import android.content.Context
import android.os.Build
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResticNative @Inject constructor(
    private val logger: ResticLogger
) {
    companion object {
        private const val TAG = "ResticNative"
    }

    // 获取对应架构的restic二进制路径
    fun getResticBinaryPath(context: Context): String {
        val abi = Build.SUPPORTED_ABIS[0]
        val libPath = context.applicationInfo.nativeLibraryDir
        val resticPath = "$libPath/librestic.so"

        logger.logBinaryPathFound(resticPath)

        if (!File(resticPath).exists()) {
            logger.logBinaryNotFound(resticPath)
            throw RuntimeException("Restic binary not found at: $resticPath")
        }

        // 确保文件有执行权限
        try {
            File(resticPath).setExecutable(true, false)
            logger.logPermissionSetSuccess()
        } catch (e: Exception) {
            logger.logPermissionSetFailed(e)
        }

        return resticPath
    }
}