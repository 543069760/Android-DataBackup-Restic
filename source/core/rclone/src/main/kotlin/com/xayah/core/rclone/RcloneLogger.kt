package com.xayah.core.rclone

import android.util.Log
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.datastore.di.DbDispatchers.IO
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RcloneLogger @Inject constructor(
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "RcloneBackup"
        private const val PREFIX = "[Rclone]"
    }

    // 基础日志方法
    fun v(message: String, throwable: Throwable? = null) {
        val logMessage = formatMessage(message)
        if (throwable != null) {
            Log.v(TAG, logMessage, throwable)
        } else {
            Log.v(TAG, logMessage)
        }
    }

    fun d(message: String, throwable: Throwable? = null) {
        val logMessage = formatMessage(message)
        if (throwable != null) {
            Log.d(TAG, logMessage, throwable)
        } else {
            Log.d(TAG, logMessage)
        }
    }

    fun i(message: String, throwable: Throwable? = null) {
        val logMessage = formatMessage(message)
        if (throwable != null) {
            Log.i(TAG, logMessage, throwable)
        } else {
            Log.i(TAG, logMessage)
        }
    }

    fun w(message: String, throwable: Throwable? = null) {
        val logMessage = formatMessage(message)
        if (throwable != null) {
            Log.w(TAG, logMessage, throwable)
        } else {
            Log.w(TAG, logMessage)
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        val logMessage = formatMessage(message)
        if (throwable != null) {
            Log.e(TAG, logMessage, throwable)
        } else {
            Log.e(TAG, logMessage)
        }
    }

    // 带线程信息的日志方法
    fun logThread(message: String) {
        val threadName = Thread.currentThread().name
        i("[$threadName] $message")
    }

    // 协程安全的日志方法
    suspend fun logSuspend(message: String) = withContext(ioDispatcher) {
        i(message)
    }

    // Rclone 特定操作的日志方法
    fun logBinaryLoad(path: String) {
        i("Loading rclone binary from: $path")
    }

    fun logBinaryNotFound(path: String) {
        e("Rclone binary not found at: $path")
    }

    fun logConfigInit(configPath: String) {
        i("Initializing rclone config: $configPath")
    }

    fun logSyncStart(remotePath: String, localPath: String) {
        i("Starting rclone sync: $remotePath -> $localPath")
    }

    fun logSyncProgress(percent: Double, files: Long, bytes: Long) {
        d("Sync progress: ${percent}%, files: $files, bytes: $bytes")
    }

    fun logSyncSuccess(transferredBytes: Long) {
        i("Rclone sync completed successfully, transferred: $transferredBytes bytes")
    }

    fun logSyncFailed(error: String) {
        e("Rclone sync failed: $error")
    }

    fun logBinaryPathFound(path: String) {
        i("Getting rclone binary path: $path")
    }

    fun logPermissionSetSuccess() {
        d("Set executable permission for rclone binary")
    }

    fun logPermissionSetFailed(e: Exception) {
        w("Failed to set executable permission", e)
    }

    // Rclone 配置相关日志方法
    fun logConfigInitStarted() {
        i("Starting rclone configuration initialization")
    }

    fun logConfigInitSuccess() {
        i("Rclone configuration initialized successfully")
    }

    fun logConfigInitFailed(error: String) {
        e("Failed to initialize rclone configuration: $error")
    }

    fun logSyncStarted(remote: String, local: String) {
        i("Starting sync from $remote to $local")
    }

    fun logRemoteListStarted(remote: String) {
        i("Listing remote files: $remote")
    }

    fun logRemoteListFailed(e: Exception) {
        e("Failed to list remote files", e)
    }

    fun logCommand(command: List<String>) {
        d("Executing command: ${command.joinToString(" ")}")
    }

    fun logCommandResult(exitCode: Int, output: String) {
        d("Command exit code: $exitCode, output: $output")
    }

    fun logResticServerStart(remote: String, addr: String) {
        i("Starting rclone restic server for $remote on $addr")
    }

    fun logResticServerStarted(addr: String) {
        i("Rclone restic server started successfully on $addr")
    }

    fun logResticServerStop() {
        i("Stopping rclone restic server")
    }

    fun logResticServerStopped() {
        i("Rclone restic server stopped")
    }

    fun logCommandFailed(e: Exception) {
        e("Error executing rclone command", e)
    }

    // 私有方法：格式化消息
    private fun formatMessage(message: String): String {
        return "$PREFIX $message"
    }
}