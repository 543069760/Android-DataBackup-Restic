package com.xayah.core.restic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class ResticRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ResticLogger,
    private val resticNative: ResticNative
) {
    companion object {
        private const val TAG = "ResticRepository"
        private val json = Json { ignoreUnknownKeys = true }
    }

    // 获取 restic 二进制路径
    private val resticPath: String by lazy {
        resticNative.getResticBinaryPath(context)
    }

    // 异步初始化仓库
    suspend fun initRepository(
        repoPath: String,
        password: String,
        callback: (Boolean, String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val args = listOf(resticPath, "init", "--repo", repoPath)

                // 修复 1 & 2: 正确声明 processBuilder 实例，并在其上设置环境变量
                val processBuilder = ProcessBuilder(args)
                processBuilder.environment()["RESTIC_PASSWORD"] = password

                val process = processBuilder.start()
                // 修复 3: 使用 InputStreamReader 解决 bufferedReader 的歧义
                val output = InputStreamReader(process.inputStream).readText()
                val errorOutput = InputStreamReader(process.errorStream).readText()
                val exitCode = process.waitFor()

                val success = exitCode == 0
                val resultOutput = if (success) output else errorOutput

                // 将日志记录移到 ResticRepository 内部
                logger.logCommandResult(exitCode, resultOutput)

                callback(success, resultOutput)
            } catch (e: Exception) {
                logger.logCommandFailed(e)
                callback(false, e.message ?: "Initialization failed")
            }
        }
    }

    // 备份文件
    suspend fun backupFile(
        repoPath: String,
        password: String,
        filePath: String,
        tags: List<String>
    ): Pair<Int, String> { // 推荐返回 Pair<Int, String> 以便上层获取快照ID或错误信息
        return withContext(Dispatchers.IO) {
            try {
                val args = listOf(
                    resticPath, "backup", "--repo", repoPath,
                    filePath, "--tag", tags.joinToString(","),
                    "--json" // 添加 --json 以便获取结构化输出，便于后续处理快照ID
                )

                val processBuilder = ProcessBuilder(args)
                processBuilder.environment()["RESTIC_REPOSITORY"] = repoPath
                processBuilder.environment()["RESTIC_PASSWORD"] = password

                val process = processBuilder.start()
                val output = InputStreamReader(process.inputStream).readText()
                val errorOutput = InputStreamReader(process.errorStream).readText()
                val exitCode = process.waitFor()

                val resultOutput = if (exitCode == 0) output else errorOutput
                logger.logCommandResult(exitCode, resultOutput)

                Pair(exitCode, resultOutput)
            } catch (e: Exception) {
                logger.logCommandFailed(e)
                Pair(-1, e.message ?: "Backup failed due to exception")
            }
        }
    }

    // 恢复快照
    suspend fun restoreSnapshot(
        repoPath: String,
        password: String,
        snapshotId: String,
        targetPath: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val args = listOf(
                    resticPath, "restore", snapshotId,
                    "--repo", repoPath, "--target", targetPath
                )

                val processBuilder = ProcessBuilder(args)
                processBuilder.environment()["RESTIC_REPOSITORY"] = repoPath
                processBuilder.environment()["RESTIC_PASSWORD"] = password

                val process = processBuilder.start()
                val output = InputStreamReader(process.inputStream).readText()
                val exitCode = process.waitFor()

                logger.logCommandResult(exitCode, output)
                exitCode == 0
            } catch (e: Exception) {
                logger.logCommandFailed(e)
                false
            }
        }
    }

    // 列出快照
    suspend fun listSnapshots(
        repoPath: String,
        password: String
    ): List<ResticSnapshot> {
        return withContext(Dispatchers.IO) {
            try {
                val args = listOf(resticPath, "snapshots", "--repo", repoPath, "--json")

                val processBuilder = ProcessBuilder(args)
                processBuilder.environment()["RESTIC_REPOSITORY"] = repoPath
                processBuilder.environment()["RESTIC_PASSWORD"] = password

                val process = processBuilder.start()
                val output = InputStreamReader(process.inputStream).readText()
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    json.decodeFromString<List<ResticSnapshot>>(output)
                } else {
                    logger.logCommandResult(exitCode, output)
                    emptyList()
                }
            } catch (e: Exception) {
                logger.logCommandFailed(e)
                emptyList()
            }
        }
    }

    // 检查仓库
    suspend fun checkRepository(
        repoPath: String,
        password: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val args = listOf(resticPath, "check", "--repo", repoPath)

                val processBuilder = ProcessBuilder(args)
                processBuilder.environment()["RESTIC_REPOSITORY"] = repoPath
                processBuilder.environment()["RESTIC_PASSWORD"] = password

                val process = processBuilder.start()
                val output = InputStreamReader(process.inputStream).readText()
                val exitCode = process.waitFor()

                logger.logCommandResult(exitCode, output)
                exitCode == 0
            } catch (e: Exception) {
                logger.logCommandFailed(e)
                false
            }
        }
    }
}

data class ResticSnapshot(
    val id: String,
    val time: String,
    val hostname: String,
    val paths: List<String>,
    val tags: List<String>
)