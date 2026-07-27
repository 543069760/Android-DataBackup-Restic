package com.xayah.core.restic

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.xayah.core.model.DataType
import com.xayah.core.model.restic.ResticBackupApp
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.model.database.S3Extra
import com.xayah.core.model.database.S3Protocol
import com.xayah.core.rootservice.service.RemoteRootService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地备份与 COS/S3 备份共用的底层能力集合。
 * 由 ResticRepositoryLocal / ResticRepositoryCos 通过构造注入组合使用。
 */
@Singleton
class ResticShared @Inject constructor(
    @ApplicationContext val context: Context,
    val logger: ResticLogger,
    val resticNative: ResticNative,
    val rootService: RemoteRootService,
) {
    companion object {
        const val TAG = "ResticRepository"
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    val resticPath: String by lazy {
        resticNative.getResticBinaryPath(context)
    }

    /**
     * 核心执行方法:使用 libsu 执行 Root 命令
     */
    suspend fun executeRestic(
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

    /** 格式化 OpenDAL Root 路径,确保以 / 开头以 / 结尾 */
    fun formatOpenDALRoot(remotePath: String): String {
        val trimmed = remotePath.trim()
        if (trimmed.isEmpty() || trimmed == "/") {
            return "/"
        }
        val withLeading = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return if (withLeading.endsWith("/")) withLeading else "$withLeading/"
    }

    /** 构建 OpenDAL Endpoint,格式: protocol://endpoint (不含 bucket) */
    fun buildOpenDALEndpoint(extra: S3Extra): String {
        val protocol = when (extra.protocol) {
            S3Protocol.HTTP -> "http"
            S3Protocol.HTTPS -> "https"
        }
        return "$protocol://${extra.endpoint.trim().removeSuffix("/")}"
    }

    /** 从 rustic 生成的 .db 解析应用备份信息(v_snapshots_full 视图) */
    fun parseAppsDb(dbFile: File): List<ResticBackupApp> {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            val query = """  
            SELECT id, time, tags_flat, total_bytes_processed  
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
                                    packageName = packageName, userId = userId,
                                    timestamp = timestamp, dataType = dataType,
                                    snapshotId = snapshotId, snapshotTime = snapshotTime,
                                    tags = tags, totalBytesProcessed = totalBytesProcessed
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

    /** 从 .db 解析快照信息 */
    fun parseSnapshotsDb(dbFile: File): List<ResticSnapshot> {
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

    /** 从 .db 解析文件备份信息(v_snapshots_full 视图) */
    fun parseFilesDb(dbFile: File): List<ResticBackupFiles> {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            val query = """  
            SELECT id, time, paths_flat, tags_flat, total_bytes_processed  
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
                                    mediaName = mediaName, fullPath = fullPath,
                                    timestamp = timestamp, dataType = dataType,
                                    snapshotId = snapshotId, snapshotTime = snapshotTime,
                                    tags = tags, totalBytesProcessed = totalBytesProcessed
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
}