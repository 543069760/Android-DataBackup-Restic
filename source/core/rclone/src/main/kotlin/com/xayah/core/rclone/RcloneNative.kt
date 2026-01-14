package com.xayah.core.rclone

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RcloneNative @Inject constructor(
    private val logger: RcloneLogger
) {
    companion object {
        private const val TAG = "RcloneNative"
        private const val DOWNLOAD_PREFS_NAME = "rclone_download"
        private const val NEED_DOWNLOAD_KEY = "need_download"
    }

    /**
     * 获取 Rclone 二进制路径
     * 适配 libsu：确保路径对 Root 可见，并强制刷新权限
     */
    fun getRcloneBinaryPath(context: Context): String {
        val rcloneDir = File(context.filesDir, "rclone")
        val privateBinaryFile = File(rcloneDir, "rclone")
        val privateBinaryPath = privateBinaryFile.absolutePath

        // 1. 确保目录存在
        if (!rcloneDir.exists()) {
            rcloneDir.mkdirs()
        }

        // 2. 如果私有目录已存在二进制文件
        if (privateBinaryFile.exists()) {
            logger.logBinaryPathFound(privateBinaryPath)
            ensureExecutable(privateBinaryFile)
            return privateBinaryPath
        }

        // 2. 检查 lib 目录中的内置版本 (通常是作为 .so 库打包进来的)
        val libPath = context.applicationInfo.nativeLibraryDir
        // 这里的名称通常取决于你 CMake/ndk 编译时的输出名称
        val libRcloneFile = File(libPath, "librclone.so")

        if (libRcloneFile.exists()) {
            Log.d(TAG, "Found bundled binary in lib: ${libRcloneFile.absolutePath}")
            try {
                // 将内置版本复制到私有目录，因为 lib 目录的文件通常不可直接作为命令执行
                libRcloneFile.copyTo(privateBinaryFile, overwrite = true)
                ensureExecutable(privateBinaryFile)
                logger.logBinaryPathFound(privateBinaryPath)
                return privateBinaryPath
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy bundled binary", e)
                // 复制失败则尝试直接返回 lib 路径（虽然大概率会失败，但作为最后保底）
                return libRcloneFile.absolutePath
            }
        }

        // 3. 都不存在，触发下载
        logger.logBinaryNotFound(privateBinaryPath)
        triggerDownloadFlow(context)

        return privateBinaryPath
    }

    /**
     * 强制设置权限
     * 关键点：setExecutable(true, false) 中的 false 意味着所有用户（包括 Root）都能执行
     */
    private fun ensureExecutable(file: File) {
        try {
            if (file.exists()) {
                file.setReadable(true, false)
                file.setExecutable(true, false)
                Log.d(TAG, "Permissions set for ${file.name}: R-X for all")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set permissions", e)
        }
    }

    private fun triggerDownloadFlow(context: Context) {
        val prefs = context.getSharedPreferences(DOWNLOAD_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(NEED_DOWNLOAD_KEY, true).apply()
        Log.d(TAG, "Download flow triggered")
    }

    fun isDownloadNeeded(context: Context): Boolean {
        val prefs = context.getSharedPreferences(DOWNLOAD_PREFS_NAME, Context.MODE_PRIVATE)
        val binaryMissing = !File(File(context.filesDir, "rclone"), "rclone").exists()
        return prefs.getBoolean(NEED_DOWNLOAD_KEY, false) || binaryMissing
    }

    fun clearDownloadFlag(context: Context) {
        val prefs = context.getSharedPreferences(DOWNLOAD_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(NEED_DOWNLOAD_KEY).apply()
        Log.d(TAG, "Download flag cleared")
    }

    /**
     * 修改后的有效性检查
     * 只要物理存在即视为有效，执行权限由调用方 (libsu) 尝试修复
     */
    fun isPrivateBinaryValid(context: Context): Boolean {
        val binaryFile = File(File(context.filesDir, "rclone"), "rclone")
        return binaryFile.exists() && binaryFile.length() > 0
    }
}