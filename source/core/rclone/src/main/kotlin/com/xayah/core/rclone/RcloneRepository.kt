package com.xayah.core.rclone

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.xayah.core.datastore.readRclonePort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
     * 核心执行方法：增加耗时统计与详细流日志
     */
    private suspend fun executeRclone(
        vararg args: String,
        isServer: Boolean = false
    ): Shell.Result = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val filesDir = context.filesDir.absolutePath
        val rcloneDir = File(filesDir, "rclone").absolutePath

        val mainCommand = "$rclonePath ${args.joinToString(" ")}"

        // 后台执行逻辑逻辑
        val finalExecLine = if (isServer) {
            "nohup $mainCommand > /dev/null 2>&1 &"
        } else {
            mainCommand
        }

        val fullScript = """
            export HOME="$filesDir"
            export XDG_CONFIG_HOME="$rcloneDir"
            export RCLONE_CONFIG="$rcloneDir/rclone.conf"
            export GODEBUG="netdns=cgo"
            $finalExecLine
        """.trimIndent()

        Log.d(TAG, "┌────── Rclone Command Execution ──────")
        Log.d(TAG, "│ IsServer: $isServer")
        Log.d(TAG, "│ Script:\n$fullScript")

        val result = Shell.cmd(fullScript).exec()
        val duration = System.currentTimeMillis() - startTime

        Log.d(TAG, "│ Result Code: ${result.code} (Time: ${duration}ms)")

        if (result.out.isNotEmpty()) {
            Log.d(TAG, "│ Stdout: ${result.out.joinToString("\n│         ")}")
        }

        if (!result.isSuccess || result.err.isNotEmpty()) {
            // 即使成功，如果有 err 信息也记录（可能是警告）
            val logPriority = if (result.isSuccess) Log.WARN else Log.ERROR
            Log.println(logPriority, TAG, "│ Stderr: ${result.err.joinToString("\n│         ")}")
        }
        Log.d(TAG, "└──────────────────────────────────────")

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
     * 启动 Rclone 服务器：增加环境预检日志
     */
    suspend fun startRcloneServer(remote: String, path: String = ""): Shell.Result {
        Log.i(TAG, ">>> Attempting to start Rclone Server...")

        val port = context.readRclonePort().first()
        val addr = "127.0.0.1:$port"
        val logFile = File(context.cacheDir, "rclone_exec.log").absolutePath

        // 预检：检查配置文件是否存在
        val configPath = File(File(context.filesDir, "rclone"), "rclone.conf")
        Log.d(TAG, "Config Check: path=${configPath.absolutePath}, exists=${configPath.exists()}")

        val result = executeRclone(
            "serve", "restic", "\"$remote:$path\"",
            "--addr", addr,
            "--log-file", "\"$logFile\"",
            "--log-level", "DEBUG",
            "--bind", "0.0.0.0",
            "-vv",
            isServer = true
        )

        if (result.isSuccess) {
            logger.logResticServerStarted(addr)
            // 额外检查：启动后瞬间尝试 pgrep 确认进程是否真的在
            kotlinx.coroutines.delay(500) // 给系统一点点 fork 的时间
            val check = Shell.cmd("pgrep -f \"$SERVER_PROCESS_NAME\"").exec()
            Log.i(TAG, "Post-start check: Process active = ${check.out.isNotEmpty()}")
        } else {
            Log.e(TAG, "Server start failed directly from Shell.")
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
        // 从 DataStore 读取端口配置
        val port = context.readRclonePort().first()
        val result = Shell.cmd("netstat -tlnp | grep :$port").exec()
        return if (result.isSuccess) {
            result.out.firstOrNull()?.trim()
        } else null
    }
}