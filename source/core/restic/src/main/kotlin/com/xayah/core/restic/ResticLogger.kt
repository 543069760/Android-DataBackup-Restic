package com.xayah.core.restic

import android.util.Log
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.datastore.di.DbDispatchers.IO
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResticLogger @Inject constructor(
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher  // 修正：使用正确的注解
) {
    companion object {
        private const val TAG = "ResticBackup"
        private const val PREFIX = "[Restic]"
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

    // Restic 特定操作的日志方法
    fun logBinaryLoad(path: String) {
        i("Loading restic binary from: $path")
    }

    fun logBinaryNotFound(path: String) {
        e("Restic binary not found at: $path")
    }

    fun logRepositoryInit(repoPath: String) {
        i("Initializing restic repository: $repoPath")
    }

    fun logBackupStart(path: String) {
        i("Starting restic backup: $path")
    }

    fun logBackupProgress(percent: Double, files: Long, bytes: Long) {
        d("Backup progress: ${percent}%, files: $files, bytes: $bytes")
    }

    fun logBackupSuccess(snapshotId: String) {
        i("Restic backup completed successfully, snapshot: $snapshotId")
    }

    fun logBackupFailed(error: String) {
        e("Restic backup failed: $error")
    }

    fun logBinaryPathFound(path: String) {
        i("Getting restic binary path: $path")
    }

    fun logPermissionSetSuccess() {
        d("Set executable permission for restic binary")
    }

    fun logPermissionSetFailed(e: Exception) {
        w("Failed to set executable permission", e)
    }

    // 在 ResticLogger.kt 中添加以下方法
    fun logRepositoryInitStarted() {
        i("Starting restic repository initialization")
    }

    fun logRepositoryInitSuccess() {
        i("Restic repository initialized successfully")
    }

    fun logRepositoryInitFailed(error: String) {
        e("Failed to initialize restic repository: $error")
    }

    fun logBackupStarted(path: String) {
        i("Starting backup of: $path")
    }

    fun logRestoreStarted(snapshotId: String, targetPath: String) {
        i("Restoring snapshot $snapshotId to: $targetPath")
    }

    fun logSnapshotsListStarted() {
        i("Listing snapshots")
    }

    fun logSnapshotsParseFailed(e: Exception) {
        e("Failed to parse snapshots JSON", e)
    }

    fun logCommand(command: List<String>) {
        d("Executing command: ${command.joinToString(" ")}")
    }

    fun logCommandResult(exitCode: Int, output: String) {
        d("Command exit code: $exitCode, output: $output")
    }

    fun logCommandFailed(e: Exception) {
        e("Error executing restic command", e)
    }

    // 私有方法：格式化消息
    private fun formatMessage(message: String): String {
        return "$PREFIX $message"
    }
}