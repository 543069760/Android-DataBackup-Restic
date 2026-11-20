package com.xayah.feature.main.restore

import android.content.Context
import android.util.Log
import com.xayah.core.model.restic.ResticBackupFiles
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 文件恢复列表的持久缓存（filesDir/db_cache/files_<accountId>.json）。
 *
 * 与 app 恢复列表的 apps_<accountId>.db 缓存同构：
 *   进入列表先读缓存秒开（不闪 Loading），再后台重建 restic 快照列表并静默替换。
 * 这样每次进入文件恢复列表都不必全量重扫仓库（云端尤其明显）。
 *
 * 放在 filesDir 而非 cacheDir：cacheDir 会被系统在低存储时清理，导致缓存形同虚设。
 */
internal object ResticFilesCache {
    private const val TAG = "ResticFilesCache"

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ResticBackupFiles.serializer())

    private fun cacheDir(context: Context): File =
        File(context.filesDir, "db_cache").apply { if (!exists()) mkdirs() }

    private fun cacheFile(context: Context, accountId: String): File {
        val safe = accountId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(cacheDir(context), "files_$safe.json")
    }

    /** 只读：缓存不存在/为空/解析失败一律返回 emptyList，绝不触发任何 JNI/网络重建。 */
    fun read(context: Context, accountId: String): List<ResticBackupFiles> {
        val f = cacheFile(context, accountId)
        if (!f.exists() || f.length() == 0L) return emptyList()
        return runCatching { json.decodeFromString(serializer, f.readText()) }
            .getOrElse {
                Log.e(TAG, "read failed accountId=$accountId", it)
                emptyList()
            }
    }

    /** 原子写：先写临时文件再 rename，避免写一半被读到。失败只记日志，不影响主流程。 */
    fun write(context: Context, accountId: String, files: List<ResticBackupFiles>) {
        runCatching {
            val target = cacheFile(context, accountId)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(json.encodeToString(serializer, files))
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }.onFailure { Log.e(TAG, "write failed accountId=$accountId", it) }
    }
}