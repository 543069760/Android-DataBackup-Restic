package com.xayah.core.restic

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.xayah.core.util.GsonUtil
import com.xayah.core.model.DataType
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.datastore.readResticCompressionLevel
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.database.S3Protocol
import com.xayah.core.model.database.CloudEntity
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

        // 详细日志记录
        Log.d(TAG, "=== Restic Command Debug ===")
        Log.d(TAG, "Command: restic ${args.joinToString(" ")}")
        Log.d(TAG, "Environment: ${defaultEnv.entries.joinToString(", ") { "${it.key}=${it.value}" }}")

        val envExports = defaultEnv.map { "export ${it.key}=\"${it.value}\"" }
        val command = envExports.joinToString(" && ") + " && $resticPath ${args.joinToString(" ")}"

        Log.d(TAG, "Full command: $command")

        val result = Shell.cmd(command).exec()

        // 详细输出日志
        Log.d(TAG, "Exit code: ${result.code}")
        if (result.out.isNotEmpty()) {
            Log.d(TAG, "STDOUT:\n${result.out.joinToString("\n")}")
        }
        if (result.err.isNotEmpty()) {
            Log.e(TAG, "STDERR:\n${result.err.joinToString("\n")}")
        }
        Log.d(TAG, "==============================")

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
                        progressCallback?.onRestoreProgress(
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

    /**
     * 从 S3 恢复快照（包含完整 S3 环境变量）
     */
    suspend fun restoreSnapshotFromS3(
        cloudEntity: CloudEntity,
        password: String,
        snapshotId: String,
        targetPath: String,
        snapshotSubPath: String? = null,
        includePath: String? = null,
        progressCallback: ResticProgressCallback? = null
    ): Boolean {
        Log.d(TAG, "=== 开始云端快照恢复 ===")
        Log.d(TAG, "快照ID: $snapshotId")
        Log.d(TAG, "目标路径: $targetPath")
        Log.d(TAG, "快照子路径: $snapshotSubPath")
        Log.d(TAG, "包含文件: $includePath")
        val extra = json.decodeFromString<S3Extra>(cloudEntity.extra) ?: return false
        Log.d(TAG, "S3配置: endpoint=${extra.endpoint}, bucket=${extra.bucket}, region=${extra.region}")

        // 使用统一的 URL 构建方法
        val s3Url = buildS3ResticUrl(extra, cloudEntity.remote)
        Log.d(TAG, "构建的 S3 URL: $s3Url")

        val env = mutableMapOf(
            "AWS_ACCESS_KEY_ID" to extra.accessKeyId,
            "AWS_SECRET_ACCESS_KEY" to extra.secretAccessKey,
            "RESTIC_PASSWORD" to password
        )

        // 添加区域配置
        if (extra.region.isNotEmpty()) {
            env["AWS_DEFAULT_REGION"] = extra.region
        }

        val fullSnapshotId = if (!snapshotSubPath.isNullOrEmpty()) {
            "$snapshotId:$snapshotSubPath"
        } else {
            snapshotId
        }

        val args = mutableListOf(
            "restore", fullSnapshotId,
            "--repo", "\"$s3Url\"",
            "--target", "\"$targetPath\"",
            "--json",
            "-o", "s3.bucket-lookup=dns"
        )

        if (!includePath.isNullOrEmpty()) {
            args.addAll(listOf("--include", "\"$includePath\""))
        }

        Log.d(TAG, "即将执行恢复命令...")
        val result = executeRestic(*args.toTypedArray(), env = env)

        Log.d(TAG, "恢复命令执行完成，退出码: ${result.code}")
        if (result.code != 0) {
            Log.e(TAG, "恢复失败，错误输出: ${result.err.joinToString("\n")}")
        }

        // 处理进度回调
        result.out.forEach { line ->
            parseRestoreProgress(line)?.let { p ->
                progressCallback?.onRestoreProgress(
                    p.files_finished ?: 0, p.files_total ?: 0,
                    p.bytes_written ?: 0, p.bytes_total ?: 0,
                    p.files_skipped ?: 0, p.bytes_skipped ?: 0
                )
            }
        }

        return result.code == 0
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
                    val timestamp = parts[parts.size - 2].toLongOrNull() ?: 0L
                    val dataType = when (parts.last()) {
                        "filesbackup" -> DataType.PACKAGE_MEDIA
                        "filesconfig" -> DataType.PACKAGE_CONFIG
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

    suspend fun listBackedUpFilesFromS3(cloudEntity: CloudEntity, password: String): List<ResticBackupFiles> {
        Log.d(TAG, "=== listBackedUpFilesFromS3 启动 ===")
        Log.d(TAG, "账户名称: ${cloudEntity.name}, 远程路径: ${cloudEntity.remote}")

        return try {
            // 1. 解析 S3 配置
            val extra = json.decodeFromString<S3Extra>(cloudEntity.extra) ?: run {
                Log.e(TAG, "错误: 无法解析 S3Extra 配置")
                return emptyList()
            }

            // 2. 构建统一的 S3 URL
            val repoUrl = buildS3ResticUrl(extra, cloudEntity.remote)
            Log.d(TAG, "生成的完整仓库 URL: $repoUrl")

            // 3. 准备环境变量
            val env = mutableMapOf(
                "AWS_ACCESS_KEY_ID" to extra.accessKeyId,
                "AWS_SECRET_ACCESS_KEY" to extra.secretAccessKey,
                "RESTIC_PASSWORD" to password
            )
            if (extra.region.isNotEmpty()) {
                env["AWS_DEFAULT_REGION"] = extra.region
            }

            // 4. 构建命令行参数
            val args = mutableListOf(
                "snapshots",
                "--repo", "\"$repoUrl\"",
                "--json",
                "-o", "s3.bucket-lookup=dns"
            )

            if (extra.region.isNotEmpty()) {
                args.add("-o")
                args.add("s3.region=${extra.region}")
            }

            Log.d(TAG, "正在执行 restic snapshots...")
            val result = executeRestic(*args.toTypedArray(), env = env)

            // 5. 处理结果
            if (result.isSuccess) {
                val jsonStr = result.out
                    .filter { it.trim().startsWith("[") || it.trim().startsWith("{") }
                    .joinToString("")

                if (jsonStr.isEmpty()) {
                    Log.w(TAG, "命令成功但未返回任何快照内容 (JSON 为空)")
                    return emptyList()
                }

                // 解析快照列表
                val snapshots = json.decodeFromString<List<ResticSnapshot>>(jsonStr)
                Log.d(TAG, "成功获取 ${snapshots.size} 个快照，开始解析文件标签...")

                val files = mutableListOf<ResticBackupFiles>()
                snapshots.forEach { snapshot ->
                    snapshot.tags.forEach { tag ->
                        val parts = tag.split("-")
                        // 预期的标签格式: mediaName-timestamp-filesbackup/filesconfig
                        if (parts.size >= 3) {
                            try {
                                val mediaName = parts.dropLast(2).joinToString("-")
                                val timestamp = parts[parts.size - 2].toLongOrNull() ?: 0L
                                val dataType = when (parts.last()) {
                                    "filesbackup" -> DataType.PACKAGE_MEDIA
                                    "filesconfig" -> DataType.PACKAGE_CONFIG
                                    else -> null
                                }


                                if (dataType != null) {
                                    val fullPath = snapshot.paths.firstOrNull() ?: ""
                                    files.add(
                                        ResticBackupFiles(
                                            mediaName = mediaName,
                                            fullPath = fullPath,
                                            timestamp = timestamp,
                                            dataType = dataType,
                                            snapshotId = snapshot.id,
                                            snapshotTime = snapshot.time,
                                            tags = snapshot.tags
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "解析文件标签出错: $tag", e)
                            }
                        }
                    }
                }
                Log.d(TAG, "最终成功提取出 ${files.size} 个文件备份项")
                files
            } else {
                Log.e(TAG, "Restic 查询失败 (Exit Code: ${result.code})")
                Log.e(TAG, "错误输出: ${result.err.joinToString("\n")}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "listBackedUpFilesFromS3 发生严重异常", e)
            emptyList()
        }
    }

    /**
     * 从 S3 仓库获取备份的应用列表
     */
    suspend fun listBackedUpAppsFromS3(cloudEntity: CloudEntity, password: String): List<ResticBackupApp> {
        Log.d(TAG, "=== listBackedUpAppsFromS3 启动 ===")
        Log.d(TAG, "账户名称: ${cloudEntity.name}, 远程路径: ${cloudEntity.remote}")

        return try {
            // 1. 解析 S3 配置
            val extra = json.decodeFromString<S3Extra>(cloudEntity.extra) ?: run {
                Log.e(TAG, "错误: 无法解析 S3Extra 配置")
                return emptyList()
            }

            // 2. 构建统一的 S3 URL (自动处理 http/https 和斜杠)
            // 使用你现有的 buildS3ResticUrl，确保它处理了 bucket 和 remote 之间的斜杠
            val repoUrl = buildS3ResticUrl(extra, cloudEntity.remote)
            Log.d(TAG, "生成的完整仓库 URL: $repoUrl")

            // 3. 准备环境变量 (与成功备份时的环境保持一致)
            val env = mutableMapOf(
                "AWS_ACCESS_KEY_ID" to extra.accessKeyId,
                "AWS_SECRET_ACCESS_KEY" to extra.secretAccessKey,
                "RESTIC_PASSWORD" to password
            )
            if (extra.region.isNotEmpty()) {
                env["AWS_DEFAULT_REGION"] = extra.region
            }

            // 4. 构建命令行参数
            val args = mutableListOf(
                "snapshots",
                "--repo", "\"$repoUrl\"",
                "--json",
                "-o", "s3.bucket-lookup=dns"
            )

            // 如果有 region，显式添加到参数中（对应成功日志中的逻辑）
            if (extra.region.isNotEmpty()) {
                args.add("-o")
                args.add("s3.region=${extra.region}")
            }

            Log.d(TAG, "正在执行 restic snapshots...")
            val result = executeRestic(*args.toTypedArray(), env = env)

            // 5. 处理结果
            if (result.isSuccess) {
                val jsonStr = result.out
                    .filter { it.trim().startsWith("[") || it.trim().startsWith("{") }
                    .joinToString("")

                if (jsonStr.isEmpty()) {
                    Log.w(TAG, "命令成功但未返回任何快照内容 (JSON 为空)")
                    return emptyList()
                }

                // 解析快照列表
                val snapshots = json.decodeFromString<List<ResticSnapshot>>(jsonStr)
                Log.d(TAG, "成功获取 ${snapshots.size} 个快照，开始解析应用标签...")

                val apps = mutableListOf<ResticBackupApp>()
                snapshots.forEach { snapshot ->
                    snapshot.tags.forEach { tag ->
                        val parts = tag.split("-")
                        // 预期的标签格式: user_0-com.package.name-1768755852316-apk
                        if (parts.size >= 4) {
                            try {
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
                                    apps.add(
                                        ResticBackupApp(
                                            packageName = packageName,
                                            userId = userId,
                                            timestamp = timestamp,
                                            dataType = dataType,
                                            snapshotId = snapshot.id,
                                            snapshotTime = snapshot.time,
                                            tags = snapshot.tags
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "解析标签出错: $tag", e)
                            }
                        }
                    }
                }
                Log.d(TAG, "最终成功提取出 ${apps.size} 个应用备份项")
                apps
            } else {
                Log.e(TAG, "Restic 查询失败 (Exit Code: ${result.code})")
                Log.e(TAG, "错误输出: ${result.err.joinToString("\n")}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "listBackedUpAppsFromS3 发生严重异常", e)
            emptyList()
        }
    }
    /**
     * 使用 Restic 备份到本地仓库
     * @param repoPath 本地仓库路径
     * @param password 仓库密码
     * @param filePath 要备份的文件路径
     * @param tags 备份标签
     * @param progressCallback 进度回调
     * @return Pair<Int, String> 退出码和 JSON 输出
     */
    suspend fun backupWithResticToLocal(
        repoPath: String,
        password: String,
        filePath: String,
        tags: List<String>,
        progressCallback: ResticProgressCallback? = null
    ): Pair<Int, String> {
        // 读取压缩级别配置
        val compressionLevel = context.readResticCompressionLevel().first() ?: "auto"

        val args = mutableListOf(
            "backup", "--repo", "\"$repoPath\"", "\"$filePath\"",
            "--tag", "\"${tags.joinToString(",")}\"", "--json",
            "--compression", compressionLevel
        )

        val result = executeRestic(*args.toTypedArray(), env = mapOf("RESTIC_PASSWORD" to password))

        // 解析进度信息
        result.out.forEach { line ->
            parseBackupProgress(line)?.let { p ->
                progressCallback?.onBackupProgress(
                    p.percent_done ?: 0f,
                    p.bytes_done ?: 0,
                    p.total_bytes ?: 0,
                    p.files_done ?: 0,
                    p.total_files ?: 0
                )
            }
        }

        return Pair(result.code, result.out.joinToString("\n"))
    }

    /**
     * 构建通用的 S3 Restic URL
     * 支持 AWS S3 和所有 S3 兼容存储（MinIO、Ceph、阿里云OSS等）
     */
    /**
     * 构建通用的 S3 Restic URL（最稳妥版）
     * 确保协议、Endpoint、Bucket 和 RemotePath 之间的斜杠处理万无一失
     */
    fun buildS3ResticUrl(extra: S3Extra, remotePath: String): String {
        // 1. 确定协议前缀
        val protocol = when (extra.protocol) {
            S3Protocol.HTTP -> "http"
            S3Protocol.HTTPS -> "https"
        }

        // 2. 处理 Host (Endpoint)
        // 剥离末尾的斜杠，防止拼接后出现双斜杠
        val host = if (extra.endpoint.isNotEmpty()) {
            extra.endpoint.trim().removeSuffix("/")
        } else {
            "s3.${extra.region}.amazonaws.com"
        }

        // 3. 处理 Bucket
        // 剥离两端的斜杠
        val bucket = extra.bucket.trim().trim('/')

        // 4. 处理远程路径 (remotePath)
        // 确保以单斜杠开头，且剥离末尾斜杠
        val path = remotePath.trim()
        val formattedPath = if (path.isEmpty() || path == "/") {
            ""
        } else {
            if (path.startsWith("/")) path.removeSuffix("/") else "/$path"
        }

        // 5. 组合最终 URL
        // 格式：s3:http://endpoint/bucket/remotePath
        val finalUrl = "s3:$protocol://$host/$bucket$formattedPath"

        Log.d("ResticRepository", "URL 构建调试: protocol=$protocol, host=$host, bucket=$bucket, path=$formattedPath")
        Log.d("ResticRepository", "最终 URL: $finalUrl")

        return finalUrl
    }

    /**
     * 初始化 S3 仓库
     * @param extra S3 配置信息
     * @param remotePath 远程路径
     * @param password 仓库密码
     * @return Result<String> 成功时包含输出信息，失败时包含异常
     */
    suspend fun initS3Repository(
        extra: S3Extra,
        remotePath: String,
        password: String
    ): Result<String> {
        val repoUrl = buildS3ResticUrl(extra, remotePath)

        // 完整的环境变量设置
        val env = mutableMapOf(
            "AWS_ACCESS_KEY_ID" to extra.accessKeyId,
            "AWS_SECRET_ACCESS_KEY" to extra.secretAccessKey,
            "RESTIC_PASSWORD" to password
        )

        // 根据文档添加必要的配置
        val options = mutableListOf<String>()

        if (extra.endpoint.isNotEmpty()) {
            // 自定义endpoint的S3兼容存储
            if (extra.region.isNotEmpty()) {
                env["AWS_DEFAULT_REGION"] = extra.region
                options.add("-o")
                options.add("s3.region=${extra.region}")
            }

            // 对于非AWS存储，可能需要指定bucket-lookup模式
            if (!extra.endpoint.contains("amazonaws.com")) {
                options.add("-o")
                options.add("s3.bucket-lookup=dns")
            }
        } else {
            // AWS S3特定配置
            env["AWS_DEFAULT_REGION"] = extra.region
        }

        // 构建完整的命令参数
        val args = mutableListOf("init", "--repo", "\"$repoUrl\"")
        args.addAll(options)

        // 直接调用executeRestic而不是initRepository
        val result = executeRestic(
            *args.toTypedArray(),
            env = env
        )

        val output = if (result.isSuccess) {
            result.out.joinToString("\n")
        } else {
            result.err.joinToString("\n")
        }

        return if (result.isSuccess) {
            Result.success(output)
        } else {
            Result.failure(Exception(output.ifEmpty { "Unknown error during restic init" }))
        }
    }

    /**
     * 使用 S3 后端备份文件
     * @param extra S3 配置信息
     * @param remotePath 远程路径
     * @param filePath 要备份的文件路径
     * @param tags 备份标签
     * @param password 仓库密码
     * @param progressCallback 进度回调
     * @return Pair<Int, String> 退出码和 JSON 输出
     */
    suspend fun backupFileToS3(
        extra: S3Extra,
        remotePath: String,
        filePath: String,
        tags: List<String>,
        password: String,
        progressCallback: ResticProgressCallback? = null
    ): Pair<Int, String> {
        val repoUrl = buildS3ResticUrl(extra, remotePath)

        val env = mutableMapOf(
            "AWS_ACCESS_KEY_ID" to extra.accessKeyId,
            "AWS_SECRET_ACCESS_KEY" to extra.secretAccessKey,
            "RESTIC_PASSWORD" to password
        )

        // S3 特定选项
        val options = mutableListOf<String>()
        if (extra.endpoint.isNotEmpty() && extra.region.isNotEmpty()) {
            options.add("-o")
            options.add("s3.region=${extra.region}")
        }

        val compressionLevel = context.readResticCompressionLevel().first() ?: "auto"

        val args = mutableListOf(
            "backup", "--repo", "\"$repoUrl\"", "\"$filePath\"",
            "--tag", "\"${tags.joinToString(",")}\"", "--json",
            "--compression", compressionLevel,
            "-o", "s3.bucket-lookup=dns"
        )
        args.addAll(options)

        val result = executeRestic(*args.toTypedArray(), env = env)

        // 解析进度
        result.out.forEach { line ->
            parseBackupProgress(line)?.let { p ->
                progressCallback?.onBackupProgress(
                    p.percent_done ?: 0f,
                    p.bytes_done ?: 0,
                    p.total_bytes ?: 0,
                    p.files_done ?: 0,
                    p.total_files ?: 0
                )
            }
        }

        return Pair(result.code, result.out.joinToString("\n"))
    }

    /**
     * 解析备份进度信息
     */
    private fun parseBackupProgress(line: String): ResticBackupProgress? {
        return try {
            if (line.contains("message_type") && line.contains("status")) {
                json.decodeFromString<ResticBackupProgress>(line)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Restic 备份进度数据类
     */
    @Serializable
    data class ResticBackupProgress(
        val message_type: String,
        val percent_done: Float? = null,
        val bytes_done: Long? = null,
        val total_bytes: Long? = null,
        val files_done: Long? = null,
        val total_files: Long? = null,
        val seconds_elapsed: Long? = null,
        val seconds_remaining: Long? = null,
        val error_count: Long? = null,
        val current_files: List<String>? = null
    )

    private fun parseRestoreProgress(line: String): ResticRestoreProgress? {
        return try { if (line.contains("message_type")) json.decodeFromString<ResticRestoreProgress>(line) else null } catch (e: Exception) { null }
    }

    interface ResticProgressCallback {
        // 恢复进度（现有）
        fun onRestoreProgress(filesFinished: Long, filesTotal: Long, bytesWritten: Long, bytesTotal: Long, filesSkipped: Long, bytesSkipped: Long)

        // 备份进度（新增）
        fun onBackupProgress(percentDone: Float, bytesDone: Long, bytesTotal: Long, filesDone: Long, filesTotal: Long)
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