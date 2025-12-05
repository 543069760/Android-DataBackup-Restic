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
import java.io.File

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

    // 获取 Restic 版本
    suspend fun getVersion(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val args = listOf(resticPath, "version")
                val processBuilder = ProcessBuilder(args)
                val process = processBuilder.start()
                val output = InputStreamReader(process.inputStream).readText()
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    // 解析版本信息，例如 "restic 0.16.2 compiled with go1.21.0 on linux/amd64"
                    val versionLine = output.lines().firstOrNull()
                    versionLine?.substringAfter("restic ")?.substringBefore(" ")
                } else {
                    null
                }
            } catch (e: Exception) {
                logger.logCommandFailed(e)
                null
            }
        }
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

                val processBuilder = ProcessBuilder(args)
                processBuilder.environment()["RESTIC_PASSWORD"] = password

                val process = processBuilder.start()
                val output = InputStreamReader(process.inputStream).readText()
                val errorOutput = InputStreamReader(process.errorStream).readText()
                val exitCode = process.waitFor()

                val success = exitCode == 0
                val resultOutput = if (success) output else errorOutput

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
    ): Pair<Int, String> {
        return withContext(Dispatchers.IO) {
            try {
                val args = listOf(
                    resticPath, "backup", "--repo", repoPath,
                    filePath, "--tag", tags.joinToString(","),
                    "--json"
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
    // 验证仓库密码是否正确
    suspend fun validateRepository(repoPath: String, password: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 使用 snapshots 命令验证仓库和密码
                val args = listOf(resticPath, "snapshots", "--repo", repoPath, "--json")
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

    // 删除仓库
    suspend fun deleteRepository(repoPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val repoFile = File(repoPath)
                if (repoFile.exists()) {
                    repoFile.deleteRecursively()
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                logger.logCommandFailed(e)
                false
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