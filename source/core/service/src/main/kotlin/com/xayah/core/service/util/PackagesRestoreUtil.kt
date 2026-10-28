package com.xayah.core.service.util

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.Context
import android.content.pm.PackageManager
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.util.srcDir
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readCleanRestoring
import com.xayah.core.datastore.readSelectionType
import com.xayah.core.model.DataType
import com.xayah.core.model.OperationState
import com.xayah.core.model.SelectionType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.model.CompressionType
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.command.Appops
import com.xayah.core.util.command.Pm
import com.xayah.core.util.command.SELinux
import com.xayah.core.util.command.Tar
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class PackagesRestoreUtil @Inject constructor(
    @ApplicationContext val context: Context,
    private val rootService: RemoteRootService,
    private val taskDao: TaskDao,
    private val packageRepository: PackageRepository,
    private val cloudRepository: CloudRepository,
    private val pathUtil: PathUtil,
) {
    companion object {
        private const val TAG = "PackagesRestoreUtil"

        // android.content.pm.PackageManager 的 FLAG_PERMISSION_* 属于 hidden 常量，
        // 这里按 AOSP 取值内联，避免依赖 hidden api 常量引用。
        private const val FLAG_PERMISSION_USER_SET = 0x0002
        private const val FLAG_PERMISSION_USER_FIXED = 0x0004
    }

    private fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { TAG to msg }
        msg
    }

    private suspend fun PackageEntity.getDataSelected(dataType: DataType) = when (context.readSelectionType().first()) {
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

        SelectionType.APK -> {
            dataType == DataType.PACKAGE_APK
        }

        SelectionType.DATA -> {
            dataType != DataType.PACKAGE_APK
        }

        SelectionType.BOTH -> {
            true
        }
    }

    private suspend fun TaskDetailPackageEntity.updateInfo(
        dataType: DataType,
        state: OperationState? = null,
        bytes: Long? = null,
        log: String? = null,
        content: String? = null,
    ) = run {
        when (dataType) {
            DataType.PACKAGE_APK -> {
                apkInfo.also {
                    if (state != null) it.state = state
                    if (bytes != null) it.bytes = bytes
                    if (log != null) it.log = log
                    if (content != null) it.content = content
                }
            }

            DataType.PACKAGE_USER -> {
                userInfo.also {
                    if (state != null) it.state = state
                    if (bytes != null) it.bytes = bytes
                    if (log != null) it.log = log
                    if (content != null) it.content = content
                }
            }

            DataType.PACKAGE_USER_DE -> {
                userDeInfo.also {
                    if (state != null) it.state = state
                    if (bytes != null) it.bytes = bytes
                    if (log != null) it.log = log
                    if (content != null) it.content = content
                }
            }

            DataType.PACKAGE_DATA -> {
                dataInfo.also {
                    if (state != null) it.state = state
                    if (bytes != null) it.bytes = bytes
                    if (log != null) it.log = log
                    if (content != null) it.content = content
                }
            }

            DataType.PACKAGE_OBB -> {
                obbInfo.also {
                    if (state != null) it.state = state
                    if (bytes != null) it.bytes = bytes
                    if (log != null) it.log = log
                    if (content != null) it.content = content
                }
            }

            DataType.PACKAGE_MEDIA -> {
                mediaInfo.also {
                    if (state != null) it.state = state
                    if (bytes != null) it.bytes = bytes
                    if (log != null) it.log = log
                    if (content != null) it.content = content
                }
            }

            else -> {}
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

    /**
     * 解析 app 数据的真实目录：
     * - 名义目录 "$parentDir/$packageName" 存在（普通设备 / user_0）→ 直接返回，行为完全不变。
     * - 名义目录不存在（OPlus/ColorOS 等随机化目录设备）→ 枚举 parentDir 下子目录，
     *   按属主 uid 反查出真实数据目录并返回。
     * - 反查未命中 → 返回名义 dst 兜底，维持原有 "Not exist" 诊断分支。
     */
    private suspend fun resolveRealDataDir(parentDir: String, packageName: String, uid: Int): String {
        val nominalDst = "$parentDir/$packageName"

        // 普通设备 / user_0：名义目录存在，直接用，不改变原有行为
        if (rootService.exists(nominalDst)) {
            log { "[DataDirProbe] resolveRealDataDir: 名义目录存在 dst=$nominalDst（直接使用，不反查）" }
            return nominalDst
        }

        // uid 无效时无法反查，直接兜底
        if (uid == -1) {
            log { "[DataDirProbe] resolveRealDataDir: uid 无效(-1)，回退名义 dst=$nominalDst" }
            return nominalDst
        }

        // 随机化设备：名义目录不存在，按属主 uid 在 parentDir 下反查真实目录
        log { "[DataDirProbe] resolveRealDataDir: 名义目录不存在，按 uid=$uid 反查 parentDir=$parentDir" }
        val targetUid = uid.toUInt()
        val children = rootService.listFilePaths(parentDir, false, true) // listFiles=false, listDirs=true
        for (childPath in children) {
            val (childUid, _) = rootService.getUidGid(childPath)
            if (childUid == targetUid) {
                log { "[DataDirProbe] resolveRealDataDir: 反查命中真实目录=$childPath (uid=$uid)，替换名义 dst=$nominalDst" }
                return childPath
            }
        }

        log { "[DataDirProbe] resolveRealDataDir: 反查未命中(uid=$uid)，回退名义 dst=$nominalDst（兜底，维持原 Not exist 诊断）" }
        return nominalDst
    }

    suspend fun restoreApk(userId: Int, p: PackageEntity, t: TaskDetailPackageEntity, srcDir: String): ShellResult = run {
        log { "Restoring apk..." }

        val dataType = DataType.PACKAGE_APK
        val packageName = p.packageName
        val ct = CompressionType.TAR
        val src = packageRepository.getArchiveDst(dstDir = srcDir, dataType = dataType, ct = ct)
        var isSuccess = true
        val out = mutableListOf<String>()

        if (p.getDataSelected(dataType).not()) {
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
        } else {
            // Return if the archive doesn't exist.
            if (rootService.exists(src)) {
                val sizeBytes = rootService.calculateSize(src)
                t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = sizeBytes)
                // Decompress apk archive
                val tmpApkPath = pathUtil.getTmpApkPath(packageName = packageName)
                rootService.deleteRecursively(tmpApkPath)
                rootService.mkdirs(tmpApkPath)
                Tar.decompress(
                    cacheDir = context.cacheDir.path,
                    callTar = { stdOut, stdErr, argv -> rootService.callTarCli(stdOut, stdErr, argv) },
                    src = src,
                    dst = tmpApkPath,
                ).also { result ->
                    isSuccess = result.isSuccess
                    out.addAll(result.out)
                }

                // Install apks
                rootService.listFilePaths(tmpApkPath).also { apksPath ->
                    when (apksPath.size) {
                        0 -> {
                            isSuccess = false
                            out.add(log { "$tmpApkPath is empty." })
                        }

                        1 -> {
                            Pm.install(userId = userId, src = apksPath.first()).also { result ->
                                isSuccess = isSuccess && result.isSuccess
                                out.addAll(result.out)
                            }
                        }

                        else -> {
                            var pmSession = ""
                            Pm.Install.create(userId = userId).also { result ->
                                if (result.isSuccess) pmSession = result.outString
                            }
                            if (pmSession.isNotEmpty()) {
                                out.add(log { "Install session: $pmSession." })

                            } else {
                                isSuccess = false
                                out.add(log { "Failed to get install session." })
                            }

                            apksPath.forEach { apkPath ->
                                Pm.Install.write(session = pmSession, srcName = PathUtil.getFileName(apkPath), src = apkPath).also { result ->
                                    isSuccess = isSuccess && result.isSuccess
                                    out.addAll(result.out)
                                }
                            }

                            Pm.Install.commit(pmSession).also { result ->
                                isSuccess = isSuccess && result.isSuccess
                                out.addAll(result.out)
                            }
                        }
                    }
                }
                rootService.deleteRecursively(tmpApkPath)

                // Check the installation again.
                rootService.queryInstalled(packageName = packageName, userId = userId).also {
                    if (it.not()) {
                        isSuccess = false
                        log { "Not installed: $packageName." }
                    }
                }
            } else {
                isSuccess = false
                out.add(log { "Not exist: $src" })
            }
            t.updateInfo(dataType = dataType, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    /**
     * Package data: USER, USER_DE, DATA, OBB, MEDIA
     */
    suspend fun restoreData(userId: Int, p: PackageEntity, t: TaskDetailPackageEntity, dataType: DataType, srcDir: String): ShellResult = run {
        log { "Restoring ${dataType.type}..." }

        val packageName = p.packageName
        val ct = CompressionType.TAR
        val src = packageRepository.getArchiveDst(dstDir = srcDir, dataType = dataType, ct = ct)
        val dstDir = packageRepository.getDataSrcDir(dataType, userId)
        val uid = rootService.getPackageUid(packageName = packageName, userId = userId)
        // 名义目录存在→用名义路径（普通设备/user_0 行为不变）；
        // 不存在（随机化设备）→按属主 uid 反查真实数据目录；都没有→兜底名义路径
        val dst = resolveRealDataDir(parentDir = dstDir, packageName = packageName, uid = uid)
        // 名义目录 = "$dstDir/$packageName"；dst 与之不同即说明走了 uid 反查（随机化设备）
        val nominalDst = packageRepository.getDataSrc(dstDir, packageName)
        val isRandomized = dst != nominalDst
        // 【诊断】对比名义路径与 PackageManager 返回的真实数据目录（匿名化目录设备上二者不同）
        run {
            val realDirs = rootService.getPackageDataDirs(packageName = packageName, userId = userId)
            val realCe = realDirs.getOrNull(0) ?: ""
            val realDe = realDirs.getOrNull(1) ?: ""
            log { "[DataDirProbe] pkg=$packageName userId=$userId dataType=${dataType.type}" }
            log { "[DataDirProbe] 名义 dstDir=$dstDir 名义 dst=$dst" }
            log { "[DataDirProbe] 真实 CE(dataDir)=$realCe 真实 DE(deviceProtectedDataDir)=$realDe" }
            log { "[DataDirProbe] CE 与名义一致? ${realCe == dst} ; DE 与名义一致? ${realDe == dst}" }
        }
        var isSuccess = true
        val out = mutableListOf<String>()

        if (p.getDataSelected(dataType).not()) {
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
        } else {
            if (uid == -1) {
                isSuccess = false
                out.add(log { "Failed to get uid of $packageName." })
            } else {
                // Return if the archive doesn't exist.
                if (rootService.exists(src)) {
                    val sizeBytes = rootService.calculateSize(src)
                    t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = sizeBytes)
                    // Generate exclusion items.
                    val exclusionList = mutableListOf<String>()
                    when (dataType) {
                        DataType.PACKAGE_USER, DataType.PACKAGE_USER_DE, DataType.PACKAGE_DATA, DataType.PACKAGE_OBB, DataType.PACKAGE_MEDIA -> {
                            // Exclude cache（JNI tar 不经 shell，排除项不能带字面引号，否则永不匹配；与备份端 PackagesBackupUtil 对齐）
                            val folders = listOf(".ota", "cache", "lib", "code_cache", "no_backup")
                            exclusionList.addAll(folders.map { "$packageName/$it" })
                            if (dataType == DataType.PACKAGE_DATA || dataType == DataType.PACKAGE_OBB || dataType == DataType.PACKAGE_MEDIA) {
                                // Exclude Backup_*
                                exclusionList.add("Backup_*")
                            }

                        }

                        else -> {}
                    }
                    log { "ExclusionList: $exclusionList." }

                    // Get the SELinux context of the path.
                    val pathContext: String
                    SELinux.getContext(path = dst).also { result ->
                        pathContext = if (result.isSuccess) result.outString else ""
                    }

                    log { "Original SELinux context: $pathContext." }

                    // 随机化设备：解到真实目录 dst 并用 --strip-components=1 剥掉归档顶层的 <packageName>/ 前缀；
                    // 普通设备/user_0：保持原逻辑，解到名义父目录 dstDir、不 strip。
                    val extractDst = if (isRandomized) dst else dstDir
                    val stripComponents = if (isRandomized) 1 else 0
                    log { "[DataDirProbe] tar 解压目标=$extractDst stripComponents=$stripComponents isRandomized=$isRandomized" }
                    // Decompress the archive.
                    Tar.decompress(
                        cacheDir = context.cacheDir.path,
                        callTar = { stdOut, stdErr, argv -> rootService.callTarCli(stdOut, stdErr, argv) },
                        exclusionList = exclusionList,
                        clear = if (context.readCleanRestoring().first()) "--recursive-unlink" else "",
                        m = false,   // 还原归档里的原始 mtime（-xpf），减少 A→B 跨设备再备份的 header 级增量
                        src = src,
                        dst = extractDst,
                        stripComponents = stripComponents,
                    ).also { result ->
                        isSuccess = result.isSuccess
                        out.addAll(result.out)
                        // 【诊断】解压失败时把 tar stderr 逐行打到 logcat，便于 adb logcat 直接看到 rc!=0 的原文
                        if (!result.isSuccess) {
                            log { "[DataDirProbe] tar 解压失败 isRandomized=$isRandomized extractDst=$extractDst stripComponents=$stripComponents" }
                            result.out.forEach { line -> log { "[DataDirProbe] tar stderr: $line" } }
                        }
                    }

                    // Restore SELinux context.
                    var gid: UInt = uid.toUInt()
                    if (dataType == DataType.PACKAGE_DATA || dataType == DataType.PACKAGE_OBB || dataType == DataType.PACKAGE_MEDIA) {
                        val (_, pathGid) = rootService.getUidGid(dataType.srcDir(userId))
                        gid = pathGid
                    }
                    SELinux.chown(uid = uid.toUInt(), gid = gid, path = dst).also { result ->
                        isSuccess = isSuccess && result.isSuccess
                        out.addAll(result.out)
                    }
                    if (pathContext.isNotEmpty()) {
                        SELinux.chcon(context = pathContext, path = dst).also { result ->
                            isSuccess = isSuccess && result.isSuccess
                            out.addAll(result.out)
                        }
                    } else {
                        val parentContext: String
                        SELinux.getContext(dstDir).also { result ->
                            parentContext = if (result.isSuccess) result.outString.replace("system_data_file", "app_data_file") else ""
                        }
                        if (parentContext.isNotEmpty()) {
                            SELinux.chcon(context = parentContext, path = dst).also { result ->
                                isSuccess = isSuccess && result.isSuccess
                                out.addAll(result.out)
                            }
                        } else {
                            isSuccess = false
                            out.add(log { "Failed to restore context: $dst" })
                        }
                    }

                } else {
                    log { "=== 数据恢复路径检测失败 ===" }
                    log { "应用包名: $packageName" }
                    log { "数据类型: ${dataType.type}" }
                    log { "传入srcDir: $srcDir" }
                    log { "构建路径: $src" }
                    val fileExists = rootService.exists(src)  // 先获取结果
                    log { "文件存在: $fileExists" }          // 然后在日志中使用
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
            t.updateInfo(dataType = dataType, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    suspend fun restorePermissions(userId: Int, p: PackageEntity) = run {
        log { "Restoring permissions..." }

        val packageName = p.packageName
        val uid = rootService.getPackageUid(packageName = packageName, userId = userId)
        val user = rootService.getUserHandle(userId)
        val permissions = p.extraInfo.permissions

        if (p.permissionSelected) {
            if (uid != -1 && user != null) {
                Appops.reset(userId = userId, packageName = packageName)
                var succeeded = 0
                var failed = 0
                log { "Permissions size: ${permissions.size}..." }
                permissions.forEach { perm ->
                    log { "Permission name: ${perm.name}, isGranted: ${perm.isGranted}, op: ${perm.op}, mode: ${perm.mode}" }
                    var ok = true

                    // (1) 先清 USER_SET / USER_FIXED，带这些 flag 时系统会拒绝本次 grant/revoke。
                    rootService.updatePermissionFlags(
                        packageName, perm.name, user,
                        FLAG_PERMISSION_USER_SET or FLAG_PERMISSION_USER_FIXED, 0,
                    )

                    // (2) runtime 权限 grant / revoke
                    if (perm.isGranted) {
                        rootService.grantRuntimePermission(packageName, perm.name, user)
                    } else {
                        rootService.revokeRuntimePermission(packageName, perm.name, user)
                    }.onFailure {
                        ok = false
                        log { "Runtime permission failed: ${perm.name}, ${it.message}" }
                    }

                    // (3) 补回 USER_SET，标记该权限由用户显式决定，避免被系统自动重置
                    rootService.updatePermissionFlags(
                        packageName, perm.name, user,
                        FLAG_PERMISSION_USER_SET, FLAG_PERMISSION_USER_SET,
                    )

                    // (4) appops 与 runtime 状态对齐：
                    //     - 未授予 -> 强制 IGNORED，避免"runtime 拒绝但 appops 放行"
                    //     - 已授予但备份值是 IGNORED -> 自相矛盾（老版本把采样不到的 op 全记成 IGNORED），
                    //       按 DEFAULT 处理，恢复旧备份时不再把授权掐掉
                    //     - 已授予且备份值是 DEFAULT -> 不回写，交给系统推导
                    //     - 已授予且备份值是具体 mode -> 原样回写
                    if (perm.op != AppOpsManagerHidden.OP_NONE) {
                        val targetMode = when {
                            perm.isGranted.not() -> AppOpsManager.MODE_IGNORED
                            perm.mode == AppOpsManager.MODE_IGNORED -> null
                            perm.mode == AppOpsManager.MODE_DEFAULT -> null
                            else -> perm.mode
                        }
                        if (targetMode != null) {
                            rootService.setOpsMode(perm.op, uid, packageName, targetMode).onFailure {
                                ok = false
                                log { "setOpsMode failed: ${perm.name}, op=${perm.op}, mode=$targetMode, ${it.message}" }
                            }
                        }
                    }

                    if (ok) succeeded++ else failed++
                }
                log { "Permissions restored: $succeeded, failed: $failed" }

                // (5) 兜底：备份快照之外、当前仍被授予的运行时权限一律撤销。
                //     覆盖应用更新新增权限、split 权限自动授予、appops reset 后系统重新授予等情况。
                val recordedNames = permissions.map { it.name }.toSet()
                val currentPackageInfo = rootService.getPackageInfoAsUser(packageName, PackageManager.GET_PERMISSIONS, userId)
                val currentPermissions = if (currentPackageInfo != null) rootService.getPermissions(packageInfo = currentPackageInfo) else listOf()
                currentPermissions.filter { it.name !in recordedNames && it.isGranted }.forEach { extra ->
                    log { "Extra granted permission not in backup, revoking: ${extra.name}" }
                    runCatching {
                        rootService.updatePermissionFlags(
                            packageName, extra.name, user,
                            FLAG_PERMISSION_USER_SET or FLAG_PERMISSION_USER_FIXED, 0,
                        )
                        rootService.revokeRuntimePermission(packageName, extra.name, user).onFailure {
                            log { "Revoke extra permission failed: ${extra.name}, ${it.message}" }
                        }
                        if (extra.op != AppOpsManagerHidden.OP_NONE) {
                            rootService.setOpsMode(extra.op, uid, packageName, AppOpsManager.MODE_IGNORED)
                        }
                    }
                }
            } else {
                log { "Failed to get uid or user handle of $packageName." }
            }
        } else {
            log { "Skip." }
        }
    }

    suspend fun restoreSsaid(userId: Int, p: PackageEntity) = run {
        log { "Restoring ssaid..." }

        val packageName = p.packageName
        val uid = rootService.getPackageUid(packageName = packageName, userId = userId)
        val ssaid = p.extraInfo.ssaid

        if (p.ssaidSelected) {
            if (uid != -1) {
                if (ssaid.isNotEmpty()) {
                    log { "Ssaid: $ssaid" }
                    rootService.setPackageSsaidAsUser(packageName, uid, userId, ssaid)
                } else {
                    log { "Ssaid is empty, skip." }
                }
            } else {
                log { "Failed to get uid of $packageName." }
            }
        } else {
            log { "Skip." }
        }
    }

    suspend fun download(
        client: CloudClient,
        p: PackageEntity,
        t: TaskDetailPackageEntity,
        dataType: DataType,
        srcDir: String,
        dstDir: String,
        onDownloaded: suspend (p: PackageEntity, t: TaskDetailPackageEntity, dataType: DataType, path: String) -> Unit
    ) = run {
        val ct = CompressionType.TAR
        val src = packageRepository.getArchiveDst(dstDir = srcDir, dataType = dataType, ct = ct)

        if (p.getDataSelected(dataType).not()) {
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
        } else {
            t.updateInfo(dataType = dataType, state = OperationState.DOWNLOADING)

            if (client.exists(src)) {
                var flag = true
                var progress = 0.0
                var total = 0.0
                var lastWritten = 0L
                var lastTime = System.currentTimeMillis()
                val startTime = System.currentTimeMillis()  // 记录开始时间用于计算平均速度

                with(CoroutineScope(coroutineContext)) {
                    launch {
                        while (flag) {
                            delay(300)
                            val currentTime = System.currentTimeMillis()
                            val timeDiff = currentTime - lastTime
                            val bytesDiff = progress.toLong() - lastWritten
                            val speed = if (timeDiff > 0) (bytesDiff * 1000 / timeDiff) else 0L
                            val speedText = speed.toDouble().formatSize()
                            val percentage = if (total > 0) (progress / total * 100).toInt() else 0

                            // 只在速度大于0或者是第一次更新时才显示
                            if (speed > 0 || lastWritten == 0L) {
                                t.updateInfo(dataType = dataType, content = "$speedText/s | $percentage%")
                            }

                            lastWritten = progress.toLong()
                            lastTime = currentTime
                        }
                    }
                }

                cloudRepository.download(
                    client = client,
                    src = src,
                    dstDir = dstDir,
                    onDownloading = { written, totalBytes ->
                        progress = written.toDouble()
                        total = totalBytes.toDouble()
                    },
                    onDownloaded = {
                        onDownloaded(p, t, dataType, dstDir)
                    }
                ).apply {
                    // 计算整个下载过程的平均速度
                    val totalTime = System.currentTimeMillis() - startTime
                    val avgSpeed = if (totalTime > 0) (progress.toLong() * 1000 / totalTime) else 0L
                    val avgSpeedText = avgSpeed.toDouble().formatSize()

                    t.updateInfo(
                        dataType = dataType,
                        log = (t.getLog(dataType) + "\n${outString}").trim(),
                        content = "$avgSpeedText/s | 100%"
                    )

                    // 然后停止协程
                    flag = false

                    if (isSuccess.not()) {
                        t.updateInfo(dataType = dataType, state = OperationState.ERROR)
                    }
                }
            } else {
                if (dataType == DataType.PACKAGE_USER || dataType == DataType.PACKAGE_APK) {
                    t.updateInfo(dataType = dataType, state = OperationState.ERROR, log = log { "Failed to connect to cloud or file not exist: $src" })
                } else {
                    t.updateInfo(dataType = dataType, state = OperationState.SKIP, log = log { "Failed to connect to cloud or file not exist, skip: $src" })
                }
            }
        }
    }
}
