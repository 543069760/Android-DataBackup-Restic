package com.xayah.core.rootservice.impl

import android.util.Log
import android.annotation.TargetApi
import android.app.ActivityManagerHidden
import android.app.ActivityThread
import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.IPackageManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.content.pm.PackageManagerHidden
import android.content.pm.PermissionInfo
import android.content.pm.UserInfo
import android.os.Build
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.os.StatFs
import android.os.UserHandle
import android.os.UserHandleHidden
import android.os.UserManagerHidden
import android.view.Display
import android.view.KeyEvent
import android.view.SurfaceControlHidden
import androidx.core.content.pm.PermissionInfoCompat
import com.android.server.display.DisplayControl
import com.topjohnwu.superuser.ShellUtils
import com.xayah.core.datastore.ConstantUtil.DEFAULT_IDLE_TIMEOUT
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.database.PackagePermission
import com.xayah.core.rootservice.IRemoteRootService
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.rootservice.parcelables.StatFsParcelable
import com.xayah.core.rootservice.parcelables.StorageStatsParcelable
import com.xayah.core.rootservice.util.ExceptionUtil.tryOn
import com.xayah.core.rootservice.util.ExceptionUtil.tryWithBoolean
import com.xayah.core.rootservice.util.SsaidUtil
import com.xayah.core.rootservice.ICallback
import com.xayah.core.util.CpuCoreSampler
import com.xayah.core.util.FileUtil
import com.xayah.core.util.HashUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.BaseUtil.setAllPermissions
import com.xayah.libnative.NativeLib
import com.xayah.libnative.Rustic
import com.xayah.libnative.TarWrapper
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.pathString
import org.rclone.gomobile.Gomobile

internal class RemoteRootServiceImpl(private val context: Context) : IRemoteRootService.Stub() {
    private val lock = Any()
    // calculateSize 只做只读遍历，既可能与备份/恢复并发，又可能在超大或符号链接密集的目录上耗时较久。
    // 它与 hidden API 调用、rclone RPC 之间没有任何共享状态需要互斥，故不占用公共 lock ——
    // 长耗时只读遍历持公共锁会把服务里其它 Binder 调用一并拖死（参见下文 rcloneRpcNoLock 的注释）。
    // 这里用一把独立锁，仅用于避免多个 calculateSize 同时跑、把 CPU 与 fd 吃满。
    private val calculateSizeLock = Any()
    // settings 命令共用同一个 root shell（ShellUtils.fastCmd），只能彼此串行；
    // 但它们不应该排队等备份/恢复释放公共 lock，故用独立锁。
    private val settingsLock = Any()
    private val fdBackupCancelWriters = java.util.concurrent.ConcurrentHashMap<Long, ParcelFileDescriptor>()
    private var systemContext: Context
    private var packageManager: PackageManager
    private var packageManagerHidden: PackageManagerHidden
    private var packageManagerService: IPackageManager
    private var userManager: UserManagerHidden
    private var activityManager: ActivityManagerHidden
    private var appOpsManager: AppOpsManagerHidden

    private fun getSystemContext(): Context = ActivityThread.systemMain().systemContext

    @TargetApi(Build.VERSION_CODES.O)
    private fun getStorageStatsManager(): StorageStatsManager = systemContext.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager

    private fun getUserManager(): UserManagerHidden = UserManagerHidden.get(systemContext).castTo()

    private fun getActivityManager(): ActivityManagerHidden = systemContext.getSystemService(Context.ACTIVITY_SERVICE).castTo()

    private fun getAppOpsManager(): AppOpsManagerHidden = systemContext.getSystemService(Context.APP_OPS_SERVICE).castTo()

    init {
        systemContext = getSystemContext()
        packageManager = systemContext.packageManager
        packageManagerHidden = packageManager.castTo()
        packageManagerService = IPackageManager.Stub.asInterface(ServiceManager.getService("package"))
        userManager = getUserManager()
        activityManager = getActivityManager()
        appOpsManager = getAppOpsManager()
    }

    override fun readStatFs(path: String): StatFsParcelable = synchronized(lock) {
        val statFs = StatFs(path)
        StatFsParcelable(statFs.availableBytes, statFs.totalBytes)
    }

    override fun mkdirs(path: String): Boolean = synchronized(lock) {
        tryOn(
            block = {
                val file = File(path)
                if (file.exists().not()) file.mkdirs() else true
            },
            onException = { false }
        )
    }

    override fun copyRecursively(path: String, targetPath: String, overwrite: Boolean): Boolean = synchronized(lock) {
        tryOn(block = { File(path).copyRecursively(target = File(targetPath), overwrite = overwrite) }, onException = { false })
    }

    override fun copyTo(path: String, targetPath: String, overwrite: Boolean): Boolean = synchronized(lock) {
        tryWithBoolean {
            File(path).copyTo(target = File(targetPath), overwrite = overwrite)
        }
    }

    override fun renameTo(src: String, dst: String): Boolean = synchronized(lock) {
        runCatching { File(src).renameTo(File(dst)) }.getOrElse { false }
    }

    override fun exists(path: String): Boolean = synchronized(lock) {
        tryOn(block = { File(path).exists() }, onException = { false })
            .also { if (!it) Log.w("RemoteRootService", "impl.exists($path)=false | ${statFailureReason(path)}") }
    }

    /**
     * `File.exists()` 把 ENOENT 和 EACCES 都折叠成 false，调用方无从判断是「路径真不存在」
     * 还是「root 侧看不到/没权限」。这里用 Os.stat 取回真实 errno：
     * - errno=2(ENOENT)：路径在该进程的挂载视图里确实不存在（mount namespace 问题）
     * - errno=13(EACCES)：路径在，但 SELinux/DAC 拒绝（权限问题）
     * 只在 exists 判否时打，正常路径零开销。
     */
    private fun statFailureReason(path: String): String {
        val ns = runCatching { android.system.Os.readlink("/proc/self/ns/mnt") }.getOrNull().orEmpty()
        val initNs = runCatching { android.system.Os.readlink("/proc/1/ns/mnt") }.getOrNull().orEmpty()
        val label = runCatching { android.system.Os.readlink("/proc/self/attr/current") }.getOrNull().orEmpty()
        // 逐级打印同一进程视角下的关键挂载点，用来区分「整体不可见」还是「只有 /data/user 缺子项」。
        val views = listOf("/data", "/data/user", "/data/user/0", "/data/data", "/data/user_de", "/storage/emulated/0", path)
            .joinToString(", ") { "$it=${statState(it)}" }
        // 目录项隔离的直接证据：/data/user/0 下当前还能看到哪些包（全量 = 未隔离，仅本应用 = 被隔离）。
        val userZero = runCatching { java.io.File("/data/user/0").list()?.take(3)?.joinToString("/") }.getOrNull()
        // 旁路候选 1：init 的根视图（跨 namespace 解析，需 ptrace 权限）。
        val initRoot = statState("/proc/1/root$path")
        // 旁路候选 2：su --mount-master 在 init ns 里执行（若 /proc/1/root 被 SELinux 拒，只能走这条）。
        return "ns=$ns initNs=$initNs same=${ns == initNs} label=$label | $views | user0=[$userZero] | /proc/1/root$path=$initRoot"
    }

    private fun statState(path: String): String = try {
        android.system.Os.stat(path)
        "ok"
    } catch (e: android.system.ErrnoException) {
        "errno=${e.errno}"
    } catch (e: Exception) {
        e.javaClass.simpleName
    }

    override fun createNewFile(path: String): Boolean = synchronized(lock) {
        tryOn(block = { File(path).createNewFile() }, onException = { false })
    }

    override fun deleteRecursively(path: String): Boolean = synchronized(lock) {
        tryOn(block = { File(path).deleteRecursively() }, onException = { false })
    }

    override fun listFilePaths(path: String, listFiles: Boolean, listDirs: Boolean): List<String> = synchronized(lock) {
        FileUtil.listFilePaths(path = path, listFiles = listFiles, listDirs = listDirs)
    }

