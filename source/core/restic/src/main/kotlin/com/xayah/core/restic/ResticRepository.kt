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
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.rootservice.ICallback
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
    private val resticNative: ResticNative,
    private val rootService: RemoteRootService,
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
     * 核心执行方法:使用 libsu 执行 Root 命令
     * @param usePty 是否使用 PTY 模拟(用于需要终端的命令,如 --sql)
     */
    private suspend fun executeRestic(
        vararg args: String,
        env: Map<String, String> = emptyMap(),
        usePty: Boolean = false
    ): Shell.Result = withContext(Dispatchers.IO) {
        val defaultEnv = mutableMapOf(
            "HOME" to context.filesDir.absolutePath,
            "XDG_CACHE_HOME" to File(context.cacheDir, "restic").absolutePath
        )

        if (usePty) {
            defaultEnv["TERM"] = "xterm-256color"
        }

        defaultEnv.putAll(env)

        val envExports = defaultEnv.map { "export ${it.key}=\"${it.value}\"" }
        val resticCommand = "$resticPath ${args.joinToString(" ")}"

        val finalCommand = if (usePty) {
            val busyboxPath = "${context.filesDir.absolutePath}/bin/busybox"
            // 重定向 stdin, stdout, stderr 避免 script 挂起
            envExports.joinToString(" && ") +
                    " && $busyboxPath script -qc \"$resticCommand 2>&1\" /dev/null < /dev/null 2>&1"
        } else {
            envExports.joinToString(" && ") + " && $resticCommand"
        }

        Log.d(TAG, "=== Restic Command Debug ===")
        Log.d(TAG, "Command: restic ${args.joinToString(" ")}")
        Log.d(TAG, "Use PTY: $usePty")
        Log.d(TAG, "Environment: ${defaultEnv.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
        Log.d(TAG, "Full command: $finalCommand")

        val result = Shell.cmd(finalCommand).exec()

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

    /**
     * 使用 Rustic OpenDAL SQL 模式从 S3 获取应用备份列表
     */
    suspend fun listBackedUpAppsFromS3WithSql(
        cloudEntity: CloudEntity,
        password: String
    ): List<ResticBackupApp> = withContext(Dispatchers.IO) {
        try {
            val extra = json.decodeFromString<S3Extra>(cloudEntity.extra) ?: return@withContext emptyList()

            // 创建 cache/sql/ 目录
            val sqlDir = File(context.cacheDir, "sql")
            if (!sqlDir.exists()) {
                sqlDir.mkdirs()
            }

            // SQL 文件保存到 cache/sql/ 目录
            val sqlFile = File(sqlDir, "snapshots_${System.currentTimeMillis()}.sql")

            val env = mutableMapOf(
                "OPENDAL_BUCKET" to extra.bucket,
                "OPENDAL_ROOT" to formatOpenDALRoot(cloudEntity.remote),
                "OPENDAL_ENDPOINT" to buildOpenDALEndpoint(extra),
                "OPENDAL_SECRET_ID" to extra.accessKeyId,
                "OPENDAL_SECRET_KEY" to extra.secretAccessKey,
                "RUSTIC_PASSWORD" to password
            )

            val args = mutableListOf(
                "--no-progress",
                "snapshots",
                "-r", "opendal:cos",
                "--sql",
                "--sql-output", sqlFile.absolutePath
            )

            Log.d(TAG, "执行 Rustic SQL 查询,输出路径: ${sqlFile.absolutePath}")
            val result = executeRestic(*args.toTypedArray(), env = env, usePty = true)

            if (!result.isSuccess) {
                Log.e(TAG, "SQL 生成失败 (Exit Code: ${result.code})")
                Log.e(TAG, "标准输出: ${result.out.joinToString("\n")}")
                Log.e(TAG, "错误输出: ${result.err.joinToString("\n")}")
                return@withContext emptyList()
            }

            if (!sqlFile.exists()) {
                Log.e(TAG, "SQL 文件未生成: ${sqlFile.absolutePath}")
                return@withContext emptyList()
            }

            if (sqlFile.length() == 0L) {
                Log.e(TAG, "SQL 文件为空")
                return@withContext emptyList()
            }

            val apps = parseSqlFileForApps(sqlFile)
            sqlFile.delete()  // 清理临时文件

            Log.d(TAG, "SQL 模式成功提取 ${apps.size} 个应用备份项")
            apps
        } catch (e: Exception) {
            Log.e(TAG, "listBackedUpAppsFromS3WithSql 异常", e)
            emptyList()
        }
    }

    /**
     * 格式化 OpenDAL Root 路径
     * 确保以 / 开头,以 / 结尾(与您的命令示例一致)
     */
    private fun formatOpenDALRoot(remotePath: String): String {
        val trimmed = remotePath.trim()
        if (trimmed.isEmpty() || trimmed == "/") {
            return "/"
        }
        val withLeading = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return if (withLeading.endsWith("/")) withLeading else "$withLeading/"
    }

    /**
     * 构建 OpenDAL Endpoint
     * 格式: protocol://endpoint (不包含 bucket)
     */
    private fun buildOpenDALEndpoint(extra: S3Extra): String {
        val protocol = when (extra.protocol) {
            S3Protocol.HTTP -> "http"
            S3Protocol.HTTPS -> "https"
        }
        return "$protocol://${extra.endpoint.trim().removeSuffix("/")}"
    }

    /**
     * 从 rustic 生成的 .db 直接解析应用备份信息（使用 v_snapshots_full 视图）
     * 替代旧的 parseSqlFileForApps（后者依赖 --sql 文本重建库）
     */
    private fun parseAppsDb(dbFile: File): List<ResticBackupApp> {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            val query = """  
            SELECT  
                id,  
                time,  
                tags_flat,  
                total_bytes_processed  
            FROM v_snapshots_full  
            WHERE tags_flat IS NOT NULL  
        """

            val cursor = db.rawQuery(query, null)
            val apps = mutableListOf<ResticBackupApp>()

            while (cursor.moveToNext()) {
                val snapshotId = cursor.getString(0)
                val snapshotTime = cursor.getString(1)
                val tagsFlat = cursor.getString(2) ?: continue
                val totalBytesProcessed = cursor.getLong(3)

                val tags = tagsFlat.split(31.toChar()).filter { it.isNotEmpty() }

                tags.forEach { tag ->
                    val parts = tag.split("-")
                    // 标签格式: user_0-com.package.name-1768755852316-apk
                    if (parts.size >= 4 && parts[0].startsWith("user_")) {
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
                                apps.add(ResticBackupApp(
                                    packageName = packageName,
                                    userId = userId,
                                    timestamp = timestamp,
                                    dataType = dataType,
                                    snapshotId = snapshotId,
                                    snapshotTime = snapshotTime,
                                    tags = tags,
                                    totalBytesProcessed = totalBytesProcessed
                                ))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解析标签出错: $tag", e)
                        }
                    }
                }
            }
            cursor.close()
            return apps
        } finally {
            db.close()
        }
    }

    /**
     * 从 SQL 文件解析应用备份信息(使用 v_snapshots_full 视图)
     */
    private fun parseSqlFileForApps(sqlFile: File): List<ResticBackupApp> {
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(":memory:", null)
        try {
            // 执行 SQL 文件中的所有语句
            sqlFile.readText().split(";").forEach { stmt ->
                val trimmed = stmt.trim()
                if (trimmed.isNotEmpty()) {
                    try {
                        db.execSQL(trimmed + ";")
                    } catch (e: Exception) {
                        // 忽略 COMMIT 等可能的语法差异
                        Log.w(TAG, "SQL 语句执行警告: ${e.message}")
                    }
                }
            }

            // 直接从视图查询,tags_flat 包含所有标签(用 char(31) 分隔)
            val query = """  
            SELECT   
                id,  
                time,  
                tags_flat,
                total_bytes_processed
            FROM v_snapshots_full  
            WHERE tags_flat IS NOT NULL  
        """

            val cursor = db.rawQuery(query, null)
            val apps = mutableListOf<ResticBackupApp>()

            while (cursor.moveToNext()) {
                val snapshotId = cursor.getString(0)
                val snapshotTime = cursor.getString(1)
                val tagsFlat = cursor.getString(2) ?: continue
                val totalBytesProcessed = cursor.getLong(3)

                // tags_flat 使用 char(31) 作为分隔符
                val tags = tagsFlat.split(31.toChar()).filter { it.isNotEmpty() }

                tags.forEach { tag ->
                    val parts = tag.split("-")
                    // 标签格式: user_0-com.package.name-1768755852316-apk
                    if (parts.size >= 4 && parts[0].startsWith("user_")) {
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
                                apps.add(ResticBackupApp(
                                    packageName = packageName,
                                    userId = userId,
                                    timestamp = timestamp,
                                    dataType = dataType,
                                    snapshotId = snapshotId,
                                    snapshotTime = snapshotTime,
                                    tags = tags,
                                    totalBytesProcessed = totalBytesProcessed
                                ))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解析标签出错: $tag", e)
                        }
                    }
                }
            }
            cursor.close()
            return apps
        } finally {
            db.close()
        }
    }

    // --- 恢复快照（JNI 版）---
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
                val fullSnapshotId = if (!snapshotSubPath.isNullOrEmpty())
                    "$snapshotId:$snapshotSubPath" else snapshotId

                // 旧 CLI 的 --glob includePath 是 include 语义；native 侧 Excludes 纯 pattern 是 exclude，
                // 所以要以 "!" 前缀表达 include（与你之前在 repository.rs 里确认的一致）。
                val includeGlob = if (!includePath.isNullOrEmpty()) "!$includePath" else ""

                // 桥接：把 ResticProgressCallback 包成 AIDL 的 ICallback
                val callbackStub = if (progressCallback != null) {
                    object : ICallback.Stub() {
                        // onRestorePlan 规划阶段一次性回传，缓存供 onProgress 拼六字段
                        @Volatile var planFilesTotal = 0L
                        @Volatile var planBytesTotal = 0L
                        @Volatile var planFilesSkipped = 0L
                        @Volatile var planBytesSkipped = 0L

                        override fun onRestorePlan(
                            filesTotal: Long, bytesTotal: Long,
                            filesSkipped: Long, bytesSkipped: Long
                        ) {
                            planFilesTotal = filesTotal
                            planBytesTotal = bytesTotal
                            planFilesSkipped = filesSkipped
                            planBytesSkipped = bytesSkipped
                        }

                        override fun onProgress(bytesWritten: Long, speed: Long, progress: Float) {
                            // native 只给字节进度；files 相关用 plan 缓存值，filesFinished 暂置 0
                            progressCallback.onRestoreProgress(
                                0L,                 // filesFinished：native 不逐文件回传
                                planFilesTotal,
                                bytesWritten,
                                planBytesTotal,
                                planFilesSkipped,
                                planBytesSkipped
                            )
                        }
                    }
                } else null

                rootService.restoreRusticSnapshot(
                    repoPath, password, fullSnapshotId, targetPath,
                    includeGlob = includeGlob,
                    callback = callbackStub
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "Rustic restore process exception", e)
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
        Log.d(TAG, "=== 开始云端快照恢复(Rustic) ===")
        Log.d(TAG, "快照ID: $snapshotId")
        Log.d(TAG, "目标路径: $targetPath")
        Log.d(TAG, "快照子路径: $snapshotSubPath")
        Log.d(TAG, "包含文件: $includePath")

        val extra = json.decodeFromString<S3Extra>(cloudEntity.extra) ?: return false
        Log.d(TAG, "S3配置: endpoint=${extra.endpoint}, bucket=${extra.bucket}")

        // 使用 OpenDAL 环境变量(与 listBackedUpAppsFromS3WithSql 一致)
        val env = mutableMapOf(
            "OPENDAL_BUCKET" to extra.bucket,
            "OPENDAL_ROOT" to formatOpenDALRoot(cloudEntity.remote),
            "OPENDAL_ENDPOINT" to buildOpenDALEndpoint(extra),
            "OPENDAL_SECRET_ID" to extra.accessKeyId,
            "OPENDAL_SECRET_KEY" to extra.secretAccessKey,
            "RUSTIC_PASSWORD" to password
        )

        val fullSnapshotId = if (!snapshotSubPath.isNullOrEmpty()) {
            "$snapshotId:$snapshotSubPath"
        } else {
            snapshotId
        }

        // Rustic 命令格式: restore <SNAPSHOT> <DESTINATION> -r <REPO>
        val args = mutableListOf(
            "restore",
            fullSnapshotId,      // 快照ID作为第一个位置参数
            targetPath,          // 目标路径作为第二个位置参数(不再用--target)
            "-r", "opendal:cos", // 使用OpenDAL协议
            "--progress-interval", "1s"             // 输出用于进度解析
        )

        if (!includePath.isNullOrEmpty()) {
            args.addAll(listOf("--glob", includePath))  // 改用--glob替代--include
        }

        Log.d(TAG, "即将执行恢复命令...")
        val result = executeRestic(*args.toTypedArray(), env = env, usePty = true)

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
        return rootService.getRusticVersion()
    }

    /**
     * 重构后的初始化仓库方法
     * 走 AIDL：rootService.initRusticRepository -> RemoteRootServiceImpl -> Rustic.initRepository
     * @return Result<String> 成功时包含输出信息，失败时包含异常
     */
    suspend fun initRepository(repoPath: String, password: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // 本地仓库无需 options，走默认 emptyMap()
                val result = rootService.initRusticRepository(repoPath, password)

                if (result.isSuccess) {
                    Result.success("Repository initialized successfully at $repoPath")
                } else {
                    Result.failure(
                        result.exceptionOrNull()
                            ?: Exception("Unknown error during rustic init")
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun listSnapshots(repoPath: String, password: String): List<ResticSnapshot> {
        return withContext(Dispatchers.IO) {
            try {
                val sqlDir = File(context.cacheDir, "sql")
                if (!sqlDir.exists()) sqlDir.mkdirs()

                // 注意：改为 .db（二进制 SQLite），由 librustic 直接写入
                val dbFile = File(sqlDir, "snapshots_local_${System.currentTimeMillis()}.db")

                Log.d(TAG, "执行 JNI listSnapshotsDb，输出路径: ${dbFile.absolutePath}")
                val result = rootService.listRusticSnapshotsDb(repoPath, password, dbFile.absolutePath)
                if (result.isFailure) {
                    Log.e(TAG, "listSnapshotsDb 失败", result.exceptionOrNull())
                    return@withContext emptyList()
                }

                if (!dbFile.exists() || dbFile.length() == 0L) {
                    Log.e(TAG, "DB 文件未生成或为空")
                    return@withContext emptyList()
                }

                val snapshots = parseSnapshotsDb(dbFile)
                dbFile.delete()
                Log.d(TAG, "JNI 模式成功提取 ${snapshots.size} 个快照")
                snapshots
            } catch (e: Exception) {
                Log.e(TAG, "listSnapshots JNI 模式异常", e)
                emptyList()
            }
        }
    }

    /**
     * 从 SQL 文件解析快照信息
     */
    private fun parseSnapshotsDb(dbFile: File): List<ResticSnapshot> {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            val query = """  
            SELECT id, time, hostname, paths_flat, tags_flat  
            FROM v_snapshots_full  
        """
            val cursor = db.rawQuery(query, null)
            val snapshots = mutableListOf<ResticSnapshot>()
            while (cursor.moveToNext()) {
                val pathsFlat = cursor.getString(3) ?: ""
                val tagsFlat = cursor.getString(4) ?: ""
                snapshots.add(
                    ResticSnapshot(
                        id = cursor.getString(0),
                        time = cursor.getString(1),
                        hostname = cursor.getString(2),
                        paths = pathsFlat.split(31.toChar()).filter { it.isNotEmpty() },
                        tags = tagsFlat.split(31.toChar()).filter { it.isNotEmpty() },
                    )
                )
            }
            cursor.close()
            return snapshots
        } finally {
            db.close()
        }
    }

    suspend fun listBackedUpFiles(repoPath: String, password: String): List<ResticBackupFiles> {
        return withContext(Dispatchers.IO) {
            try {
                // 创建 cache/sql/ 目录（复用既有临时目录约定）
                val sqlDir = File(context.cacheDir, "sql")
                if (!sqlDir.exists()) {
                    sqlDir.mkdirs()
                }

                // 目标 .db 文件（由 librustic 在 root 进程写入）
                val dbFile = File(sqlDir, "snapshots_files_${System.currentTimeMillis()}.db")

                Log.d(TAG, "执行本地文件备份 DB 查询(JNI)")
                // 经 AIDL → RemoteRootServiceImpl → Rustic.listSnapshotsDb → librustic
                rootService.listRusticSnapshotsDb(repoPath, password, dbFile.absolutePath)

                if (!dbFile.exists() || dbFile.length() == 0L) {
                    Log.e(TAG, "DB 生成失败或为空")
                    return@withContext emptyList()
                }

                val files = parseFilesDb(dbFile)
                dbFile.delete()

                Log.d(TAG, "DB 模式成功提取 ${files.size} 个文件备份项")
                files
            } catch (e: Exception) {
                Log.e(TAG, "listBackedUpFiles DB 模式异常", e)
                emptyList()
            }
        }
    }

    /**
     * 从原生 librustic 产出的 .db 文件解析文件备份信息。
     * 直接查询 v_snapshots_full 视图（不再执行 .sql 语句流）。
     */
    private fun parseFilesDb(dbFile: File): List<ResticBackupFiles> {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            val query = """  
            SELECT  
                id,  
                time,  
                paths_flat,  
                tags_flat,  
                total_bytes_processed  
            FROM v_snapshots_full  
            WHERE tags_flat IS NOT NULL  
        """

            val cursor = db.rawQuery(query, null)
            val files = mutableListOf<ResticBackupFiles>()

            while (cursor.moveToNext()) {
                val snapshotId = cursor.getString(0)
                val snapshotTime = cursor.getString(1)
                val pathsFlat = cursor.getString(2) ?: ""
                val tagsFlat = cursor.getString(3) ?: continue
                val totalBytesProcessed = cursor.getLong(4)

                // paths_flat / tags_flat 使用 char(31) 作为分隔符
                val paths = pathsFlat.split(31.toChar()).filter { it.isNotEmpty() }
                val tags = tagsFlat.split(31.toChar()).filter { it.isNotEmpty() }

                tags.forEach { tag ->
                    val parts = tag.split("-")
                    // 标签格式: mediaName-timestamp-filesbackup/filesconfig
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
                                val fullPath = paths.firstOrNull() ?: ""
                                files.add(ResticBackupFiles(
                                    mediaName = mediaName,
                                    fullPath = fullPath,
                                    timestamp = timestamp,
                                    dataType = dataType,
                                    snapshotId = snapshotId,
                                    snapshotTime = snapshotTime,
                                    tags = tags,
                                    totalBytesProcessed = totalBytesProcessed
                                ))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解析文件标签出错: $tag", e)
                        }
                    }
                }
            }
            cursor.close()
            return files
        } finally {
            db.close()
        }
    }

    /**
     * 从 SQL 文件解析文件备份信息
     */
    private fun parseSqlFileForFiles(sqlFile: File): List<ResticBackupFiles> {
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(":memory:", null)
        try {
            // 执行 SQL 文件
            sqlFile.readText().split(";").forEach { stmt ->
                val trimmed = stmt.trim()
                if (trimmed.isNotEmpty()) {
                    try {
                        db.execSQL(trimmed + ";")
                    } catch (e: Exception) {
                        Log.w(TAG, "SQL 语句执行警告: ${e.message}")
                    }
                }
            }

            val query = """    
            SELECT     
                id,    
                time,    
                paths_flat,  
                tags_flat,
                total_bytes_processed    
            FROM v_snapshots_full    
            WHERE tags_flat IS NOT NULL    
        """

            val cursor = db.rawQuery(query, null)
            val files = mutableListOf<ResticBackupFiles>()

            while (cursor.moveToNext()) {
                val snapshotId = cursor.getString(0)
                val snapshotTime = cursor.getString(1)
                val pathsFlat = cursor.getString(2) ?: ""
                val tagsFlat = cursor.getString(3) ?: continue
                val totalBytesProcessed = cursor.getLong(4)

                val paths = pathsFlat.split(31.toChar()).filter { it.isNotEmpty() }
                val tags = tagsFlat.split(31.toChar()).filter { it.isNotEmpty() }

                tags.forEach { tag ->
                    val parts = tag.split("-")
                    // 标签格式: mediaName-timestamp-filesbackup/filesconfig
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
                                val fullPath = paths.firstOrNull() ?: ""
                                files.add(ResticBackupFiles(
                                    mediaName = mediaName,
                                    fullPath = fullPath,
                                    timestamp = timestamp,
                                    dataType = dataType,
                                    snapshotId = snapshotId,
                                    snapshotTime = snapshotTime,
                                    tags = tags,
                                    totalBytesProcessed = totalBytesProcessed
                                ))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解析文件标签出错: $tag", e)
                        }
                    }
                }
            }
            cursor.close()
            return files
        } finally {
            db.close()
        }
    }

    /**
     * 从 S3 删除单个快照
     */
    suspend fun forgetSnapshotFromS3(
        cloudEntity: CloudEntity,
        password: String,
        snapshotId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val extra = json.decodeFromString<S3Extra>(cloudEntity.extra) ?: return@withContext false

            val env = mutableMapOf(
                "OPENDAL_BUCKET" to extra.bucket,
                "OPENDAL_ROOT" to formatOpenDALRoot(cloudEntity.remote),
                "OPENDAL_ENDPOINT" to buildOpenDALEndpoint(extra),
                "OPENDAL_SECRET_ID" to extra.accessKeyId,
                "OPENDAL_SECRET_KEY" to extra.secretAccessKey,
                "RUSTIC_PASSWORD" to password
            )

            val args = arrayOf("forget", snapshotId, "-r", "opendal:cos")
            val result = executeRestic(*args, env = env, usePty = false)

            Log.d(TAG, "Forget snapshot 结果: exitCode=${result.code}")
            result.code == 0
        } catch (e: Exception) {
            Log.e(TAG, "删除快照失败: ${e.message}", e)
            false
        }
    }

    /**
     * 清理 S3 仓库中未引用的数据
     */
    suspend fun pruneS3Repository(
        cloudEntity: CloudEntity,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val extra = json.decodeFromString<S3Extra>(cloudEntity.extra) ?: return@withContext false

            val env = mutableMapOf(
                "OPENDAL_BUCKET" to extra.bucket,
                "OPENDAL_ROOT" to formatOpenDALRoot(cloudEntity.remote),
                "OPENDAL_ENDPOINT" to buildOpenDALEndpoint(extra),
                "OPENDAL_SECRET_ID" to extra.accessKeyId,
                "OPENDAL_SECRET_KEY" to extra.secretAccessKey,
                "RUSTIC_PASSWORD" to password
            )

            val args = mutableListOf(
                "prune",
                "-r", "opendal:cos",
                "--max-unused", "unlimited"  // 关键修改:避免重组
            )

            Log.d(TAG, "执行 S3 仓库 prune (unlimited 模式)")
            val result = executeRestic(*args.toTypedArray(), env = env, usePty = false)

            Log.d(TAG, "Prune 结果: exitCode=${result.code}")
            result.code == 0
        } catch (e: Exception) {
            Log.e(TAG, "Prune 失败: ${e.message}", e)
            false
        }
    }

    suspend fun forgetSnapshot(
        repoPath: String,
        password: String,
        snapshotId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = rootService.forgetRusticSnapshot(repoPath, password, snapshotId)
            Log.d(TAG, "Forget snapshot 结果: ${result.isSuccess}")
            result.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "删除快照失败: ${e.message}", e)
            false
        }
    }

    suspend fun pruneRepository(
        repoPath: String,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 本地仓库沿用 --max-unused 10%
            val result = rootService.pruneRusticRepository(repoPath, password, "10%")
            Log.d(TAG, "Prune 结果: ${result.isSuccess}")
            result.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Prune 失败: ${e.message}", e)
            false
        }
    }

    suspend fun validateRepository(repoPath: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            rootService.validateRusticRepository(repoPath, password).isSuccess
        }

    suspend fun deleteRepository(repoPath: String): Boolean = withContext(Dispatchers.IO) {
        try { File(repoPath).deleteRecursively() } catch (e: Exception) { false }
    }

    suspend fun checkRepository(repoPath: String, password: String): Boolean {
        return withContext(Dispatchers.IO) {
            // 迁移说明：改走 JNI（librustic.so，经 RootService），不再 spawn restic 二进制。
            // 原生 checkRepository 成功返回、失败抛异常（含"仓库不存在/config 缺失"），
            // 因此用 Result.isSuccess 直接映射为 Boolean，替代原来对 stdout 文本的解析。
            val result = rootService.checkRusticRepository(repoPath, password)
            if (result.isSuccess) {
                true
            } else {
                Log.w(TAG, "Rustic check failed: ${result.exceptionOrNull()?.message}")
                false
            }
        }
    }

    suspend fun listBackedUpApps(repoPath: String, password: String): List<ResticBackupApp> {
        return withContext(Dispatchers.IO) {
            try {
                // 创建 cache/sql/ 目录（沿用旧目录，仅扩展名改为 .db）
                val sqlDir = File(context.cacheDir, "sql")
                if (!sqlDir.exists()) {
                    sqlDir.mkdirs()
                }

                val dbFile = File(sqlDir, "snapshots_apps_${System.currentTimeMillis()}.db")

                Log.d(TAG, "执行本地应用备份 DB 查询（JNI）: ${dbFile.absolutePath}")

                // 经 AIDL → RemoteRootServiceImpl → Rustic.listSnapshotsDb 在 root 进程生成 .db
                val result = rootService.listRusticSnapshotsDb(repoPath, password, dbFile.absolutePath)

                if (result.isFailure) {
                    Log.e(TAG, "listBackedUpApps DB 生成失败", result.exceptionOrNull())
                    dbFile.delete()
                    return@withContext emptyList()
                }

                if (!dbFile.exists() || dbFile.length() == 0L) {
                    Log.e(TAG, "DB 文件未生成或为空")
                    dbFile.delete()
                    return@withContext emptyList()
                }

                val apps = parseAppsDb(dbFile)
                dbFile.delete()

                Log.d(TAG, "DB 模式成功提取 ${apps.size} 个应用备份项")
                apps
            } catch (e: Exception) {
                Log.e(TAG, "listBackedUpApps DB 模式异常", e)
                emptyList()
            }
        }
    }

    /**
     * 使用 Rustic OpenDAL SQL 模式从 S3 获取文件备份列表
     */
    suspend fun listBackedUpFilesFromS3WithSql(
        cloudEntity: CloudEntity,
        password: String
    ): List<ResticBackupFiles> = withContext(Dispatchers.IO) {
        try {
            val extra = json.decodeFromString<S3Extra>(cloudEntity.extra) ?: return@withContext emptyList()

            // 创建 cache/sql/ 目录
            val sqlDir = File(context.cacheDir, "sql")
            if (!sqlDir.exists()) {
                sqlDir.mkdirs()
            }

            // SQL 文件保存到 cache/sql/ 目录
            val sqlFile = File(sqlDir, "snapshots_files_${System.currentTimeMillis()}.sql")

            val env = mutableMapOf(
                "OPENDAL_BUCKET" to extra.bucket,
                "OPENDAL_ROOT" to formatOpenDALRoot(cloudEntity.remote),
                "OPENDAL_ENDPOINT" to buildOpenDALEndpoint(extra),
                "OPENDAL_SECRET_ID" to extra.accessKeyId,
                "OPENDAL_SECRET_KEY" to extra.secretAccessKey,
                "RUSTIC_PASSWORD" to password
            )

            val args = mutableListOf(
                "--no-progress",
                "snapshots",
                "-r", "opendal:cos",
                "--sql",
                "--sql-output", sqlFile.absolutePath
            )

            Log.d(TAG, "执行 Rustic SQL 查询(文件备份),输出路径: ${sqlFile.absolutePath}")
            val result = executeRestic(*args.toTypedArray(), env = env, usePty = true)

            if (!result.isSuccess) {
                Log.e(TAG, "SQL 生成失败 (Exit Code: ${result.code})")
                Log.e(TAG, "标准输出: ${result.out.joinToString("\n")}")
                Log.e(TAG, "错误输出: ${result.err.joinToString("\n")}")
                return@withContext emptyList()
            }

            if (!sqlFile.exists()) {
                Log.e(TAG, "SQL 文件未生成: ${sqlFile.absolutePath}")
                return@withContext emptyList()
            }

            if (sqlFile.length() == 0L) {
                Log.e(TAG, "SQL 文件为空")
                return@withContext emptyList()
            }

            val files = parseSqlFileForFiles(sqlFile)
            sqlFile.delete()  // 清理临时文件

            Log.d(TAG, "SQL 模式成功提取 ${files.size} 个文件备份项")
            files
        } catch (e: Exception) {
            Log.e(TAG, "listBackedUpFilesFromS3WithSql 异常", e)
            emptyList()
        }
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
                "RUSTIC_PASSWORD" to password
            )
            if (extra.region.isNotEmpty()) {
                env["AWS_DEFAULT_REGION"] = extra.region
            }

            // 4. 构建命令行参数
            val args = mutableListOf(
                "snapshots",
                "-r", repoUrl,
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
                                            tags = snapshot.tags,
                                            totalBytesProcessed = snapshot.summary?.total_bytes_processed ?: 0L
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
                "RUSTIC_PASSWORD" to password
            )
            if (extra.region.isNotEmpty()) {
                env["AWS_DEFAULT_REGION"] = extra.region
            }

            // 4. 构建命令行参数
            val args = mutableListOf(
                "snapshots",
                "-r", repoUrl,
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
                                            tags = snapshot.tags,
                                            totalBytesProcessed = snapshot.summary?.total_bytes_processed ?: 0L
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
     * 使用 Rustic 备份到本地仓库（JNI 版）
     * 经 RootService → RemoteRootServiceImpl.createRusticSnapshot → Rustic.createSnapshot → librustic.so
     *
     * 返回值契约保持不变：Pair<退出码, 输出字符串>
     * - 成功：Pair(0, 合成的 summary JSON)，其中含 "message_type":"summary" 与 "snapshot_id":"<id>"，
     *   供上层 AbstractBackupService.extractSnapshotIdFromJson 正则抠出快照 ID。
     * - 失败：Pair(1, 错误信息)
     *
     * 注意：JNI 路径不再走 RUSTIC_STOP_FILE / additionalEnv / usePty；
     * 备份中途取消暂不支持（待 ICancellationSignal 阶段）。additionalEnv 仅保留以兼容签名。
     */
    suspend fun backupWithResticToLocal(
        repoPath: String,
        password: String,
        filePath: String,
        tags: List<String>,
        additionalEnv: Map<String, String> = emptyMap(),
        progressCallback: ResticProgressCallback? = null
    ): Pair<Int, String> {
        return withContext(Dispatchers.IO) {
            try {
                // ICallback 适配：JNI 三参进度 → ResticProgressCallback.onBackupProgress 五参
                val callback: ICallback? = if (progressCallback != null) {
                    object : ICallback.Stub() {
                        override fun onProgress(bytesWritten: Long, speed: Long, progress: Float) {
                            progressCallback.onBackupProgress(
                                percentDone = progress,
                                bytesDone = bytesWritten,
                                bytesTotal = 0L,   // JNI 进度不提供总量
                                filesDone = 0L,
                                filesTotal = 0L
                            )
                        }
                        override fun onRestorePlan(
                            filesTotal: Long,
                            bytesTotal: Long,
                            filesSkipped: Long,
                            bytesSkipped: Long
                        ) {
                            // 备份链路不需要 restore plan 统计，空实现
                        }
                    }
                } else null

                val snapshotId = rootService.createRusticSnapshot(
                    repositoryPath = repoPath,
                    password = password,
                    sourcePaths = listOf(filePath),
                    tags = tags,
                    callback = callback
                )

                if (snapshotId.isNotBlank()) {
                    // 合成一行 summary JSON，令上层 extractSnapshotIdFromJson 正则命中
                    val summaryJson = """{"message_type":"summary","snapshot_id":"$snapshotId"}"""
                    Pair(0, summaryJson)
                } else {
                    Pair(1, "Rustic returned an empty snapshot ID")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during local Rustic backup", e)
                Pair(1, e.message ?: "Unknown error")
            }
        }
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
        return withContext(Dispatchers.IO) {
            try {
                // 使用 OpenDAL 环境变量(与 listBackedUpAppsFromS3WithSql 一致)
                val env = mutableMapOf(
                    "OPENDAL_BUCKET" to extra.bucket,
                    "OPENDAL_ROOT" to formatOpenDALRoot(remotePath),
                    "OPENDAL_ENDPOINT" to buildOpenDALEndpoint(extra),
                    "OPENDAL_SECRET_ID" to extra.accessKeyId,
                    "OPENDAL_SECRET_KEY" to extra.secretAccessKey,
                    "RUSTIC_PASSWORD" to password
                )

                // Rustic 命令格式: init -r opendal:cos
                val args = mutableListOf(
                    "init",
                    "-r", "opendal:cos"
                )

                val result = executeRestic(*args.toTypedArray(), env = env, usePty = true)

                val output = if (result.isSuccess) {
                    result.out.joinToString("\n")
                } else {
                    result.err.joinToString("\n")
                }

                if (result.isSuccess) {
                    Result.success(output)
                } else {
                    Result.failure(Exception(output.ifEmpty { "Unknown error during rustic init" }))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
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
        return withContext(Dispatchers.IO) {
            try {
                val tag = tags.firstOrNull() ?: "default"
                val stopFilePath = File(context.cacheDir, tag).absolutePath

                val env = mutableMapOf(
                    "OPENDAL_BUCKET" to extra.bucket,
                    "OPENDAL_ROOT" to formatOpenDALRoot(remotePath),
                    "OPENDAL_ENDPOINT" to buildOpenDALEndpoint(extra),
                    "OPENDAL_SECRET_ID" to extra.accessKeyId,
                    "OPENDAL_SECRET_KEY" to extra.secretAccessKey,
                    "RUSTIC_PASSWORD" to password,
                    "RUSTIC_INSTANCE_LABEL" to tag,
                    "RUSTIC_STOP_FILE" to stopFilePath
                )

                // Rustic 命令格式: backup <SOURCE> -r opendal:cos --tag <TAGS>
                val args = mutableListOf(
                    "backup",
                    filePath,
                    "-r", "opendal:cos",
                    "--progress-interval", "1s"
                )

                // 添加标签
                tags.forEach { tag ->
                    args.add("--tag")
                    args.add(tag)
                }

                val result = executeRestic(*args.toTypedArray(), env = env, usePty = true)

                Pair(result.code, result.out.joinToString("\n"))
            } catch (e: Exception) {
                Log.e(TAG, "Error during S3 Rustic backup", e)
                Pair(1, e.message ?: "Unknown error")
            }
        }
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
    val tags: List<String>,
    val summary: SnapshotSummary? = null
)

@Serializable
data class SnapshotSummary(
    val total_bytes_processed: Long? = null,
    val files_new: Long? = null,
    val files_changed: Long? = null,
    val files_unmodified: Long? = null,
    val total_files_processed: Long? = null,
    val dirs_new: Long? = null,
    val dirs_changed: Long? = null,
    val dirs_unmodified: Long? = null,
    val data_blobs: Long? = null,
    val tree_blobs: Long? = null,
    val data_added: Long? = null,
    val data_added_packed: Long? = null
)