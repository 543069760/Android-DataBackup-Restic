package com.xayah.core.rclone

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RcloneRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: RcloneLogger,
    private val rcloneNative: RcloneNative
) {
    companion object {
        private const val TAG = "RcloneRepository"
        private const val SERVER_PROCESS_NAME = "rclone serve restic"
    }

    private val rclonePath: String by lazy {
        rcloneNative.getRcloneBinaryPath(context)
    }

    /**
     * 核心执行方法：使用 libsu 执行 Root 命令
     */
    private suspend fun executeRclone(
        vararg args: String,
        env: Map<String, String> = emptyMap()
    ): Shell.Result = withContext(Dispatchers.IO) {
        val defaultEnv = mutableMapOf(
            "HOME" to context.filesDir.absolutePath,
            "XDG_CONFIG_HOME" to File(context.filesDir, "rclone").absolutePath,
            "RCLONE_CONFIG" to File(File(context.filesDir, "rclone"), "rclone.conf").absolutePath,
            "GODEBUG" to "netdns=cgo"
        )
        defaultEnv.putAll(env)

        val envPrefix = defaultEnv.map { "${it.key}=\"${it.value}\"" }.joinToString(" ")

        // Join args properly.
        // Note: If args contain spaces (like the remote path), ensure they are quoted before passing here.
        val fullCommand = "$envPrefix $rclonePath ${args.joinToString(" ")}"

        Log.d(TAG, "Executing Root Command: $fullCommand")
        val result = Shell.cmd(fullCommand).exec()

        // 添加详细错误日志
        if (!result.isSuccess) {
            Log.e(TAG, "Command failed with exit code: ${result.code}")
            Log.e(TAG, "Standard output: ${result.out.joinToString("\n")}")
        }

        logger.logCommandResult(result.code, result.out.joinToString("\n"))
        result
    }

    /**
     * 获取 rclone 版本号
     */
    suspend fun getVersion(): String? {
        val result = executeRclone("-V")
        return if (result.isSuccess) {
            result.out.firstOrNull()?.substringAfter("rclone ")?.substringBefore(" ")
        } else null
    }

    /**
     * 启动 Rclone 服务器
     */
    suspend fun startRcloneServer(
        remote: String,
        path: String = "",
        addr: String = "127.0.0.1:38080",
        verbose: Boolean = true
    ): Shell.Result {
        logger.logResticServerStart(remote, addr)

        // DO NOT include rclonePath here, executeRclone adds it automatically
        val result = executeRclone(
            "serve",
            "restic",
            "$remote:$path",
            "--addr", addr,
            "--bind", "0.0.0.0",
            "-vv"
        )

        if (result.isSuccess) {
            logger.logResticServerStarted(addr)
        } else {
            logger.logCommandFailed(Exception("Failed to start rclone restic server"))
        }

        return result
    }

    /**
     * 停止 Rclone 服务器
     */
    suspend fun stopRcloneServer(): Shell.Result {
        logger.logResticServerStop()

        return try {
            // 查找并终止 rclone serve restic 进程
            val findProcessResult = Shell.cmd("pgrep -f \"$SERVER_PROCESS_NAME\"").exec()

            if (findProcessResult.isSuccess && findProcessResult.out.isNotEmpty()) {
                val pids = findProcessResult.out.map { it.trim() }
                val killResults = mutableListOf<Shell.Result>()

                pids.forEach { pid ->
                    val killResult = Shell.cmd("kill -TERM $pid").exec()
                    killResults.add(killResult)
                    Log.d(TAG, "Sent TERM signal to process $pid, exit code: ${killResult.code}")
                }

                // 等待进程优雅退出
                kotlinx.coroutines.delay(2000)

                // 检查进程是否仍在运行，强制终止
                val checkResult = Shell.cmd("pgrep -f \"$SERVER_PROCESS_NAME\"").exec()
                if (checkResult.isSuccess && checkResult.out.isNotEmpty()) {
                    val remainingPids = checkResult.out.map { it.trim() }
                    remainingPids.forEach { pid ->
                        Log.w(TAG, "Force killing process $pid")
                        Shell.cmd("kill -KILL $pid").exec()
                    }
                }

                logger.logResticServerStopped()
                Shell.cmd("echo 'Rclone restic server stopped'").exec()
            } else {
                Log.d(TAG, "No rclone restic server process found")
                Shell.cmd("echo 'No server process running'").exec()
            }
        } catch (e: Exception) {
            logger.logCommandFailed(e)
            Shell.cmd("echo 'Error stopping server: ${e.message}'").exec()
        }
    }
    /**
     * 检查服务器状态
     */
    suspend fun checkServerStatus(): Boolean {
        val result = Shell.cmd("pgrep -f \"$SERVER_PROCESS_NAME\"").exec()
        return result.isSuccess && result.out.isNotEmpty()
    }

    /**
     * 获取服务器监听地址
     */
    suspend fun getServerAddress(): String? {
        val result = Shell.cmd("netstat -tlnp | grep :38080").exec()
        return if (result.isSuccess) {
            result.out.firstOrNull()?.trim()
        } else null
    }
}