package com.xayah.core.restic

import android.content.Context
import android.os.Build
import android.util.Log  // 添加标准 Log 导入
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResticNative @Inject constructor(
    private val logger: ResticLogger
) {
    companion object {
        private const val TAG = "ResticNative"
        private const val DOWNLOAD_PREFS_NAME = "restic_download"
        private const val NEED_DOWNLOAD_KEY = "need_download"
    }

    // 获取对应架构的restic二进制路径
    fun getResticBinaryPath(context: Context): String {
        // 优先使用私有目录中的二进制文件
        val privateBinaryPath = File(context.filesDir, "restic").absolutePath
        val privateBinaryFile = File(privateBinaryPath)

        if (privateBinaryFile.exists()) {
            logger.logBinaryPathFound(privateBinaryPath)

            // 确保文件有执行权限
            try {
                privateBinaryFile.setExecutable(true, false)
                logger.logPermissionSetSuccess()
            } catch (e: Exception) {
                logger.logPermissionSetFailed(e)
            }

            return privateBinaryPath
        }

        // 私有目录中不存在，检查lib目录中的内置版本
        val abi = Build.SUPPORTED_ABIS[0]
        val libPath = context.applicationInfo.nativeLibraryDir
        val libResticPath = "$libPath/librestic.so"
        val libResticFile = File(libResticPath)

        if (libResticFile.exists()) {
            logger.logBinaryPathFound(libResticPath)

            // 将内置版本复制到私有目录
            try {
                libResticFile.copyTo(privateBinaryFile, overwrite = true)
                privateBinaryFile.setExecutable(true, false)
                logger.logPermissionSetSuccess()
                logger.logBinaryPathFound(privateBinaryPath)
                return privateBinaryPath
            } catch (e: Exception) {
                logger.logPermissionSetFailed(e)
                // 复制失败，直接返回lib路径
                return libResticPath
            }
        }

        // 内置版本也不存在，触发下载流程
        logger.logBinaryNotFound(privateBinaryPath)
        triggerDownloadFlow(context)

        // 返回私有目录路径（下载完成后会使用）
        return privateBinaryPath
    }

    private fun triggerDownloadFlow(context: Context) {
        // 通过SharedPreferences标记需要下载
        val prefs = context.getSharedPreferences(DOWNLOAD_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(NEED_DOWNLOAD_KEY, true).apply()
        Log.d(TAG, "Download flow triggered for Restic binary")  // 修改为标准 Log
    }

    fun isDownloadNeeded(context: Context): Boolean {
        val prefs = context.getSharedPreferences(DOWNLOAD_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(NEED_DOWNLOAD_KEY, false)
    }

    fun clearDownloadFlag(context: Context) {
        val prefs = context.getSharedPreferences(DOWNLOAD_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(NEED_DOWNLOAD_KEY).apply()
        Log.d(TAG, "Download flag cleared")  // 修改为标准 Log
    }

    // 检查私有目录中的二进制文件是否存在且可执行
    fun isPrivateBinaryValid(context: Context): Boolean {
        val binaryFile = File(context.filesDir, "restic")
        return binaryFile.exists() && binaryFile.canExecute()
    }
}