    private fun writeToParcel(block: (Parcel) -> Unit): ParcelFileDescriptor {
        val parcel = Parcel.obtain()
        parcel.setDataPosition(0)
        block(parcel)
        val tmpFile = File.createTempFile("databackup-parcel-", ".tmp", context.cacheDir)
        tmpFile.delete()
        tmpFile.createNewFile()
        tmpFile.writeBytes(parcel.marshall())
        val pfd = ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_WRITE)
        tmpFile.delete()
        parcel.recycle()
        return pfd
    }

    override fun readText(path: String): ParcelFileDescriptor = synchronized(lock) {
        writeToParcel { parcel ->
            parcel.writeString(FileUtil.readText(path))
        }
    }

    override fun readBytes(path: String): ParcelFileDescriptor =
        synchronized(lock) {
            writeToParcel { parcel ->
                val bytes = tryOn(
                    block = {
                        File(path).readBytes()
                    },
                    onException = {
                        ByteArray(0)
                    }
                )
                parcel.writeInt(bytes.size)
                parcel.writeByteArray(bytes)
            }
        }

    override fun calculateSize(path: String): Long = synchronized(calculateSizeLock) {
        NativeLib.calculateSize(path)
    }

    override fun clearEmptyDirectoriesRecursively(path: String) = synchronized(lock) {
        tryOn {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                clearEmptyDirectoriesRecursivelyApi26(path)
            } else {
                clearEmptyDirectoriesRecursivelyApi24(path)
            }
        }
    }

    private fun clearEmptyDirectoriesRecursivelyApi24(path: String) {
        val dir = File(path)
        if (dir.isDirectory) {
            dir.listFiles()?.also { items ->
                if (items.isEmpty()) {
                    dir.delete()
                } else {
                    items.forEach {
                        clearEmptyDirectoriesRecursivelyApi24(it.absolutePath)
                    }
                }
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun clearEmptyDirectoriesRecursivelyApi26(path: String) {
        Files.walkFileTree(Paths.get(path), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path?, attrs: BasicFileAttributes?): FileVisitResult {
                if (dir != null && attrs != null) {
                    if (Files.isDirectory(dir) && Files.list(dir).count() == 0L) {
                        // Empty dir
                        Files.delete(dir)
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path?, attrs: BasicFileAttributes?): FileVisitResult {
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path?, exc: IOException?): FileVisitResult {
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path?, exc: IOException?): FileVisitResult {
                return FileVisitResult.CONTINUE
            }
        })
    }

    override fun setAllPermissions(src: String): Unit = synchronized(lock) { File(src).setAllPermissions() }

    override fun getUidGid(path: String): IntArray = synchronized(lock) {
        NativeLib.getUidGid(path)
    }

    /**
     * AIDL limits transaction to 1M which means it may throw [android.os.TransactionTooLargeException]
     * when the package list is too large. So we just make it parcelable and write into tmp file to avoid that.
     */
    override fun getInstalledPackagesAsUser(flags: Int, userId: Int): ParcelFileDescriptor = synchronized(lock) {
        writeToParcel { parcel ->
            val packages = packageManagerHidden.getInstalledPackagesAsUser(flags, userId)
            parcel.writeTypedList(packages)
        }
    }

    override fun getPackageInfoAsUser(packageName: String, flags: Int, userId: Int): PackageInfo =
        synchronized(lock) { packageManagerHidden.getPackageInfoAsUser(packageName, flags, userId) }

    override fun grantRuntimePermission(packageName: String, permName: String, user: UserHandle) {
        synchronized(lock) {
            packageManagerHidden.grantRuntimePermission(packageName, permName, user)
        }
    }

    override fun revokeRuntimePermission(packageName: String, permName: String, user: UserHandle) {
        synchronized(lock) {
            packageManagerHidden.revokeRuntimePermission(packageName, permName, user)
        }
    }

    override fun getPermissionFlags(packageName: String, permName: String, user: UserHandle) =
        synchronized(lock) { packageManagerHidden.getPermissionFlags(permName, packageName, user) }

    override fun updatePermissionFlags(packageName: String, permName: String, user: UserHandle, flagMask: Int, flagValues: Int) {
        synchronized(lock) {
            packageManagerHidden.updatePermissionFlags(permName, packageName, flagMask, flagValues, user)
        }
    }

    override fun getPackageSourceDir(packageName: String, userId: Int): List<String> = synchronized(lock) {
        runCatching {
            val sourceDirList = mutableListOf<String>()
            val packageInfo = packageManagerHidden.getPackageInfoAsUser(packageName, 0, userId)
            sourceDirList.add(packageInfo.applicationInfo!!.sourceDir)
            val splitSourceDirs = packageInfo.applicationInfo!!.splitSourceDirs
            if (!splitSourceDirs.isNullOrEmpty()) for (i in splitSourceDirs) sourceDirList.add(i)
            sourceDirList
        }.getOrElse { listOf() }
    }

    override fun queryInstalled(packageName: String, userId: Int): Boolean = synchronized(lock) {
        tryWithBoolean {
            packageManagerHidden.getPackageInfoAsUser(packageName, 0, userId)
        }
    }

    override fun getPackageUid(packageName: String, userId: Int): Int = synchronized(lock) {
        runCatching {
            packageManagerHidden.getPackageInfoAsUser(packageName, 0, userId).applicationInfo!!.uid
        }.getOrElse { -1 }
    }

    override fun getPackageDataDirs(packageName: String, userId: Int): List<String> = synchronized(lock) {
        runCatching {
            val appInfo = packageManagerHidden.getPackageInfoAsUser(packageName, 0, userId).applicationInfo!!
            // index 0 = CE 真实数据目录(dataDir)，index 1 = DE 真实数据目录(deviceProtectedDataDir)
            listOf(appInfo.dataDir ?: "", appInfo.deviceProtectedDataDir ?: "")
        }.getOrElse { listOf("", "") }
    }

    override fun isUserUnlocked(userId: Int): Boolean = synchronized(lock) {
        runCatching {
            userManager.isUserUnlocked(userId)
        }.getOrElse { false }
    }

    override fun getUserHandle(userId: Int): UserHandle = synchronized(lock) {
        UserHandleHidden.of(userId)
    }

    private fun queryStatsForPackageApi24(packageInfo: PackageInfo, user: UserHandle): StorageStatsParcelable {
        val userHandle: UserHandleHidden = user.castTo()
        // https://cs.android.com/android/platform/superproject/+/android-8.0.0_r51:frameworks/base/services/core/java/com/android/server/pm/Installer.java;l=225
        // https://cs.android.com/android/platform/superproject/+/android-8.0.0_r51:frameworks/native/cmds/installd/InstalldNativeService.cpp;l=1340
        // https://cs.android.com/android/platform/superproject/+/android-8.0.0_r51:frameworks/base/services/usage/java/com/android/server/usage/StorageStatsService.java;l=439
        // https://cs.android.com/android/platform/superproject/+/android-8.0.0_r51:frameworks/base/core/java/android/app/usage/StorageStats.java;l=31
        val userDir = "${PathUtil.getPackageUserDir(userHandle.identifier)}/${packageInfo.packageName}"
        val userCacheDir = "$userDir/cache"
        val userCodeCacheDir = "$userDir/code_cache"
        val dataDir = "${PathUtil.getPackageDataDir(userHandle.identifier)}/${packageInfo.packageName}"
        val dataCacheDir = "$dataDir/cache"
        val dataCodeCacheDir = "$dataDir/code_cache"
        val obbDir = "${PathUtil.getPackageObbDir(userHandle.identifier)}/${packageInfo.packageName}"
        val obbCacheDir = "$obbDir/cache"
        val obbCodeCacheDir = "$obbDir/code_cache"
        val mediaDir = "${PathUtil.getPackageMediaDir(userHandle.identifier)}/${packageInfo.packageName}"
        val mediaCacheDir = "$mediaDir/cache"
        val mediaCodeCacheDir = "$mediaDir/code_cache"

        val appDirSize = runCatching { FileUtil.calculateSize(PathUtil.getParentPath(packageInfo.applicationInfo!!.sourceDir)) }.getOrElse { 0 }
        val cacheDirSize = FileUtil.calculateSize(userCacheDir) + FileUtil.calculateSize(userCodeCacheDir) +
                FileUtil.calculateSize(dataCacheDir) + FileUtil.calculateSize(dataCodeCacheDir) +
                FileUtil.calculateSize(obbCacheDir) + FileUtil.calculateSize(obbCodeCacheDir) +
                FileUtil.calculateSize(mediaCacheDir) + FileUtil.calculateSize(mediaCodeCacheDir)
        val dataDirSize = FileUtil.calculateSize(userDir) + FileUtil.calculateSize(dataDir) + FileUtil.calculateSize(obbDir) + FileUtil.calculateSize(mediaDir) - cacheDirSize
        return StorageStatsParcelable(
            appDirSize,
            cacheDirSize,
            dataDirSize,
            0,
        )
    }

    @TargetApi(Build.VERSION_CODES.O)
    fun queryStatsForPackageApi26(packageInfo: PackageInfo, user: UserHandle): StorageStatsParcelable {
        val stats = runCatching { getStorageStatsManager().queryStatsForPackage(packageInfo.applicationInfo!!.storageUuid, packageInfo.packageName, user) }.getOrNull()

        return StorageStatsParcelable(
            stats?.appBytes ?: 0,
            stats?.cacheBytes ?: 0,
            stats?.dataBytes ?: 0,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stats?.externalCacheBytes ?: 0 else 0,
        )
    }

    override fun queryStatsForPackage(packageInfo: PackageInfo, user: UserHandle): StorageStatsParcelable? = synchronized(lock) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                queryStatsForPackageApi26(packageInfo, user)
            } else {
                queryStatsForPackageApi24(packageInfo, user)
            }
        }.getOrNull()
    }

    override fun getUsers(): List<UserInfo> = synchronized(lock) {
        tryOn(
            block = {
                userManager.users
            },
            onException = {
                listOf()
            }
        )
    }

    /**
     * 返回 `cmd user list -v` 的原始行，供上层判定用户类型。
     *
     * UserInfo.userType 在 SDK 中是 @hide，编译期取不到；而用户类型恰恰是区分
     * 「应用分身（profile.CLONE）」和「系统分身（full.SECONDARY）」的唯一可靠依据
     * （userId 在 Android 上并非固定值，不能靠 999 判断），所以走命令行拿 type。
     * 解析失败/系统不支持时返回空列表，由上层静默跳过相关提示。
     */
    override fun getUserListVerbose(): List<String> = synchronized(lock) {
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd user list -v"))
            val output = process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            process.waitFor()
            output.lines().filter { it.contains("id=") }
        }.getOrElse { listOf() }
    }

    /**
     * 通过 pm create-user 创建应用分身类型的 profile（图标显示在主用户启动器）。
     * 仅尝试 CLONE 类型——普通 secondary user 的图标不出现在主启动器，
     * MANAGED 会带工作资料角标，都不符合"分身"语义；ROM 不支持时直接返回 -1，
     * 由 UI 层提示用户手动创建分身。
     */
    override fun createUser(name: String): Int = synchronized(lock) {
        runCatching {
            val before = userManager.users.map { it.id }.toSet()
            val safeName = name.replace(Regex("[^A-Za-z0-9_-]"), "")
            val cmd = "pm create-user --profileOf 0 --userType android.os.usertype.profile.CLONE \"$safeName\""

            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            Log.i("RemoteRootService", "$cmd -> output: $output, error: $error")

            Regex("""id\s+(\d+)""").find(output)?.groupValues?.get(1)?.toInt()
                ?: (userManager.users.map { it.id }.toSet() - before).firstOrNull()
                ?: -1
        }.getOrElse { -1 }
    }

    /**
     * 通过 am start-user 后台启动用户（不切前台）。
     * 新建/停止态用户的 CE 存储在启动过程中解锁（无锁屏凭据时），
     * 用于把「未启动」从「FBE 锁定」中区分出来：启动后仍 isUserUnlocked=false 的才是真锁定。
     * 启动是异步的，轮询最多 ~5s 等待解锁生效。
     */
    override fun startUser(userId: Int): Boolean = synchronized(lock) {
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "am start-user $userId"))
            process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            process.waitFor()

            // 轮询等待 CE 解锁；无锁屏的用户通常几百 ms 内完成
            repeat(10) {
                if (isUserUnlocked(userId)) return@synchronized true
                Thread.sleep(500)
            }
            isUserUnlocked(userId)
        }.getOrElse { false }
    }

    /**
     * 通过 pm remove-user 删除用户，用于 createUser 批量创建中途失败时的回滚。
     */
    override fun removeUser(userId: Int): Boolean = synchronized(lock) {
        runCatching {
            val before = userManager.users.map { it.id }.toSet()
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "pm remove-user $userId"))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            Log.i("RemoteRootService", "pm remove-user $userId output: $output, error: $error")

            // 输出含 success 直接判成功；否则用差集确认该 id 已消失
            if (output.contains("success", ignoreCase = true)) return@synchronized true
            userId !in userManager.users.map { it.id }.toSet() && userId in before
        }.getOrElse { false }
    }

    override fun walkFileTree(path: String): ParcelFileDescriptor = synchronized(lock) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            walkFileTreeApi26(path)
        } else {
            walkFileTreeApi24(path)
        }
    }

    private fun walkFileTreeApi24(path: String): ParcelFileDescriptor {
        fun walkFileTreeRecursively(path: String): List<PathParcelable> {
            val pathParcelableList = mutableListOf<PathParcelable>()
            val file = File(path)
            if (file.isFile) {
                pathParcelableList.add(PathParcelable(file.absolutePath))
            } else if (file.isDirectory) {
                for (item in file.listFiles()!!) {
                    pathParcelableList.addAll(walkFileTreeRecursively(item.absolutePath))
                }
            }
            return pathParcelableList
        }
        return writeToParcel { parcel ->
            parcel.writeTypedList(
                tryOn(
                    block = { walkFileTreeRecursively(path) },
                    onException = {
                        listOf()
                    },
                )
            )
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun walkFileTreeApi26(path: String): ParcelFileDescriptor = writeToParcel { parcel ->
        parcel.writeTypedList(
            tryOn(
                block = {
                    val pathParcelableList = mutableListOf<PathParcelable>()
                    Files.walkFileTree(Paths.get(path), object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path?, attrs: BasicFileAttributes?): FileVisitResult {
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path?, attrs: BasicFileAttributes?): FileVisitResult {
                            if (file != null && attrs != null) {
                                pathParcelableList.add(PathParcelable(file.pathString))
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(file: Path?, exc: IOException?): FileVisitResult {
                            return FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(dir: Path?, exc: IOException?): FileVisitResult {
                            return FileVisitResult.CONTINUE
                        }
                    })
                    pathParcelableList
                },
                onException = {
                    listOf()
                }
            )
        )
    }

    override fun getPackageArchiveInfo(path: String): PackageInfo? = synchronized(lock) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            systemContext.packageManager.getPackageArchiveInfo(path, PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))?.apply {
                applicationInfo?.sourceDir = path
                applicationInfo?.publicSourceDir = path
            }
        } else {
            systemContext.packageManager.getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES)?.apply {
                applicationInfo?.sourceDir = path
                applicationInfo?.publicSourceDir = path
            }
        }
    }

    override fun getPackageSsaidAsUser(packageName: String, uid: Int, userId: Int): String? = synchronized(lock) { SsaidUtil(userId).getSsaid(packageName, uid) }
    override fun setPackageSsaidAsUser(packageName: String, uid: Int, userId: Int, ssaid: String) {
        synchronized(lock) { SsaidUtil(userId).setSsaid(packageName, uid, ssaid) }
    }

    /**
     * 息屏/设置相关调用不能再用全局 [lock]：
     * 备份/恢复（[backupTarToRustic]、[createRusticSnapshot] 等）会整段持有 [lock]，
     * 处理中的「自动息屏」请求会被阻塞到备份结束才真正执行，且守护进程一旦在备份期间死亡，
     * 这些「在途」的 binder 调用会抛 DeadObjectException 并被 UI 当成任务出错。
     * 这里改用独立的小锁，只串行「触碰 [DisplayControl]」这一段，不会被备份阻塞。
     *
     * 顺序：先走 [setPowerModeBySleepKey]（真实睡眠，PWS 与面板状态一致，唯一能「保持熄灭」的路径）；
     * 之后按「是否会触发 android_servers 的 native 加载」排列，前三路都不碰 [DisplayControl]，
     * 只有它们全部失败才走最后一路。这一点很关键——息屏是备份/恢复进行中触发的，一旦在这里
     * abort，被切断的是正在流式读写的 tar/native 调用，代价是整个任务失败，而不只是「息屏没生效」。
     */
    override fun setDisplayPowerMode(mode: Int, allowRiskyPath: Boolean): Boolean {
        // 首选：让 PowerManagerService 自己进入 / 退出睡眠，而不是只把面板点灭。
        //
        // 下面几路（SurfaceControl / DisplayControl / requestDisplayPower）改的只是「面板电源状态」，
        // PWS 的期望状态没变（wakefulness 仍是 Awake；调用方为了防任务期间自动休眠还会把
        // screen_off_timeout 设成 MAX）。于是任何一次电源状态重算 —— 写入 settings 触发的
        // DIRTY_SETTINGS、亮度/接近传感器更新、唤醒锁增减 —— 都会把面板重新点亮，而且因为超时是
        // MAX，亮起来之后就再也不熄：这就是「倒计时息屏几秒后又亮，并从此常亮」的完整成因。
        // 走 KEYCODE_SLEEP / KEYCODE_WAKEUP 之后 PWS 与面板状态一致，不会再被拉回；处理服务在任务
        // 期间持有 PARTIAL_WAKE_LOCK，CPU 不挂起，tar / rustic 照常跑完。
        var via: String? = setPowerModeBySleepKey(mode)
        // 前三路都不触发 DisplayControl 的静态初始化（即 loadLibrary0("android_servers")）。
        if (via == null) via = setPowerModeOnSurfaceControlDisplays(mode)
        if (via == null && setPowerModeOnBuiltInDisplay(mode)) via = "built-in-display"
        if (via == null && requestDisplayPower(mode)) via = "requestDisplayPower"
        // 兜底：唯一可能让守护进程 abort 的一路，再叠一层「已验证 / 调用方允许」门禁。
        if (via == null) via = setPowerModeOnDisplayControlDisplays(mode, allowRiskyPath)
        // 这一行只在息屏/亮屏时打一次，用于确认设备实际走的是哪条路径、哪些能力探测为真。
        Log.i(
            "RemoteRootService",
            "setDisplayPowerMode(mode=$mode, sdk=${Build.VERSION.SDK_INT}" +
                ", sc.ids=$hasSurfaceControlDisplayIds" +
                ", sc.token=$hasSurfaceControlDisplayToken" +
                ", sc.builtIn=$hasSurfaceControlBuiltInDisplay" +
                ", allowRisky=$allowRiskyPath" +
                ", dc=${displayControlState()}" +
                ", via=${via ?: "failed"})"
        )
        return via != null
    }

    /**
     * 用 root shell 注入 KEYCODE_SLEEP / KEYCODE_WAKEUP，让 PowerManagerService 真正进入 / 退出睡眠。
     *
     * 这是唯一能让「屏幕保持熄灭」的路径：面板级点灭之后 PWS 仍认为屏幕该亮，任何一次电源状态
     * 重算都会把面板重新点亮（详见 [setDisplayPowerMode] 的说明）。睡眠态下 PWS 的期望状态本身就是
     * OFF，不会再被拉回。
     *
     * 状态切换不是瞬时的，这里做有限轮询确认；一旦读不到 wakefulness（例如个别精简 ROM 裁掉了
     * dumpsys power），退化为「命令本身没有报错即视为成功」，避免误判失败导致调用方回滚超时。
     */
    private fun setPowerModeBySleepKey(mode: Int): String? {
        val wantOff = mode != SurfaceControlHidden.POWER_MODE_NORMAL
        val keyCode = if (wantOff) KeyEvent.KEYCODE_SLEEP else KeyEvent.KEYCODE_WAKEUP
        // 2>&1：input 的报错走 stderr，libsu 的 fastCmd 只收 stdout，这里直接把两者合并取回。
        val output = runCatching { ShellUtils.fastCmd("input keyevent $keyCode 2>&1") }.getOrNull() ?: return null
        if (output.contains("Exception", true) || output.contains("Permission", true) || output.contains("Unknown", true)) {
            Log.w("RemoteRootService", "inject keyevent $keyCode failed: ${output.take(200)}")
            return null
        }
        repeat(3) { attempt ->
            if (attempt > 0) Thread.sleep(200)
            val wakefulness = runCatching { ShellUtils.fastCmd("dumpsys power | grep -m1 mWakefulness=") }
                .getOrNull()?.trim().orEmpty()
            if (wakefulness.isEmpty()) return "keyevent/$keyCode"
            if (wakefulness.contains("Asleep") == wantOff) return "keyevent/$keyCode"
        }
        Log.w("RemoteRootService", "inject keyevent $keyCode did not take effect")
        return null
    }

    /** 只串行「触碰 [DisplayControl]」这一段；与全局 [lock] 无关，不会被备份长任务阻塞。 */
    private val displayPowerLock = Any()

    /**
     * 本机对 [DisplayControl] 的判定结果，持久化在 filesDir，跨守护进程重启有效：
     *
     * - [UNKNOWN]：从未成功触碰过。此时只有调用方明确声明「无长任务在途」才允许尝试；
     * - [SAFE]：曾完整走过一次静态初始化且进程存活，此后可以放心使用；
     * - [DANGEROUS]：上次正是在触碰它的那几行里进程死亡（abort），永远不再尝试。
     *
     * 之所以必须落盘：abort 时 Java 层一行代码都执行不到，进程内状态无从留存，
     * 唯一能留下的证据就是「调用前写下、正常返回后删除」的 marker 文件。
     */
    private enum class DisplayControlState { UNKNOWN, SAFE, DANGEROUS }

    @Volatile
    private var mDisplayControlState: DisplayControlState? = null
    private val displayControlStateLock = Any()

    private fun guardFile(name: String): File? = runCatching { File(context.filesDir, name) }.getOrNull()

    private fun readStateFile(): String? =
        runCatching { guardFile(STATE_FILE)?.takeIf { it.exists() }?.readText()?.trim() }.getOrNull()

    private fun displayControlState(): DisplayControlState = synchronized(displayControlStateLock) {
        mDisplayControlState?.let { return it }
        val markerFile = guardFile(MARKER_FILE)
        val persisted = readStateFile()
        val state = when {
            // marker 残留 = 上一次在 isAvailable()/getPhysicalDisplayIds() 里进程就死了。
            // （LMK 恰好在这个微秒级窗口内杀进程也会命中，误判的代价只是「息屏少一条路径」。）
            markerFile?.exists() == true -> DisplayControlState.DANGEROUS
            persisted == STATE_SAFE -> DisplayControlState.SAFE
            persisted == STATE_DANGER -> DisplayControlState.DANGEROUS
            else -> DisplayControlState.UNKNOWN
        }
        if ((state == DisplayControlState.SAFE && persisted != STATE_SAFE) ||
            (state == DisplayControlState.DANGEROUS && persisted != STATE_DANGER)
        ) {
            runCatching { guardFile(STATE_FILE)?.writeText(if (state == DisplayControlState.SAFE) STATE_SAFE else STATE_DANGER) }
        }
        runCatching { markerFile?.delete() }
        mDisplayControlState = state
        state
    }

    private fun markDisplayControlSafe() {
        synchronized(displayControlStateLock) {
            mDisplayControlState = DisplayControlState.SAFE
            runCatching { guardFile(STATE_FILE)?.writeText(STATE_SAFE) }
        }
    }

    /**
     * 反射探测 android.view.SurfaceControl 上某个隐藏静态方法是否存在。
     *
     * 必须走纯反射，不能放进 [SurfaceControlHidden]：Refine 的字节码改写只替换
     * 「真实类上存在同名同签名」的调用，自定义探测方法不会被改写，运行时会因为
     * stub 类只存在于编译期而 NoClassDefFoundError。
     *
     * 为什么不按版本号判断：Android 14 起 getPhysicalDisplayIds()/getPhysicalDisplayToken()
     * 被迁到 com.android.server.display.DisplayControl，但各 ROM 删除时机不一致（荣耀只删了
     * Android 14 的），只有探测才是唯一可靠依据。参见 scrcpy #4823 / #5109。
     */
    private fun hasSurfaceControlMethod(name: String, vararg parameterTypes: Class<*>): Boolean =
        runCatching {
            Class.forName("android.view.SurfaceControl").getDeclaredMethod(name, *parameterTypes)
            true
        }.getOrDefault(false)

    // 同一进程内类结构不会变化，探测一次即可；lazy 保证最多反射一次。
    private val hasSurfaceControlDisplayIds: Boolean by lazy { hasSurfaceControlMethod("getPhysicalDisplayIds") }
    private val hasSurfaceControlDisplayToken: Boolean by lazy {
        hasSurfaceControlMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType!!)
    }
    private val hasSurfaceControlBuiltInDisplay: Boolean by lazy {
        hasSurfaceControlMethod("getBuiltInDisplay", Int::class.javaPrimitiveType!!)
    }

    /**
     * 物理屏路径（首选）：Android 10+ 用 SurfaceControl 自身的
     * getPhysicalDisplayIds()/getPhysicalDisplayToken() 逐个物理屏设置电源模式。
     *
     * 关键点是 **用能力探测而不是版本号**：只要这两个方法还在就绝不触碰 [DisplayControl]，
     * 后者的静态初始化会执行 loadLibrary0("android_servers")，个别 ROM（荣耀 Android 14）
     * 在该 native 注册阶段直接 abort 整个进程，Java 层 catch 不住。
     * 参见 scrcpy issue #4823 与 PR #5109。
     *
     * @return 成功时返回数据源标记，旧方法不存在或未完全成功时返回 null 交由下一级兜底。
     */
    private fun setPowerModeOnSurfaceControlDisplays(mode: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        // ids 与 token 是配套 API，任一缺失都视为旧路径不可用（否则会在取 token 时抛 NoSuchMethodError）。
        if (!hasSurfaceControlDisplayIds || !hasSurfaceControlDisplayToken) return null

        val ids = SurfaceControlHidden.getPhysicalDisplayIds() ?: return null
        if (ids.isEmpty()) return null

        var allOk = true
        for (id in ids) {
            // token 为 null 说明该 display 拿不到句柄，跳过它但整体视为未完全成功，以便继续兜底。
            val token = SurfaceControlHidden.getPhysicalDisplayToken(id)
            if (token == null || !SurfaceControlHidden.setDisplayPowerMode(token, mode)) allOk = false
        }
        return if (allOk) "physical-displays/SurfaceControl" else null
    }

    /**
     * 物理屏路径（末级兜底）：Android 14+ 的 [DisplayControl]。
     *
     * 这是整条息屏链路里唯一可能让守护进程 abort 的调用，所以排在最后，并额外叠两道门禁：
     *
     * 1. [DisplayControlState.DANGEROUS] 直接放弃，[DisplayControlState.UNKNOWN] 则要求调用方
     *    明确声明「此刻没有长任务在途」（[allowRiskyPath]）。备份/恢复进行中的息屏一律走这条，
     *    宁可这次不自动息屏，也不能把正在流式读写的 tar/native 调用连同守护进程一起带走；
     * 2. 触碰前落 marker、正常返回才删：进程若在这几行里 abort，marker 会活下来，
     *    下次启动据此判为 DANGEROUS，绝不重复踩同一个坑。
     */
    private fun setPowerModeOnDisplayControlDisplays(mode: Int, allowRiskyPath: Boolean): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return synchronized(displayPowerLock) {
            when (displayControlState()) {
                DisplayControlState.DANGEROUS -> return@synchronized null
                DisplayControlState.UNKNOWN -> if (!allowRiskyPath) {
                    Log.i("RemoteRootService", "skip DisplayControl: 本机未验证，且调用方声明有长任务在途")
                    return@synchronized null
                }
                DisplayControlState.SAFE -> Unit
            }

            val marker = guardFile(MARKER_FILE)
            runCatching { marker?.writeText(STATE_INFLIGHT) }
            var loaded = false
            try {
                loaded = DisplayControl.isAvailable()
                if (!loaded) return@synchronized null

                val ids = DisplayControl.getPhysicalDisplayIds() ?: return@synchronized null
                if (ids.isEmpty()) return@synchronized null

                var allOk = true
                for (id in ids) {
                    val token = DisplayControl.getPhysicalDisplayToken(id)
                    if (token == null || !SurfaceControlHidden.setDisplayPowerMode(token, mode)) allOk = false
                }
                if (allOk) "physical-displays/DisplayControl" else null
            } finally {
                // 能走到 finally 就说明 native 加载没有 abort，本机可以安全使用这条路径。
                runCatching { marker?.delete() }
                if (loaded) markDisplayControlSafe()
            }
        }
    }

    /**
     * 内置屏兜底：所有 Android 版本都存在的 getBuiltInDisplay(0)。
     * 只覆盖主屏、不涉及副屏，且不会触发 android_servers 的 native 加载。
     */
    private fun setPowerModeOnBuiltInDisplay(mode: Int): Boolean {
        if (!hasSurfaceControlBuiltInDisplay) return false
        val token = SurfaceControlHidden.getBuiltInDisplay(Display.DEFAULT_DISPLAY) ?: return false
        return SurfaceControlHidden.setDisplayPowerMode(token, mode)
    }

    /**
     * 第三路：Android 15+ 的 DisplayManagerGlobal.requestDisplayPower。
     *
     * 不经过 DisplayControl，因此绕开了 android_servers 的 native 加载；真机上存在两种签名 ——
     * AOSP 的 (int, boolean) 与部分厂商的 (int, int)（后者参数为 Display.STATE_*），
     * 所以两种都探测、取存在的那个，不做假设。
     *
     * 放在 [DisplayControl] 之前：Android 15 起改用这条路径，绝大多数新机因此完全不必冒险加载
     * android_servers。代价是该 API 在部分机型上可能返回 true 却实际不生效（scrcpy #5530，
     * 上游 #5418 引入后由 3d1f036 回滚）——但「息屏不灵」的影响面远小于「守护进程 abort 打断备份」。
     */
    private fun requestDisplayPower(mode: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
        return runCatching {
            val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val instance = globalClass.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
                ?: return@runCatching false

            val on = mode == SurfaceControlHidden.POWER_MODE_NORMAL
            val booleanMethod = runCatching {
                globalClass.getDeclaredMethod(
                    "requestDisplayPower",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
            }.getOrNull()
            val stateMethod = runCatching {
                globalClass.getDeclaredMethod(
                    "requestDisplayPower",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            }.getOrNull()
            val method = booleanMethod ?: stateMethod ?: return@runCatching false

            val argument: Any = if (method === booleanMethod) on else if (on) Display.STATE_ON else Display.STATE_OFF
            method.isAccessible = true
            method.invoke(instance, Display.DEFAULT_DISPLAY, argument) as? Boolean ?: false
        }.getOrDefault(false)
    }

    override fun getScreenOffTimeout(): Int = synchronized(settingsLock) {
        ShellUtils.fastCmd("settings get system screen_off_timeout").toIntOrNull() ?: DEFAULT_IDLE_TIMEOUT
    }

    override fun setScreenOffTimeout(timeout: Int): Unit = synchronized(settingsLock) {
        ShellUtils.fastCmd("settings put system screen_off_timeout $timeout")
    }

    override fun forceStopPackageAsUser(packageName: String, userId: Int) = synchronized(lock) {
        activityManager.forceStopPackageAsUser(packageName, userId)
    }

    override fun setApplicationEnabledSetting(packageName: String, newState: Int, flags: Int, userId: Int, callingPackage: String?) = synchronized(lock) {
        packageManagerService.setApplicationEnabledSetting(packageName, newState, flags, userId, callingPackage)
    }

    override fun getApplicationEnabledSetting(packageName: String, userId: Int): Int = synchronized(lock) {
        packageManagerService.getApplicationEnabledSetting(packageName, userId)
    }

    override fun getPermissions(packageInfo: PackageInfo): List<PackagePermission> = synchronized(lock) {
        val permissions = mutableListOf<PackagePermission>()
        val uid = packageInfo.applicationInfo?.uid ?: -1
        val packageName = packageInfo.packageName
        val requestedPermissions = packageInfo.requestedPermissions?.toList() ?: listOf()
        val requestedPermissionsFlags = packageInfo.requestedPermissionsFlags?.toList() ?: listOf()
        // getOpsForPackage 返回多个 PackageOps entry（按 op 分组的历史记录），只取第一个会漏掉绝大部分 op，
        // 导致后续把缺失的 op 误记为 MODE_IGNORED（= 显式拒绝）。这里必须遍历所有 entry。
        val ops = runCatching {
            appOpsManager.getOpsForPackage(uid, packageName, null)
                ?.flatMap { it.ops }
                ?.associate { it.op to it.mode }
        }.getOrNull() ?: emptyMap()
        requestedPermissions.forEachIndexed { i, name ->
            runCatching {
                val permissionInfo = packageManager.getPermissionInfo(name, 0)
                val protection = PermissionInfoCompat.getProtection(permissionInfo)
                val protectionFlags = PermissionInfoCompat.getProtectionFlags(permissionInfo)
                val isGranted = (requestedPermissionsFlags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                val op = AppOpsManagerHidden.permissionToOpCode(name)
                // 采样不到的 op 说明系统未记录过，用 MODE_DEFAULT（由系统按 runtime 授权状态推导），
                // 绝不能记为 MODE_IGNORED——恢复时它会把刚授予的权限重新掐掉。
                val mode = ops.get(op) ?: AppOpsManager.MODE_DEFAULT
                if ((op != AppOpsManagerHidden.OP_NONE)
                    || (protection == PermissionInfo.PROTECTION_DANGEROUS || (protectionFlags and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0)
                ) {
                    permissions.add(PackagePermission(name, isGranted, op, mode))
                }
            }
        }
        permissions
    }

    override fun setOpsMode(code: Int, uid: Int, packageName: String?, mode: Int) = synchronized(lock) {
        appOpsManager.setMode(code, uid, packageName, mode)
    }

    override fun calculateMD5(src: String): String = synchronized(lock) { HashUtil.calculateMD5(src) }

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>?.toStringMap(): Map<String, String> =
        this?.entries?.associate { (k, v) -> k.toString() to v.toString() } ?: emptyMap()

    override fun rcloneRpc(method: String, input: String): String = synchronized(lock) {
        val res = Gomobile.rcloneRPC(method, input)
        if (res.status != 200L) {
            throw IllegalStateException("rclone RPC `$method` failed: status=${res.status}, output=${res.output}")
        }
        res.output
    }

    // 注意：绝不能用 synchronized(lock)。createRusticSnapshot 持锁整个备份时长，
    // 只读账务统计（core/stats / core/stats-reset）若也抢同一把锁，会被阻塞到备份结束，
    // 导致进度轮询在整段上传期间一次都跑不了。librclone 的 Gomobile.rcloneRPC 可并发调用，
    // core/stats 为只读统计，不与 createRusticSnapshot 的 native 备份互斥，故不加锁。
    override fun rcloneRpcNoLock(method: String, input: String): String {
        val res = Gomobile.rcloneRPC(method, input)
        if (res.status != 200L) {
            throw IllegalStateException("rclone RPC(NoLock) `$method` failed: status=${res.status}, output=${res.output}")
        }
        return res.output
    }

    override fun getRusticVersion(): String = synchronized(lock) { Rustic.getVersion() }

    override fun initRusticRepository(repositoryPath: String, password: String, options: MutableMap<Any?, Any?>?): Unit = synchronized(lock) {
        Rustic.initRepository(repositoryPath, password, options.toStringMap())
    }

    // ---------------------------------------------------------------- extra_verify 迁移
    // rustic 的 extra_verify 默认 true：每个 blob 加密压缩后还要在内存里解密+解压+逐字节比对，
    // 写端 CPU 接近翻倍（FD 备份 40MB/s 的主要瓶颈）。新仓库由 Kotlin 侧在建库 options 里
    // 带 EXTRA_VERIFY_KEY="0" 直接关闭，老仓库只能在 open 后改 config。
    //
    // 语义要点：
    //   - apply_config 只落盘 config、不更新已打开句柄（set_extra_verify 仅在 open 时应用一次），
    //     所以必须走独立的一次 Rustic.setExtraVerify 调用，下次 open 自然生效。
    //   - 返回 false = 无变化（幂等），重复调用安全。
    //   - 每个仓库只付一次"多开一次仓库"的成本，用 SharedPreferences 按仓库身份记录。
    private val extraVerifyMigrated: SharedPreferences by lazy {
        context.getSharedPreferences("restic_extra_verify_migration", Context.MODE_PRIVATE)
    }

    private fun extraVerifyMigrationKey(repositoryPath: String, options: Map<String, String>): String =
        // 云端多个账号共用同一 repositoryPath（如 "opendal:s3"），必须把 options 一起纳入身份，
        // 否则 A 账号迁移后 B 账号会被误判为已迁移、永远保持 extra_verify=true。
        (repositoryPath + "|" + options.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" })
            .hashCode().toString()

    /**
     * 对既有仓库做一次幂等的 extra_verify=false 迁移；每个仓库身份只尝试一次。
     * 失败不抛（不能因为优化项把备份/校验链路弄挂）。
     */
    private fun ensureRusticExtraVerifyDisabled(
        repositoryPath: String,
        password: String,
        options: Map<String, String>,
    ) {
        // 先看标记再探仓库：已迁移的仓库不该为这个优化多付任何一次 native 调用。
        val key = extraVerifyMigrationKey(repositoryPath, options)
        if (runCatching { extraVerifyMigrated.getBoolean(key, false) }.getOrDefault(false)) return
        // 未初始化的仓库不处理：init 路径由 Kotlin 侧 options 自带 "0"。
        if (!runCatching { Rustic.repositoryExists(repositoryPath, options) }.getOrDefault(false)) return
        runCatching { Rustic.setExtraVerify(repositoryPath, password, options, false) }
            .onSuccess { changed ->
                Log.i("ResticExtraVerify", "migrated path=$repositoryPath changed=$changed")
                runCatching { extraVerifyMigrated.edit().putBoolean(key, true).apply() }
            }
            .onFailure { e ->
                val msg = e.message ?: ""
                Log.w("ResticExtraVerify", "migration failed path=$repositoryPath msg=$msg")
                // append-only 仓库被 rustic_core 显式拒绝，重试无用，直接打标记，
                // 避免每次备份都白多开一次仓库。
                if (msg.contains("append-only", ignoreCase = true)) {
                    runCatching { extraVerifyMigrated.edit().putBoolean(key, true).apply() }
                }
            }
    }

    override fun rusticRepositoryExists(repositoryPath: String, options: MutableMap<Any?, Any?>?): Boolean = synchronized(lock) {
        Rustic.repositoryExists(repositoryPath, options.toStringMap())
    }

    override fun rusticRepositoryConfigId(
        repositoryPath: String,
        options: MutableMap<Any?, Any?>?,
    ): String? {
        val optionMap: Map<String, String> = options
            ?.entries
            ?.associate { (k, v) -> k.toString() to v.toString() }
            ?: emptyMap()
        return Rustic.repositoryConfigId(repositoryPath, optionMap)
    }

    override fun validateRusticRepository(repositoryPath: String, password: String, options: MutableMap<Any?, Any?>?): Unit = synchronized(lock) {
        try {
            val optionMap = options.toStringMap()
            Rustic.validateRepository(repositoryPath, password, optionMap)
            // 校验通过说明仓库与密码都正确，这是关掉写入自验证的最安全时机（每仓库仅一次）。
            ensureRusticExtraVerifyDisabled(repositoryPath, password, optionMap)
        } catch (e: Exception) {
            Log.i("ResticRepository", "impl.validateRusticRepository threw, msg=${e.message}")
            throw IllegalStateException(e.message ?: "validateRusticRepository failed")
        }
    }

    override fun createRusticSnapshot(repositoryPath: String, password: String, sourcePaths: MutableList<String>, tags: MutableList<String>, options: MutableMap<Any?, Any?>?, callback: ICallback?, cancelId: Long): String = synchronized(lock) {
        try {
            val optionMap = options.toStringMap()
            // 兜底：部分调用链不经过 validateRusticRepository，写入前再确认一次（每仓库仅一次）。
            ensureRusticExtraVerifyDisabled(repositoryPath, password, optionMap)
            Rustic.createSnapshot(repositoryPath, password, sourcePaths, tags, optionMap, callback, cancelId)
        } catch (e: Exception) {
            Log.i("RusticCancel", "impl.createRusticSnapshot threw, msg=${e.message}")
            throw IllegalStateException(e.message ?: "createRusticSnapshot failed")
        }
    }
    // 注意：绝不能用 synchronized(lock)。createRusticSnapshot 持锁整个备份时长，
    // 取消若也抢同一把锁会一直死等，取消永远无法生效。
    override fun cancelRusticBackup(cancelId: Long) {
        android.util.Log.i("RusticCancel", "impl.cancelRusticBackup id=$cancelId (about to call Rustic, NOT in lock)")
        // 先关 fd 备份的取消令牌（若该 cancelId 是 fd 备份）——
        // POLLHUP 让 native 侧立刻 kill tar 子进程，管道写端断流，
        // rustic 读端收 EOF 快速返回，不再拖着把整份数据读完。
        fdBackupCancelWriters.remove(cancelId)?.let { w ->
            android.util.Log.i("RusticCancel", "closing fd-backup cancel token for id=$cancelId")
            runCatching { w.close() }
        }
        Rustic.cancelBackup(cancelId)
        android.util.Log.i("RusticCancel", "impl.cancelRusticBackup id=$cancelId returned")
    }
    // 兜底：协作式取消（cancelRusticBackup）无效时，由 binder 线程池空闲线程执行，
    // 直接硬杀整个 root 守护进程（含卡在阻塞 socket 上的备份线程）。
    // 绝不能用 synchronized(lock)：卡死的 createRusticSnapshot 正持有 lock。
    override fun forceStopSelf() {
        Log.i("CancelFallback", "[CancelFallback] forceStopSelf called, pid=${android.os.Process.myPid()}, killing root process now")
        // 杀掉本进程（root 守护进程），卡死的 native 调用随进程一起终止；
        // app 侧阻塞的 binder 调用会因进程死亡抛 DeadObjectException 解栈。
        android.os.Process.killProcess(android.os.Process.myPid())
        // 兜底：极少数情况下 killProcess 未立即生效时，强制退出。
        kotlin.system.exitProcess(0)
    }

    override fun restoreRusticSnapshot(
        repositoryPath: String,
        password: String,
        snapshotId: String,
        destinationPath: String,
        options: MutableMap<Any?, Any?>?,          // AIDL Map 落地类型，按你本机 stub 生成的签名
        includeGlob: String,
        callback: ICallback?
    ) {
        Rustic.restoreSnapshot(
            repositoryPath = repositoryPath,
            password = password,
            snapshotId = snapshotId,
            destinationPath = destinationPath,
            includeGlob = includeGlob,          // 从 AIDL 传下来的 glob（没有就传 ""）
            options = options.toStringMap(),
            callback = callback,                // ICallback? → Any? 子类型，OK
        )
    }

    override fun checkRusticRepository(repositoryPath: String, password: String, options: MutableMap<Any?, Any?>?): Unit = synchronized(lock) {
        try {
            Rustic.checkRepository(repositoryPath, password, options.toStringMap())
        } catch (e: Exception) {
            Log.i("ResticRepository", "impl.checkRusticRepository threw, msg=${e.message}")
            throw IllegalStateException(e.message ?: "checkRusticRepository failed")
        }
    }

    override fun forgetRusticSnapshot(repositoryPath: String, password: String, options: MutableMap<Any?, Any?>?, snapshotId: String): Unit = synchronized(lock) {
        Rustic.forgetSnapshot(repositoryPath, password, snapshotId, options.toStringMap())
    }

    override fun pruneRusticRepository(repositoryPath: String, password: String, options: MutableMap<Any?, Any?>?, maxUnused: String, instantDelete: Boolean): Unit = synchronized(lock) {
        Rustic.pruneRepository(repositoryPath, password, maxUnused, options.toStringMap(), instantDelete)
    }

    override fun listRusticSnapshotsDb(repositoryPath: String, password: String, options: MutableMap<Any?, Any?>?, dbPath: String): Unit = synchronized(lock) {
        Rustic.listSnapshotsDb(repositoryPath, password, dbPath, options.toStringMap())
    }

    override fun opendalList(scheme: String, path: String, options: MutableMap<Any?, Any?>): String =
        synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            val opts = options as Map<String, String>
            Rustic.opendalList(scheme, path, opts)
        }

    override fun opendalCreateDir(scheme: String, path: String, options: MutableMap<Any?, Any?>) =
        synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            val opts = options as Map<String, String>
            Rustic.opendalCreateDir(scheme, path, opts)
        }

    override fun callTarCli(stdOut: String, stdErr: String, argv: Array<String>): Int {
        return TarWrapper.callCli(stdOut, stdErr, argv)
    }

    override fun backupTarToRustic(
        repositoryPath: String,
        password: String,
        filename: String,
        argv: Array<String>,
        tags: MutableList<String>,
        options: MutableMap<Any?, Any?>?,
        callback: ICallback?,
        cancelId: Long,
        // 注意：本函数 = synchronized(lock) 会持锁整段备份时长（几十秒级），
        // 期间所有其他 synchronized(lock) 的 rootservice 调用排队——与
        // createRusticSnapshot(L579) 相同的既有取舍。cancelRusticBackup(L589)
        // 和 forceStopSelf(L598) 刻意不走这把锁，取消链路不受影响。
        // 函数体内无依赖 lock 的可变状态（fdBackupCancelWriters 是
        // ConcurrentHashMap 自带线程安全）；若确认备份期间无其他模块调用
        // 加锁方法（UI 侧读文件/查统计等），可去掉 synchronized(lock)。
    ): String = synchronized(lock) {
        // 写入自验证（extra_verify）是本次 FD 备份吞吐的主要 CPU 开销来源，
        // 必须在真正开写之前关掉：老仓库在这里做一次额外 open + apply_config（每仓库仅一次），
        // 新仓库在建库 options 里已带 "0"，此处直接命中标记跳过。
        ensureRusticExtraVerifyDisabled(repositoryPath, password, options.toStringMap())
        val stdErrFile = File.createTempFile("fd-bak-err", ".tmp", context.cacheDir)
        val pipe = ParcelFileDescriptor.createPipe()
        val rfd = pipe[0]
        val wfd = pipe[1]
        // dup() 保证返回一个全新的 fd（不依赖 fromFd 是否 dup 的实现细节）
        val tarOut = wfd.dup()

        // 管道内核缓冲扩容（1MB）已下沉到 native：tar-wrapper.cpp 的 callCliFd 在
        // fork 前对写端 fd 调 fcntl(F_SETPIPE_SZ)，全版本通用、不受 Os.fcntlInt 的
        // API 30 限制，故 Kotlin 侧不再设置管道大小。
        Log.i("FdBackup", "pipe fds: rfd=${rfd.fd} wfd=${wfd.fd} tarOut=${tarOut.fd}")
        wfd.close()

        val cancelPipe = ParcelFileDescriptor.createPipe()
        val cancelRead = cancelPipe[0]
        val cancelWrite = cancelPipe[1]
        Log.i("FdBackup", "cancel fds: r=${cancelRead.fd} w=${cancelWrite.fd}")
        fdBackupCancelWriters[cancelId] = cancelWrite
        val tarCodeRef = AtomicInteger(-1)
        val tarErrorRef = AtomicReference<Throwable?>(null)
        var snapshotId: String? = null
        var fedBytes = -1L
        // FdPerf：采样本进程（root 守护进程）的 CPU 占用，约 1s 一条。
        // 必须读 /proc/self/stat 而不是上层 app 的读数——rustic 的分块/哈希/压缩/加密全在本进程内
        // 串行完成，app 只是转发 AIDL 进度回调，读它恒接近 0，会掩盖真正的瓶颈。
        // 判据：cores≈1.0 → 单核打满（CPU bound）；明显 <1 → 瓶颈在 IO / fsync。
        // 注意 tar 子进程是独立进程，其 CPU 不计入这里；本数反映的是 rustic 消费侧。
        val perfSampler = CpuCoreSampler()
        val perfStop = AtomicBoolean(false)
        val perfThread = thread(start = true, name = "fd-perf", isDaemon = true) {
            while (!perfStop.get()) {
                try {
                    Thread.sleep(200)
                    perfSampler.maybeLog()?.let {
                        Log.i("FdPerf", "where=root pid=${android.os.Process.myPid()} $it")
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    // 采样只是旁路信息，读 /proc 失败等任何异常都不能把守护进程带走。
                    Log.w("FdPerf", "sampler failed: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
        try {
            // tarOut 已在 try 外由 wfd.dup() 创建、wfd 已关闭——
            // 写端只剩 tarOut 一份引用，tar 退出 close 后内核即向读端发 EOF。
            // 注意：不要再 here 做 fromFd(wfd.fd)——wfd 已关，dup 出来的是坏 fd。
            //
            // fd 号必须在启动线程之前一次性取好。tar 线程一旦创建，本函数随时可能因为
            // rustic 提前抛错/取消而走到 finally 关闭 tarOut，那时线程再去读 tarOut.fd
            // 会抛 IllegalStateException("Already closed")；而线程里的未捕获异常会
            // 被 RuntimeInit 的默认处理器处理成「杀掉整个进程」，守护进程一死，
            // 上层所有进行中的备份/恢复全部变成 binder 死亡（真机 6 次崩溃即此路径）。
            val tarOutFd = tarOut.fd
            val cancelReadFd = cancelRead.fd
            val cancelWriteFd = cancelWrite.fd
            val tarThread = thread(start = true, name = "tar-fd-writer") {
                try {
                    // 第 5 个参数 cancelWriteFd：tar 子进程 fork 时继承了写端副本，
                    // native 侧在 dup2 后将其关闭——否则 Kotlin 侧 close 写端后内核引用
                    // 仍 ≥1，cancelRead 永远收不到 POLLHUP，abort 失效。
                    tarCodeRef.set(TarWrapper.callCliFd(tarOutFd, stdErrFile.absolutePath, argv, cancelReadFd, cancelWriteFd))
                } catch (t: Throwable) {
                    // 这条线程绝不能让异常逃出去：兜住并交给外层判定携带原因，
                    // 宁可让本次备份失败一次，也不能让守护进程被一条写线程带走。
                    Log.e("FdBackup", "tar-fd-writer failed: ${t.javaClass.simpleName}: ${t.message}")
                    tarErrorRef.set(t)
                } finally {
                    runCatching { tarOut.close() }      // 写端引用归零 → 读端 EOF
                    runCatching { cancelRead.close() }
                }
            }
            // detachFd 后 rfd 对象立即失效——先存原始号再打日志，
            // 访问 detach 后的 rfd.fd 会抛 IllegalStateException("Already closed")
            val dataFd = rfd.detachFd()
            Log.i("FdBackup", "createSnapshotFromFd dataFd=$dataFd tarOut=$tarOutFd cancelRead=$cancelReadFd cancelWrite=$cancelWriteFd")
            val json = runCatching {
                Rustic.createSnapshotFromFd(
                    repositoryPath, password, dataFd, filename,
                    tags, options.toStringMap(), callback, cancelId,
                )
            }.getOrElse { e ->
                // rustic 侧取消（cancel flag 命中写后端）会以错误形式返回——统一归并为取消
                if (e.message?.contains("cancel", ignoreCase = true) == true)
                    throw kotlinx.coroutines.CancellationException("rustic backup canceled: ${e.message}")
                throw e
            }
            // 取消窗口内 rustic 可能返回空/错误 JSON——同样按取消处理，
            // 不交给后面的四要素判定（那会走 FATAL+forget，可能误杀好快照）。
            // 取消令牌被 cancelRusticBackup 消费（map 中 cancelId 消失）才算取消；
            // 其余空结果按真实错误处理，交给四要素判定/异常路径。
            val wasCanceled = fdBackupCancelWriters[cancelId] == null
            val snapshotIdEmpty = runCatching { JSONObject(json).optString("snapshotId") }
                .getOrNull().isNullOrEmpty()
            if (wasCanceled && (json.isNullOrEmpty() || snapshotIdEmpty)) {
                throw kotlinx.coroutines.CancellationException("rustic backup canceled: empty/error result after cancel")
            }
            Log.i("FdBackup", "rustic returned: $json")

            // rustic 正常返回后：tar 可能还堵在 pipe_write（全量 dedup 提前返回场景），
            // 关取消令牌触发 native 侧 SIGKILL，join 加超时兜底，不让 binder 线程无限挂住。
            tarThread.join(5_000)   // join(millis) 返回 Unit，存活与否要看 isAlive
            if (tarThread.isAlive) {
                Log.w("FdBackup", "tar still alive after rustic return, aborting via cancelFd")
                runCatching { cancelWrite.close() }   // POLLHUP → native SIGKILL tar 子进程
                tarThread.join(2_000)
                if (tarThread.isAlive) {
                    Log.e("FdBackup", "tar-fd-writer thread still alive after abort; tarCode will be -1")
                }
            }
            val tarCode = tarCodeRef.get()
            if (tarCode == -3) {
                // tar 被 cancelFd 杀掉 = 取消路径
                throw kotlinx.coroutines.CancellationException("rustic backup canceled: tar aborted via cancelFd (tarCode=$tarCode)")
            }
            val stderrText = stdErrFile.readText()
            val totals = Regex("""Total bytes written:\s*(\d+)""")
                .find(stderrText)?.groupValues?.get(1)?.toLongOrNull()
            val warnings = Regex("""(?:File removed before we read it|file changed as we read it)""")
                .findAll(stderrText).count()
            val obj = JSONObject(json)
            snapshotId = obj.optString("snapshotId").takeIf { it.isNotEmpty() }
            fedBytes = obj.optLong("fedBytes", -1L)
            Log.i("FdBackup", "tarCode=$tarCode totals=$totals fedBytes=$fedBytes warnings=$warnings snapshot=$snapshotId")

            // 四要素判定（收紧版）：parent 禁用后 rustic 每次全量读管道，
            // 正常路径必然 fedBytes == totals > 0 且 tarCode ∈ {0,1}。
            // fedBytes <= 0 只可能是空快照 bug（snapshotId 有效但快照是 0B 空壳，
            // 恢复列表里无法使用）——一律 FATAL + forget，不再做任何豁免。
            // tarCode=-3（cancelFd abort）也归 FATAL：那是取消/异常路径的标记。
            val fatal = tarCode !in 0..1 ||
                    totals == null ||
                    fedBytes <= 0 ||
                    fedBytes != totals
            if (fatal) {
                Log.w("FdBackup", "FATAL: tarCode=$tarCode totals=$totals fedBytes=$fedBytes snapshot=$snapshotId stderr=$stderrText")
                if (snapshotId != null) {
                    runCatching {
                        Rustic.forgetSnapshot(repositoryPath, password, snapshotId!!, options.toStringMap())
                    }
                }
                throw IllegalStateException(
                    "tar/rustic fd backup failed: tarCode=$tarCode totals=$totals fedBytes=$fedBytes " +
                            "stderr=$stderrText tarError=${tarErrorRef.get()?.message}",
                    tarErrorRef.get(),
                )
            }
            obj.put("tarCode", tarCode).put("warnings", warnings)
            obj.toString()
        } finally {
            perfStop.set(true)
            perfThread.interrupt()
            fdBackupCancelWriters.remove(cancelId, cancelWrite)
            runCatching { cancelWrite.close() }
            runCatching { cancelRead.close() }
            runCatching { tarOut.close() }
            runCatching { wfd.close() }
            runCatching { rfd.close() }
            stdErrFile.delete()
        }
    }

    private companion object {
        /** 持久化的判定结果文件名。 */
        const val STATE_FILE = "display_control_state"

        /** 触碰 DisplayControl 前写入、正常返回后删除的留痕文件；残留即代表进程死在调用里。 */
        const val MARKER_FILE = "display_control_inflight"
        const val STATE_SAFE = "safe"
        const val STATE_DANGER = "danger"
        const val STATE_INFLIGHT = "inflight"
    }
}
