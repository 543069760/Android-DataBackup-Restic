package com.xayah.core.restic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import com.xayah.core.model.restic.ResticBackupApp  // 添加导入
import com.xayah.core.model.DataType              // 添加导入

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

    suspend fun listSnapshots(
        repoPath: String,
        password: String
    ): List<ResticSnapshot> {
        return withContext(Dispatchers.IO) {
            try {
                // 添加查询开始日志
                Log.d(TAG, "开始查询快照: repoPath=$repoPath")

                val args = listOf(resticPath, "snapshots", "--repo", repoPath, "--json")
                // 添加命令参数日志
                Log.d(TAG, "执行命令: ${args.joinToString(" ")}")

                val processBuilder = ProcessBuilder(args)
                processBuilder.environment()["RESTIC_REPOSITORY"] = repoPath
                processBuilder.environment()["RESTIC_PASSWORD"] = password

                val process = processBuilder.start()
                val output = InputStreamReader(process.inputStream).readText()
                val exitCode = process.waitFor()

                // 添加命令执行结果日志
                Log.d(TAG, "命令执行完成: exitCode=$exitCode, output长度=${output.length}")

                if (exitCode == 0) {
                    val snapshots = json.decodeFromString<List<ResticSnapshot>>(output)
                    // 添加解析成功日志
                    Log.d(TAG, "快照解析成功: 共${snapshots.size}个快照")
                    snapshots.forEach { snapshot ->
                        Log.d(TAG, "快照详情: id=${snapshot.id}, time=${snapshot.time}, paths=${snapshot.paths}, tags=${snapshot.tags}")
                    }
                    snapshots
                } else {
                    // 添加错误日志
                    Log.e(TAG, "快照查询失败: exitCode=$exitCode, output=$output")
                    logger.logCommandResult(exitCode, output)
                    emptyList()
                }
            } catch (e: Exception) {
                // 添加异常日志
                Log.e(TAG, "快照查询异常", e)
                logger.logCommandFailed(e)
                emptyList()
            }
        }
    }
    // 验证仓库密码是否正确
    suspend fun validateRepository(repoPath: String, password: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "验证仓库: repoPath=$repoPath")
                val args = listOf(resticPath, "snapshots", "--repo", repoPath, "--json")
                Log.d(TAG, "执行验证命令: ${args.joinToString(" ")}")

                val processBuilder = ProcessBuilder(args)
                processBuilder.environment()["RESTIC_REPOSITORY"] = repoPath
                processBuilder.environment()["RESTIC_PASSWORD"] = password

                val process = processBuilder.start()
                val output = InputStreamReader(process.inputStream).readText()
                val exitCode = process.waitFor()

                logger.logCommandResult(exitCode, output)

                if (exitCode == 0) {
                    Log.d(TAG, "仓库验证成功")
                } else {
                    Log.e(TAG, "仓库验证失败: exitCode=$exitCode, output=$output")
                }
                exitCode == 0
            } catch (e: Exception) {
                Log.e(TAG, "仓库验证异常", e)
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

    // 查询已备份的应用 - 移入类内部
    suspend fun listBackedUpApps(repoPath: String, password: String): List<ResticBackupApp> {
        return withContext(Dispatchers.IO) {
            try {
                val snapshots = listSnapshots(repoPath, password)
                val apps = mutableListOf<ResticBackupApp>()

                snapshots.forEach { snapshot ->
                    snapshot.tags.forEach { tag ->
                        // 解析 tag 格式: userId-packageName-timestamp-dataType
                        val parts = tag.split("-")
                        if (parts.size >= 4) {
                            val userId = parts[0].split("_").lastOrNull()?.toIntOrNull() ?: 0
                            val packageName = parts[1]
                            val timestamp = parts[2].toLongOrNull() ?: 0L
                            val dataTypeStr = parts[3]
                            val dataType = when (dataTypeStr) {
                                "apk" -> DataType.PACKAGE_APK
                                "user" -> DataType.PACKAGE_USER
                                "user_de" -> DataType.PACKAGE_USER_DE
                                "data" -> DataType.PACKAGE_DATA
                                "obb" -> DataType.PACKAGE_OBB
                                "media" -> DataType.PACKAGE_MEDIA
                                else -> null
                            }

                            if (dataType != null) {
                                apps.add(ResticBackupApp(
                                    packageName = packageName,
                                    userId = userId,
                                    timestamp = timestamp,
                                    dataType = dataType,
                                    snapshotId = snapshot.id,
                                    snapshotTime = snapshot.time,
                                    tags = snapshot.tags
                                ))
                            }
                        }
                    }
                }

                apps
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list backed up apps", e)
                emptyList()
            }
        }
    }
}

@Serializable
data class ResticSnapshot(
    val id: String,
    val time: String,
    val hostname: String,
    val paths: List<String>,
    val tags: List<String>
)