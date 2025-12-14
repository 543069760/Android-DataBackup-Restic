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
        targetPath: String,
        snapshotSubPath: String? = null,
        includePath: String? = null,
        progressCallback: ResticProgressCallback? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 构造带子路径的快照ID
                val fullSnapshotId = if (!snapshotSubPath.isNullOrEmpty()) {
                    "$snapshotId:$snapshotSubPath"
                } else {
                    snapshotId
                }

                val args = mutableListOf(
                    resticPath, "restore", fullSnapshotId,
                    "--repo", repoPath,
                    "--target", targetPath,
                    "--json"
                )

                if (!includePath.isNullOrEmpty()) {
                    args.add("--include")
                    args.add(includePath)
                }

                Log.d(TAG, "Restic restore command: ${args.joinToString(" ")}")

                val processBuilder = ProcessBuilder(args)
                processBuilder.environment()["RESTIC_REPOSITORY"] = repoPath
                processBuilder.environment()["RESTIC_PASSWORD"] = password
                processBuilder.environment()["HOME"] = context.filesDir.absolutePath
                processBuilder.environment()["XDG_CACHE_HOME"] = File(context.cacheDir, "restic").absolutePath
                val process = processBuilder.start()

                try {
                    // 分别读取标准输出和错误输出
                    val stdout = process.inputStream.bufferedReader()
                    val stderr = process.errorStream.bufferedReader()

                    // 处理标准输出（进度信息）
                    val outputBuilder = StringBuilder()
                    stdout.use { reader ->
                        reader.forEachLine { line ->
                            outputBuilder.appendLine(line)  // 捕获输出

                            try {
                                val progress = parseRestoreProgress(line)
                                if (progress != null && progressCallback != null) {
                                    progressCallback.onProgress(
                                        filesFinished = progress.files_finished ?: 0,
                                        filesTotal = progress.files_total ?: 0,
                                        bytesWritten = progress.bytes_written ?: 0,
                                        bytesTotal = progress.bytes_total ?: 0,
                                        filesSkipped = progress.files_skipped ?: 0,
                                        bytesSkipped = progress.bytes_skipped ?: 0
                                    )
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse progress line: $line", e)
                            }
                        }
                    }

// 读取错误输出用于调试
                    val errorOutput = stderr.readText()
                    val output = outputBuilder.toString()  // 使用捕获的输出

                    val exitCode = process.waitFor()

                    // === 详细恢复过程诊断 ===
                    Log.d(TAG, "========== Restic 恢复过程详情 ==========")
                    Log.d(TAG, "命令: ${args.joinToString(" ")}")
                    Log.d(TAG, "退出码: $exitCode")
                    Log.d(TAG, "标准输出长度: ${output.length} 字符")
                    Log.d(TAG, "错误输出长度: ${errorOutput.length} 字符")

                    if (output.isNotEmpty()) {
                        Log.d(TAG, "=== 标准输出内容 ===")
                        output.lines().forEachIndexed { index, line ->
                            Log.d(TAG, "stdout[$index]: $line")
                        }
                    }

                    if (errorOutput.isNotEmpty()) {
                        Log.e(TAG, "=== 错误输出内容 ===")
                        errorOutput.lines().forEachIndexed { index, line ->
                            Log.e(TAG, "stderr[$index]: $line")
                        }
                    }

                    // 分析退出码原因
                    if (exitCode != 0) {
                        Log.w(TAG, "=== 退出码分析 ===")
                        when {
                            errorOutput.contains("warning", ignoreCase = true) -> {
                                Log.w(TAG, "✓ 检测到警告信息")
                            }
                            errorOutput.contains("error", ignoreCase = true) -> {
                                Log.e(TAG, "✗ 检测到错误信息")
                            }
                            errorOutput.contains("file not found", ignoreCase = true) -> {
                                Log.e(TAG, "✗ 文件未找到")
                            }
                            errorOutput.contains("permission denied", ignoreCase = true) -> {
                                Log.e(TAG, "✗ 权限被拒绝")
                            }
                            output.contains("restoring", ignoreCase = true) -> {
                                Log.w(TAG, "✓ 检测到恢复操作记录")
                            }
                            else -> {
                                Log.w(TAG, "? 未知原因的非零退出码")
                            }
                        }

                        // 检查目标文件
                        val targetFile = File(targetPath, includePath ?: "")
                        Log.w(TAG, "目标文件检查: ${targetFile.absolutePath}")
                        Log.w(TAG, "文件存在: ${targetFile.exists()}")
                        if (targetFile.exists()) {
                            Log.w(TAG, "文件大小: ${targetFile.length()} bytes")
                            Log.w(TAG, "文件修改时间: ${targetFile.lastModified()}")
                        }
                    }

                    Log.d(TAG, "========================================")

                    logger.logCommandResult(exitCode, output)
                    exitCode == 0
                } catch (e: Exception) {
                    Log.e(TAG, "Restic restore process exception", e)
                    // 尝试获取可能的错误输出
                    try {
                        val errorOutput = process.errorStream.bufferedReader().readText()
                        Log.e(TAG, "Process error output: $errorOutput")
                    } catch (ex: Exception) {
                        Log.e(TAG, "Failed to read error output", ex)
                    }
                    throw e
                }
            } catch (e: Exception) {
                logger.logCommandFailed(e)
                false
            }
        }
    }
    // 添加进度解析方法
    private fun parseRestoreProgress(line: String): ResticRestoreProgress? {
        return try {
            json.decodeFromString<ResticRestoreProgress>(line)
        } catch (e: Exception) {
            null
        }
    }

    interface ResticProgressCallback {
        fun onProgress(
            filesFinished: Long,
            filesTotal: Long,
            bytesWritten: Long,
            bytesTotal: Long,
            filesSkipped: Long = 0,
            bytesSkipped: Long = 0
        )
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
                                "config" -> DataType.PACKAGE_CONFIG
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
data class ResticRestoreProgress(
    val message_type: String,
    val files_finished: Long? = null,
    val files_total: Long? = null,
    val bytes_written: Long? = null,
    val bytes_total: Long? = null,
    val files_skipped: Long? = null,
    val bytes_skipped: Long? = null
)

@Serializable
data class ResticSnapshot(
    val id: String,
    val time: String,
    val hostname: String,
    val paths: List<String>,
    val tags: List<String>
)