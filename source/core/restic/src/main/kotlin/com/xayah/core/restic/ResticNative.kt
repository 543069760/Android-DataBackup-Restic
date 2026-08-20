package com.xayah.core.restic

import android.content.Context
import android.os.Build
import android.util.Log
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

    /**
     * 获取 Restic 二进制路径
     * 适配 libsu：确保路径对 Root 可见，并强制刷新权限
     */
    fun getResticBinaryPath(context: Context): String {
        val privateBinaryFile = File(context.filesDir, "restic")
        val privateBinaryPath = privateBinaryFile.absolutePath

        // 1. 如果私有目录已存在二进制文件
        if (privateBinaryFile.exists()) {
            logger.logBinaryPathFound(privateBinaryPath)
            ensureExecutable(privateBinaryFile)
            return privateBinaryPath
        }

        // 2. 检查 lib 目录中的内置版本 (通常是作为 .so 库打包进来的)
        val libPath = context.applicationInfo.nativeLibraryDir
        // 这里的名称通常取决于你 CMake/ndk 编译时的输出名称
        val libResticFile = File(libPath, "librestic.so")

        if (libResticFile.exists()) {
            Log.d(TAG, "Found bundled binary in lib: ${libResticFile.absolutePath}")
            try {
                // 将内置版本复制到私有目录，因为 lib 目录的文件通常不可直接作为命令执行
                libResticFile.copyTo(privateBinaryFile, overwrite = true)
                ensureExecutable(privateBinaryFile)
                logger.logBinaryPathFound(privateBinaryPath)
                return privateBinaryPath
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy bundled binary", e)
                // 复制失败则尝试直接返回 lib 路径（虽然大概率会失败，但作为最后保底）
                return libResticFile.absolutePath
            }
        }

        // 3. 都不存在（JNI 方案下不再下载二进制）
        logger.logBinaryNotFound(privateBinaryPath)
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

    /**
     * 修改后的有效性检查
     * 只要物理存在即视为有效，执行权限由调用方 (libsu) 尝试修复
     */
    fun isPrivateBinaryValid(context: Context): Boolean {
        val binaryFile = File(context.filesDir, "restic")
        return binaryFile.exists() && binaryFile.length() > 0
    }
}