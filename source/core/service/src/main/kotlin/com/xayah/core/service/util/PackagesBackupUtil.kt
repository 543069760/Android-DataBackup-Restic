package com.xayah.core.service.util

import android.content.Context
import android.content.pm.PackageManager
import com.xayah.core.model.util.formatToStorageSizePerSecond
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readFollowSymlinks
import com.xayah.core.datastore.readSelectionType
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataType
import com.xayah.core.model.OperationState
import com.xayah.core.model.SelectionType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.IconRelativeDir
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.Tar
import com.xayah.core.util.filesDir
import com.xayah.core.util.model.ShellResult
import com.xayah.core.model.OpType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.io.File

class PackagesBackupUtil @Inject constructor(
    @ApplicationContext val context: Context,
    private val rootService: RemoteRootService,
    private val taskDao: TaskDao,
    private val packageRepository: PackageRepository,
    private val commonBackupUtil: CommonBackupUtil,
    private val cloudRepository: CloudRepository,
) {
    companion object {
        private const val TAG = "PackagesBackupUtil"
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }

    /**
     * 准备 argv 的结果：
     * - Ready：可流式打包，携带 tar 的 argv、源大小(用于进度/统计)、快照内逻辑归档名
     * - Skip ：未选中 / 源不存在(非 USER) / 数据未变化，调用侧应跳过且不视为失败
     * - Error：源缺失(USER 必备) 或 取路径失败，调用侧应记 ERROR
     */
    sealed interface ArgvResult {
        data class Ready(
            val argv: Array<String>,
            val sizeBytes: Long,
            val stdinFilename: String,
        ) : ArgvResult

        data object Skip : ArgvResult

        data class Error(val out: List<String>) : ArgvResult
    }

    private fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { TAG to msg }
        msg
    }

    // JNI tar 的公共 argv 前缀（xattr/selinux 往返一致，两端必须相同）
    private val tarHeader =
        arrayOf("tar", "--xattrs", "--xattrs-include=*", "--acls", "--selinux", "--totals")

    /** callTar lambda：交给 root 进程执行 GNU tar */
    private val callTar = Tar.CallTar { stdOut, stdErr, argv ->
        rootService.callTarCli(stdOut, stdErr, argv)
    }

    private suspend fun PackageEntity.getDataSelected(dataType: DataType) =
        when (context.readSelectionType().first()) {
            SelectionType.DEFAULT -> {
                when (dataType) {
                    DataType.PACKAGE_APK -> apkSelected
                    DataType.PACKAGE_USER -> userSelected
                    DataType.PACKAGE_USER_DE -> userDeSelected
                    DataType.PACKAGE_DATA -> dataSelected
                    DataType.PACKAGE_OBB -> obbSelected
                    DataType.PACKAGE_MEDIA -> mediaSelected
                    else -> false
                }
            }

            SelectionType.APK -> dataType == DataType.PACKAGE_APK
            SelectionType.DATA -> dataType != DataType.PACKAGE_APK
            SelectionType.BOTH -> true
        }

    private fun PackageEntity.getDataBytes(dataType: DataType) = when (dataType) {
        DataType.PACKAGE_APK -> dataStats.apkBytes
        DataType.PACKAGE_USER -> dataStats.userBytes
        DataType.PACKAGE_USER_DE -> dataStats.userDeBytes
        DataType.PACKAGE_DATA -> dataStats.dataBytes
        DataType.PACKAGE_OBB -> dataStats.obbBytes
        DataType.PACKAGE_MEDIA -> dataStats.mediaBytes
        else -> 0
    }

    private fun PackageEntity.setDataBytes(dataType: DataType, sizeBytes: Long) = when (dataType) {
        DataType.PACKAGE_APK -> dataStats.apkBytes = sizeBytes
        DataType.PACKAGE_USER -> dataStats.userBytes = sizeBytes
        DataType.PACKAGE_USER_DE -> dataStats.userDeBytes = sizeBytes
        DataType.PACKAGE_DATA -> dataStats.dataBytes = sizeBytes
        DataType.PACKAGE_OBB -> dataStats.obbBytes = sizeBytes
        DataType.PACKAGE_MEDIA -> dataStats.mediaBytes = sizeBytes
        else -> Unit
    }

    private fun PackageEntity.setDisplayBytes(dataType: DataType, sizeBytes: Long) =
        when (dataType) {
            DataType.PACKAGE_APK -> displayStats.apkBytes = sizeBytes
            DataType.PACKAGE_USER -> displayStats.userBytes = sizeBytes
            DataType.PACKAGE_USER_DE -> displayStats.userDeBytes = sizeBytes
            DataType.PACKAGE_DATA -> displayStats.dataBytes = sizeBytes
            DataType.PACKAGE_OBB -> displayStats.obbBytes = sizeBytes
            DataType.PACKAGE_MEDIA -> displayStats.mediaBytes = sizeBytes
            else -> Unit
        }

    private suspend fun TaskDetailPackageEntity.updateInfo(
        dataType: DataType,
        state: OperationState? = null,
        bytes: Long? = null,
        log: String? = null,
        content: String? = null,
    ) = run {
        val info = when (dataType) {
            DataType.PACKAGE_APK -> apkInfo
            DataType.PACKAGE_USER -> userInfo
            DataType.PACKAGE_USER_DE -> userDeInfo
            DataType.PACKAGE_DATA -> dataInfo
            DataType.PACKAGE_OBB -> obbInfo
            DataType.PACKAGE_MEDIA -> mediaInfo
            else -> null
        }
        info?.let {
            if (state != null) it.state = state
            if (bytes != null) it.bytes = bytes
            if (log != null) it.log = log
            if (content != null) it.content = content
        }
        taskDao.upsert(this)
    }

    private fun TaskDetailPackageEntity.getLog(
        dataType: DataType,
    ) = when (dataType) {
        DataType.PACKAGE_APK -> apkInfo.log
        DataType.PACKAGE_USER -> userInfo.log
        DataType.PACKAGE_USER_DE -> userDeInfo.log
        DataType.PACKAGE_DATA -> dataInfo.log
        DataType.PACKAGE_OBB -> obbInfo.log
        DataType.PACKAGE_MEDIA -> mediaInfo.log
        else -> ""
    }

    private suspend fun getPackageSourceDir(packageName: String, userId: Int) =
        rootService.getPackageSourceDir(packageName, userId).let { list ->
            if (list.isNotEmpty()) PathUtil.getParentPath(list[0]) else ""
        }

    // ===================== FIFO 流式（app backup 走这条链路） =====================

    /**
     * 生成 APK 的流式 argv（喂给 rustic 的 stdin）。
     * JNI tar 无 shell，不能用通配符 glob，需先枚举真实文件名。
     */
    suspend fun prepareApkArgv(
        p: PackageEntity,
        r: PackageEntity?,
        t: TaskDetailPackageEntity,
    ): ArgvResult = run {
        val dataType = DataType.PACKAGE_APK
        if (p.getDataSelected(dataType).not()) {
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
            return@run ArgvResult.Skip
        }

        val srcDir = getPackageSourceDir(packageName = p.packageName, userId = p.userId)
        if (srcDir.isEmpty()) {
            val out = mutableListOf(log { "Failed to get apk src dir." })
            return@run ArgvResult.Error(out)
        }

        val apkFiles = rootService.listFilePaths(srcDir, true, false)
            .map { PathUtil.getFileName(it) }
            .filter { it.endsWith(".apk") }
        if (apkFiles.isEmpty()) {
            val out = mutableListOf(log { "No apk found in $srcDir." })
            return@run ArgvResult.Error(out)
        }

        val sizeBytes = rootService.calculateSize(srcDir)
        t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = sizeBytes)

        val argv = mutableListOf(
            "tar", "--xattrs", "--xattrs-include=*", "--acls", "--selinux",
            "--totals", "-cpf", "-", "-C", srcDir, "--"
        )
        argv.addAll(apkFiles)

        ArgvResult.Ready(
            argv = argv.toTypedArray(),
            sizeBytes = sizeBytes,
            stdinFilename = "${dataType.type}.tar",
        )
    }

    /**
     * 生成 data（USER/USER_DE/DATA/OBB/MEDIA）的流式 argv。
     * exclusion 不加 shell 引号（JNI tar 不经 shell）。
     */
    suspend fun prepareDataArgv(
        p: PackageEntity,
        r: PackageEntity?,
        t: TaskDetailPackageEntity,
        dataType: DataType,
    ): ArgvResult {
        log { "Preparing ${dataType.type} argv..." }
        val packageName = p.packageName
        val userId = p.userId
        val out = mutableListOf<String>()

        if (p.getDataSelected(dataType).not()) {
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
            return ArgvResult.Skip
        }

        val srcDir = packageRepository.getDataSrcDir(dataType, userId)
        val src = packageRepository.getDataSrc(srcDir, packageName)
        if (rootService.exists(src).not()) {
            return if (dataType == DataType.PACKAGE_USER) {
                out.add(log { "Not exist: $src" })
                t.updateInfo(dataType = dataType, state = OperationState.ERROR, log = out.toLineString())
                ArgvResult.Error(out)
            } else {
                out.add(log { "Not exist and skip: $src" })
                t.updateInfo(dataType = dataType, state = OperationState.SKIP, log = out.toLineString())
                ArgvResult.Skip
            }
        }

        // 生成排除项（无 shell 引号）
        val exclusionList = mutableListOf<String>()
        when (dataType) {
            DataType.PACKAGE_USER, DataType.PACKAGE_USER_DE -> {
                val folders = listOf(".ota", "cache", "lib", "code_cache", "no_backup")
                exclusionList.addAll(folders.map { "$packageName/$it" })
            }

            DataType.PACKAGE_DATA, DataType.PACKAGE_OBB, DataType.PACKAGE_MEDIA -> {
                exclusionList.add("$packageName/cache")
                exclusionList.add("Backup_*")
            }

            else -> {}
        }
        log { "ExclusionList: $exclusionList." }

        val sizeBytes = rootService.calculateSize(src)
        t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = sizeBytes)

        val argv = mutableListOf(*tarHeader)
        exclusionList.forEach { argv.add("--exclude=$it") }
        if (context.readFollowSymlinks().first()) argv.add("-h")
        argv.addAll(listOf("-cpf", "-", "-C", srcDir, "--", packageName))

        return ArgvResult.Ready(
            argv = argv.toTypedArray(),
            sizeBytes = sizeBytes,
            stdinFilename = "${dataType.type}.tar",
        )
    }

    // ===================== 落盘 .tar（云备份 BackupServiceCloudImpl 仍走这里） =====================

    private val tarCt = CompressionType.TAR

    fun getIconsAndLabelsDst(dstDir: String) = "${dstDir}/$IconRelativeDir.${tarCt.suffix}"

    /**
     * 备份应用图标与名称:先把本次已激活备份应用的 label 映射写成 labels.json
     * 放进 icon 目录(与 png 同级),再连同图标一起压进 icon.tar。
     * labels.json 的 key = "<userId>-<packageName>",value = 应用名(label)。
     * stripComponents=1 解压后落在 filesDir/icon/<accountId>/labels.json。
     */
    suspend fun backupIconsAndLabels(dstDir: String): ShellResult = run {
        log { "Backing up icons and labels..." }

        // 独立暂存目录：与恢复落地点 filesDir/icon/ 彻底解耦，根除套娃
        val stagingRoot = File(context.cacheDir, "icon_backup_staging")
        val stagingIconDir = File(stagingRoot, IconRelativeDir)   // .../icon_backup_staging/icon
        val liveIconDir = File("${context.filesDir()}/$IconRelativeDir")

        runCatching {
            // 每次先清空暂存，保证干净
            stagingRoot.deleteRecursively()
            stagingIconDir.mkdirs()

            // 1) 只拷贝顶层扁平图标文件（<pkg>.png / adaptive@<pkg>.png），
            //    排除历史 <accountId>/ 子目录（套娃来源），也排除旧 labels.json
            liveIconDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".png")) {
                    f.copyTo(File(stagingIconDir, f.name), overwrite = true)
                }
            }

            // 2) labels.json：累积合并 + 全量映射（沿用你本地逻辑）
            val activated = packageRepository.queryActivated(OpType.BACKUP)
            val labelMap: Map<String, String> = activated.associate { pkg ->
                "${pkg.userId}-${pkg.packageName}" to pkg.packageInfo.label
            }
            val installedMap: Map<String, String> =
                packageRepository.queryPackages(OpType.BACKUP, blocked = false)
                    .associate { pkg -> "${pkg.userId}-${pkg.packageName}" to pkg.packageInfo.label }
                    .filterValues { it.isNotEmpty() }
            // 读旧累积 labels.json（仍从持久的 live 目录读，保留跨次累积）
            val oldMap: Map<String, String> = File(liveIconDir, "labels.json").let { lf ->
                if (lf.exists())
                    runCatching { json.decodeFromString<Map<String, String>>(lf.readText()) }
                        .getOrElse { emptyMap() }
                else emptyMap()
            }
            val mergedMap = oldMap + installedMap + labelMap

            // 持久保存到 live 目录（作为下次备份的累积源），并写一份进暂存目录用于本次打包
            liveIconDir.mkdirs()
            File(liveIconDir, "labels.json").writeText(json.encodeToString(mergedMap))
            File(stagingIconDir, "labels.json").writeText(json.encodeToString(mergedMap))
            log { "labels.json written: ${mergedMap.size} entries (old=${oldMap.size}, installed=${installedMap.size}, activated=${labelMap.size}) -> staging=${stagingIconDir.absolutePath}" }
        }.onFailure {
            // 失败不阻断图标备份，仅记录
            log { "Failed to prepare icon staging: ${it.message}" }
        }

        // 3) 只压缩暂存里的 icon 子目录（结构：icon/<pkg>.png + icon/labels.json），
        //    stripComponents=1 恢复后干净落到 filesDir/icon/<accountId>/，不再套娃
        val dst = getIconsAndLabelsDst(dstDir = dstDir)
        var isSuccess: Boolean
        val out = mutableListOf<String>()

        Tar.compressIconsToFile(
            cacheDir = context.cacheDir.path,
            callTar = callTar,
            srcDir = stagingRoot.absolutePath,   // 暂存根目录
            src = IconRelativeDir,               // icon 子目录
            dst = dst,
        ).also { result ->
            isSuccess = result.isSuccess
            out.addAll(result.out)
        }
        commonBackupUtil.testArchive(src = dst, ct = tarCt).also { result ->
            isSuccess = isSuccess && result.isSuccess
            out.addAll(result.out)
        }

        // 4) 清理暂存，避免残留占用 cacheDir
        runCatching { stagingRoot.deleteRecursively() }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    suspend fun backupApk(
        p: PackageEntity,
        r: PackageEntity?,
        t: TaskDetailPackageEntity,
        dstDir: String,
        isCanceled: (() -> Boolean)? = null
    ): ShellResult = run {
        log { "Backing up apk..." }

        val dataType = DataType.PACKAGE_APK
        val packageName = p.packageName
        val userId = p.userId
        val ct = CompressionType.TAR
        val dst = packageRepository.getArchiveDst(dstDir = dstDir, dataType = dataType, ct = ct)
        var isSuccess: Boolean
        val out = mutableListOf<String>()
        val srcDir = getPackageSourceDir(packageName = packageName, userId = userId)

        if (p.getDataSelected(dataType).not()) {
            isSuccess = true
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
        } else {
            if (srcDir.isNotEmpty()) {
                val sizeBytes = rootService.calculateSize(srcDir)
                t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = sizeBytes)
                if (rootService.exists(dst) && sizeBytes == r?.getDataBytes(dataType)) {
                    isSuccess = true
                    t.updateInfo(dataType = dataType, state = OperationState.SKIP)
                    out.add(log { "Data has not changed." })
                } else {
                    // JNI tar 无 shell，枚举 *.apk 真实文件名后落盘
                    val apkFiles = rootService.listFilePaths(srcDir, true, false)
                        .map { PathUtil.getFileName(it) }
                        .filter { it.endsWith(".apk") }
                    Tar.compressFilesToFile(
                        cacheDir = context.cacheDir.path,
                        callTar = callTar,
                        srcDir = srcDir,
                        files = apkFiles,
                        dst = dst,
                    ).also { result ->
                        isSuccess = result.isSuccess
                        out.addAll(result.out)

                        // 压缩完成后立即检查取消标志
                        if (isCanceled?.invoke() == true) {
                            log { "Backup canceled after compression" }
                            isSuccess = false
                            out.add("Backup canceled by user")
                            return@run ShellResult(code = -1, input = listOf(), out = out)
                        }
                    }
                }
            } else {
                isSuccess = false
                out.add(log { "Failed to get apk path of $packageName." })
            }
            t.updateInfo(
                dataType = dataType,
                state = if (isSuccess) OperationState.DONE else OperationState.ERROR,
                log = out.toLineString()
            )
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    /**
     * Package data: USER, USER_DE, DATA, OBB, MEDIA
     */
    suspend fun backupData(
        p: PackageEntity,
        t: TaskDetailPackageEntity,
        r: PackageEntity?,
        dataType: DataType,
        dstDir: String,
        isCanceled: (() -> Boolean)? = null
    ): ShellResult = run {
        log { "Backing up ${dataType.type}..." }

        val packageName = p.packageName
        val userId = p.userId
        val ct = CompressionType.TAR
        val dst = packageRepository.getArchiveDst(dstDir = dstDir, dataType = dataType, ct = ct)
        var isSuccess: Boolean
        val out = mutableListOf<String>()
        val srcDir = packageRepository.getDataSrcDir(dataType, userId)

        if (p.getDataSelected(dataType).not()) {
            isSuccess = true
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
        } else {
            // Check the existence of origin path.
            val src = packageRepository.getDataSrc(srcDir, packageName)
            rootService.exists(src).also {
                if (it.not()) {
                    if (dataType == DataType.PACKAGE_USER) {
                        isSuccess = false
                        out.add(log { "Not exist: $src" })
                        t.updateInfo(dataType = dataType, state = OperationState.ERROR, log = out.toLineString())
                        return@run ShellResult(code = -1, input = listOf(), out = out)
                    } else {
                        out.add(log { "Not exist and skip: $src" })
                        t.updateInfo(dataType = dataType, state = OperationState.SKIP, log = out.toLineString())
                        return@run ShellResult(code = -2, input = listOf(), out = out)
                    }
                }
            }

            // Generate exclusion items（无 shell 引号）.
            val exclusionList = mutableListOf<String>()
            when (dataType) {
                DataType.PACKAGE_USER, DataType.PACKAGE_USER_DE -> {
                    val folders = listOf(".ota", "cache", "lib", "code_cache", "no_backup")
                    exclusionList.addAll(folders.map { "$packageName/$it" })
                }

                DataType.PACKAGE_DATA, DataType.PACKAGE_OBB, DataType.PACKAGE_MEDIA -> {
                    exclusionList.add("$packageName/cache")
                    exclusionList.add("Backup_*")
                }

                else -> {}
            }
            log { "ExclusionList: $exclusionList." }

            val sizeBytes = rootService.calculateSize(src)
            t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = sizeBytes)
            if (rootService.exists(dst) && sizeBytes == r?.getDataBytes(dataType)) {
                isSuccess = true
                t.updateInfo(dataType = dataType, state = OperationState.SKIP)
                out.add(log { "Data has not changed." })
            } else {
                // Compress and test.
                Tar.compressToFile(
                    cacheDir = context.cacheDir.path,
                    callTar = callTar,
                    exclusionList = exclusionList,
                    h = if (context.readFollowSymlinks().first()) "-h" else "",
                    srcDir = srcDir,
                    src = packageName,
                    dst = dst,
                ).also { result ->
                    isSuccess = result.isSuccess
                    out.addAll(result.out)

                    // 压缩完成后立即检查取消标志
                    if (isCanceled?.invoke() == true) {
                        log { "Backup canceled after compression" }
                        isSuccess = false
                        out.add("Backup canceled by user")
                        return@run ShellResult(code = -1, input = listOf(), out = out)
                    }
                }
                commonBackupUtil.testArchive(src = dst, ct = ct).also { result ->
                    isSuccess = isSuccess && result.isSuccess
                    out.addAll(result.out)
                    if (result.isSuccess) {
                        p.setDataBytes(dataType, sizeBytes)
                        p.setDisplayBytes(dataType, rootService.calculateSize(dst))
                    }
                }
            }

            t.updateInfo(
                dataType = dataType,
                state = if (isSuccess) OperationState.DONE else OperationState.ERROR,
                log = out.toLineString()
            )
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    suspend fun backupPermissions(p: PackageEntity) = run {
        log { "Backing up permissions..." }

        val packageName = p.packageName
        val userId = p.userId

        val packageInfo = rootService.getPackageInfoAsUser(packageName, PackageManager.GET_PERMISSIONS, userId)
        packageInfo?.apply { p.extraInfo.permissions = rootService.getPermissions(packageInfo = this) }
        val permissions = p.extraInfo.permissions
        log { "Permissions size: ${permissions.size}..." }
        permissions.forEach {
            log { "Permission name: ${it.name}, isGranted: ${it.isGranted}, op: ${it.op}, mode: ${it.mode}" }
        }
    }

    suspend fun backupSsaid(p: PackageEntity) = run {
        log { "Backing up ssaid..." }

        val packageName = p.packageName
        val uid = p.extraInfo.uid
        val userId = p.userId

        val ssaid = rootService.getPackageSsaidAsUser(packageName = packageName, uid = uid, userId = userId)
        log { "Ssaid: $ssaid" }
        p.extraInfo.ssaid = ssaid
    }

    suspend fun upload(
        client: CloudClient,
        p: PackageEntity,
        t: TaskDetailPackageEntity,
        dataType: DataType,
        srcDir: String,
        dstDir: String,
        isCanceled: (() -> Boolean)? = null
    ) = run {
        val ct = CompressionType.TAR
        val src = packageRepository.getArchiveDst(dstDir = srcDir, dataType = dataType, ct = ct)
        t.updateInfo(dataType = dataType, state = OperationState.UPLOADING)

        var flag = true
        var progress = 0f
        var speed = 0L
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()

        with(CoroutineScope(coroutineContext)) {
            launch {
                while (flag) {
                    if (isCanceled?.invoke() == true) {
                        log { "Upload progress monitoring canceled" }
                        flag = false
                        break
                    }

                    val speedText = if (speed > 0) speed.formatToStorageSizePerSecond() else ""
                    val content = if (speedText.isNotEmpty()) {
                        "$speedText | ${(progress * 100).toInt()}%"
                    } else {
                        "${(progress * 100).toInt()}%"
                    }
                    t.updateInfo(dataType = dataType, content = content)
                    delay(500)
                }
            }
        }

        cloudRepository.upload(
            client = client,
            src = src,
            dstDir = dstDir,
            onUploading = { read, total ->
                progress = read.toFloat() / total
                val currentTime = System.currentTimeMillis()
                val timeDiff = currentTime - lastTime
                if (timeDiff >= 500) {
                    val bytesDiff = read - lastBytes
                    speed = if (timeDiff > 0) (bytesDiff * 1000 / timeDiff) else 0L
                    lastTime = currentTime
                    lastBytes = read
                }
            },
            isCanceled = isCanceled
        ).apply {
            flag = false
            t.updateInfo(
                dataType = dataType,
                state = if (isSuccess) OperationState.DONE else OperationState.ERROR,
                log = t.getLog(dataType) + "\n${outString}",
                content = "100%"
            )
        }
    }
}