package com.xayah.core.restic

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.xayah.core.model.DataType
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.datastore.readResticCompressionLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResticRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ResticLogger,
    private val resticNative: ResticNative
) {
    companion object {
        private const val TAG = "ResticRepository"
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    private val resticPath: String by lazy {
        resticNative.getResticBinaryPath(context)
    }

    /**
     * 核心执行方法：使用 libsu 执行 Root 命令
     */
    private suspend fun executeRestic(
        vararg args: String,
        env: Map<String, String> = emptyMap()
    ): Shell.Result = withContext(Dispatchers.IO) {
        val defaultEnv = mutableMapOf(
            "HOME" to context.filesDir.absolutePath,
            "XDG_CACHE_HOME" to File(context.cacheDir, "restic").absolutePath
        )
        defaultEnv.putAll(env)

        // 构建带环境变量的命令
        val envExports = defaultEnv.map { "export ${it.key}=\"${it.value}\"" }
        val command = envExports.joinToString(" && ") + " && $resticPath ${args.joinToString(" ")}"

        Log.d(TAG, "Executing Root Command: $command")

        val result = Shell.cmd(command).exec()
        logger.logCommandResult(result.code, result.out.joinToString("\n"))
        result
    }

    // --- 恢复快照（包含详细诊断逻辑） ---
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
                Log.d(TAG, "开始恢复快照: $snapshotId -> $targetPath")

                val fullSnapshotId = if (!snapshotSubPath.isNullOrEmpty()) {
                    "$snapshotId:$snapshotSubPath"
                } else {
                    snapshotId
                }

                val args = mutableListOf(
                    "restore", fullSnapshotId,
                    "--repo", "\"$repoPath\"",
                    "--target", "\"$targetPath\"",
                    "--json"
                )

                if (!includePath.isNullOrEmpty()) {
                    args.addAll(listOf("--include", "\"$includePath\""))
                }

                // 执行命令
                val result = executeRestic(*args.toTypedArray(), env = mapOf("RESTIC_PASSWORD" to password))
                val output = result.out.joinToString("\n")
                val exitCode = result.code

                // 处理进度回调
                result.out.forEach { line ->
                    parseRestoreProgress(line)?.let { p ->
                        progressCallback?.onProgress(
                            p.files_finished ?: 0, p.files_total ?: 0,
                            p.bytes_written ?: 0, p.bytes_total ?: 0,
                            p.files_skipped ?: 0, p.bytes_skipped ?: 0
                        )
                    }
                }

                // === 详细恢复过程诊断 (原始逻辑回归) ===
                Log.d(TAG, "========== Restic 恢复过程详情 ==========")
                Log.d(TAG, "命令参数: ${args.joinToString(" ")}")
                Log.d(TAG, "退出码: $exitCode")
                Log.d(TAG, "标准输出长度: ${output.length} 字符")

                if (output.isNotEmpty()) {
                    Log.d(TAG, "=== 标准输出内容 ===")
                    result.out.forEachIndexed { index, line ->
                        Log.d(TAG, "stdout[$index]: $line")
                    }
                }

                // 分析失败原因
                if (exitCode != 0) {
                    Log.w(TAG, "=== 退出码分析 ===")
                    when {
                        output.contains("warning", ignoreCase = true) -> Log.w(TAG, "✓ 检测到警告信息")
                        output.contains("error", ignoreCase = true) -> Log.e(TAG, "✗ 检测到错误信息")
                        output.contains("file not found", ignoreCase = true) -> Log.e(TAG, "✗ 文件未找到")
                        output.contains("permission denied", ignoreCase = true) -> Log.e(TAG, "✗ 权限被拒绝 (SELinux 或 文件系统只读)")
                        output.contains("restoring", ignoreCase = true) -> Log.w(TAG, "✓ 检测到恢复操作记录，但命令未成功完成")
                        else -> Log.w(TAG, "? 未知原因的非零退出码")
                    }

                    // 检查物理文件是否存在
                    val targetFile = File(targetPath, includePath ?: "")
                    Log.w(TAG, "目标文件检查: ${targetFile.absolutePath}")
                    Log.w(TAG, "文件物理存在: ${targetFile.exists()}")
                    if (targetFile.exists()) {
                        Log.w(TAG, "文件大小: ${targetFile.length()} bytes")
                        Log.w(TAG, "最后修改时间: ${targetFile.lastModified()}")
                    }
                }
                Log.d(TAG, "========================================")

                exitCode == 0
            } catch (e: Exception) {
                Log.e(TAG, "Restic restore process exception", e)
                logger.logCommandFailed(e)
                false
            }
        }
    }

    // --- 其他方法 (保持 libsu 优化版) ---

    suspend fun getVersion(): String? {
        val result = executeRestic("version")
        return if (result.isSuccess) {
            result.out.firstOrNull()?.substringAfter("restic ")?.substringBefore(" ")
        } else null
    }

    /**
     * 重构后的初始化仓库方法
     * @return Result<String> 成功时包含输出信息，失败时包含异常
     */
    suspend fun initRepository(repoPath: String, password: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val result = executeRestic(
                    "init",
                    "--repo", "\"$repoPath\"",
                    env = mapOf("RESTIC_PASSWORD" to password)
                )

                val output = if (result.isSuccess) {
                    result.out.joinToString("\n")
                } else {
                    result.err.joinToString("\n")
                }

                if (result.isSuccess) {
                    Result.success(output)
                } else {
                    Result.failure(Exception(output.ifEmpty { "Unknown error during restic init" }))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun backupFile(repoPath: String, password: String, filePath: String, tags: List<String>): Pair<Int, String> {
        // 读取压缩级别配置
        val compressionLevel = context.readResticCompressionLevel().first() ?: "auto"

        val args = mutableListOf(
            "backup", "--repo", "\"$repoPath\"", "\"$filePath\"",
            "--tag", "\"${tags.joinToString(",")}\"", "--json",
            "--compression", compressionLevel  // 添加压缩参数
        )

        val result = executeRestic(*args.toTypedArray(), env = mapOf("RESTIC_PASSWORD" to password))
        return Pair(result.code, result.out.joinToString("\n"))
    }

    suspend fun listSnapshots(repoPath: String, password: String): List<ResticSnapshot> {
        val result = executeRestic("snapshots", "--repo", "\"$repoPath\"", "--json", env = mapOf("RESTIC_PASSWORD" to password))
        return if (result.isSuccess) {
            try {
                // 过滤：仅保留看起来像 JSON 内容的行（以 [ 或 { 开头），防止 Shell 杂质干扰
                val jsonStr = result.out
                    .filter { it.trim().startsWith("[") || it.trim().startsWith("{") }
                    .joinToString("")

                if (jsonStr.isEmpty()) return emptyList()
                json.decodeFromString<List<ResticSnapshot>>(jsonStr)
            } catch (e: Exception) {
                Log.e(TAG, "JSON 解析快照失败: ${e.message}")
                emptyList()
            }
        } else emptyList()
    }

    suspend fun listBackedUpFiles(repoPath: String, password: String): List<ResticBackupFiles> {
        val snapshots = listSnapshots(repoPath, password)
        val files = mutableListOf<ResticBackupFiles>()
        snapshots.forEach { snapshot ->
            snapshot.tags.forEach { tag ->
                val parts = tag.split("-")
                if (parts.size >= 3) {
                    val mediaName = parts.dropLast(2).joinToString("-")
                    val timestamp = parts.last().toLongOrNull() ?: 0L
                    val dataType = when (parts[parts.size - 2]) {
                        "media" -> DataType.PACKAGE_MEDIA
                        "config" -> DataType.PACKAGE_CONFIG
                        else -> null
                    }
                    if (dataType != null) {
                        val fullPath = snapshot.paths.firstOrNull() ?: ""
                        files.add(ResticBackupFiles(
                            mediaName = mediaName,
                            fullPath = fullPath,
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
        return files
    }

    suspend fun validateRepository(repoPath: String, password: String): Boolean =
        executeRestic("snapshots", "--repo", "\"$repoPath\"", "--json", env = mapOf("RESTIC_PASSWORD" to password)).isSuccess

    suspend fun deleteRepository(repoPath: String): Boolean = withContext(Dispatchers.IO) {
        try { File(repoPath).deleteRecursively() } catch (e: Exception) { false }
    }

    suspend fun checkRepository(repoPath: String, password: String): Boolean =
        executeRestic("check", "--repo", "\"$repoPath\"", env = mapOf("RESTIC_PASSWORD" to password)).isSuccess

    suspend fun listBackedUpApps(repoPath: String, password: String): List<ResticBackupApp> {
        val snapshots = listSnapshots(repoPath, password)
        val apps = mutableListOf<ResticBackupApp>()
        snapshots.forEach { snapshot ->
            snapshot.tags.forEach { tag ->
                val parts = tag.split("-")
                if (parts.size >= 4) {
                    val userId = parts[0].split("_").lastOrNull()?.toIntOrNull() ?: 0
                    val packageName = parts[1]
                    val timestamp = parts[2].toLongOrNull() ?: 0L
                    val dataType = when (parts[3]) {
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
                        apps.add(ResticBackupApp(packageName, userId, timestamp, dataType, snapshot.id, snapshot.time, snapshot.tags))
                    }
                }
            }
        }
        return apps
    }

    private fun parseRestoreProgress(line: String): ResticRestoreProgress? {
        return try { if (line.contains("message_type")) json.decodeFromString<ResticRestoreProgress>(line) else null } catch (e: Exception) { null }
    }

    interface ResticProgressCallback {
        fun onProgress(filesFinished: Long, filesTotal: Long, bytesWritten: Long, bytesTotal: Long, filesSkipped: Long, bytesSkipped: Long)
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