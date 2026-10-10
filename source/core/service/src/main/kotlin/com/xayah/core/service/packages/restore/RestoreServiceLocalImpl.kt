package com.xayah.core.service.packages.restore

import android.util.Log
import android.content.Intent
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.ResticRepoLocator
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readBackupDirectory
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoConfigId
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.datastore.saveResticRepoConfigId
import com.xayah.core.datastore.saveResticRepoPath
import com.xayah.core.datastore.readResticActiveIsOtg
import com.xayah.core.datastore.readResticOtgRepoPath
import com.xayah.core.datastore.readResticOtgPassword
import com.xayah.core.datastore.readResticOtgRepoConfigId
import com.xayah.core.datastore.saveResticOtgRepoPath
import com.xayah.core.model.CloudType
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.model.restic.ResticRestoreQueueItem
import com.xayah.core.restic.CloudResticBackend
import com.xayah.core.restic.ResticRepository
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesRestoreUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.model.util.formatSize
import com.xayah.core.model.util.formatToStorageSizePerSecond
import com.xayah.core.model.OperationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.system.measureTimeMillis
import javax.inject.Inject
import java.io.File

@AndroidEntryPoint
internal class RestoreServiceLocalImpl @Inject constructor() : AbstractRestoreService() {
    override val mTAG: String = "RestoreServiceLocalImpl"

    private var mTargetPackageName: String = ""

    // 批量恢复队列（含 CONFIG 与重型项）；空表示旧单包路径
    private var mRestoreQueue: List<ResticRestoreQueueItem> = emptyList()
    private var mRestoreQueueMap: Map<Pair<String, Int>, List<ResticRestoreQueueItem>> = emptyMap()

    @Inject
    override lateinit var mRootService: RemoteRootService

    @Inject
    override lateinit var mPathUtil: PathUtil

    @Inject
    override lateinit var mCommonBackupUtil: CommonBackupUtil

    @Inject
    override lateinit var mTaskDao: TaskDao

    @Inject
    override lateinit var mTaskRepo: TaskRepository

    // 本地 restic 仓库（core:restic，core:service 已依赖）
    @Inject
    lateinit var mResticRepo: ResticRepository

    // 新增：OTG 前置对齐解析器（第 7 步 core/data）
    @Inject
    lateinit var mResticRepoLocator: ResticRepoLocator

    // 云端 restic 后端注册表：Hilt 多绑定 Map<CloudType, CloudResticBackend>
    // 注意 @JvmSuppressWildcards，否则 Dagger 生成 Map<CloudType, ? extends CloudResticBackend> 找不到绑定
    @Inject
    lateinit var resticBackends: Map<CloudType, @JvmSuppressWildcards CloudResticBackend>

    @Inject
    lateinit var mCloudRepo: CloudRepository

    override val mTaskEntity by lazy {
        TaskEntity(
            id = 0,
            opType = OpType.RESTORE,
            taskType = TaskType.PACKAGE,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(mTAG, "=== RestoreServiceLocalImpl 创建 ===")
        Log.d(mTAG, "Root目录: $mRootDir")
        Log.d(mTAG, "Apps目录: $mAppsDir")
        Log.d(mTAG, "Configs目录: $mConfigsDir")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mTargetPackageName = intent?.getStringExtra("TARGET_PACKAGE_NAME") ?: ""
        Log.d(mTAG, "=== 服务启动命令接收 ===")
        Log.d(mTAG, "目标包名: $mTargetPackageName")

        // 读取批量恢复队列临时文件（缺失/异常置空，兼容旧单包路径）
        try {
            val queueFile = File(mContext.cacheDir, RESTORE_QUEUE_FILE)
            if (queueFile.exists()) {
                mRestoreQueue = QUEUE_JSON.decodeFromString<List<ResticRestoreQueueItem>>(queueFile.readText())
                mRestoreQueueMap = mRestoreQueue.groupBy { it.packageName to it.userId }
                Log.d(mTAG, "读取批量恢复队列成功，共 ${mRestoreQueue.size} 条，分组 ${mRestoreQueueMap.size} 个包")
            } else {
                mRestoreQueue = emptyList()
                mRestoreQueueMap = emptyMap()
                Log.d(mTAG, "无批量恢复队列文件，走旧单包路径")
            }
        } catch (e: Exception) {
            Log.e(mTAG, "读取批量恢复队列失败，置空: ${e.message}", e)
            mRestoreQueue = emptyList()
            mRestoreQueueMap = emptyMap()
        }

        val restoreDir = File("${mRootDir}/restore")
        Log.d(mTAG, "restore目录存在: ${restoreDir.exists()}")

        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 全融合第一步：初始化阶段（getPackages 之前）取回每个包的 CONFIG，
     * 解到 /restore/apps/{pkg}/user_{userId}/，随后读 json→upsert PackageEntity
     * (opType=RESTORE, backupDir=.../restore/, activated=true)，
     * 使后续 getPackages() 的 queryActivated 能查到批量选中的所有包。
     */
    override suspend fun onPrepareEntities() {
        if (mRestoreQueue.isEmpty()) {
            Log.d(mTAG, "队列为空，跳过 onPrepareEntities（旧单包路径由既有流程处理）")
            return
        }

        // 只取每个 (pkg,userId) 的 CONFIG 项
        val configItems = mRestoreQueue.filter { it.dataType == DataType.PACKAGE_CONFIG }
        Log.d(mTAG, "onPrepareEntities: 取回 config 项 ${configItems.size} 个")

        // 1) 先分流：磁盘已有 config 直接复用（串行，仅读 json + DB upsert，很快）；
        //    磁盘缺失的收集起来，走 restic 兜底并发解出
        val needExtract = mutableListOf<ResticRestoreQueueItem>()
        for (item in configItems) {
            val targetPath = transitDirOf(item.packageName, item.userId)
            val configFile = File(targetPath, "package_restore_config.json")
            if (configFile.exists()) {
                Log.d(
                    mTAG,
                    "onPrepareEntities: 复用磁盘已有 config，跳过 restic 重解 ${item.packageName}/user_${item.userId}"
                )
                activatePackageFromConfig(item.packageName, item.userId, targetPath)
            } else {
                needExtract.add(item)
            }
        }

        // 2) 兜底：磁盘无 config（例如单包旧路径或阶段1未落盘）才用 restic 解——受限并发
        if (needExtract.isNotEmpty()) {
            val semaphore = Semaphore(permits = 3)  // 与云端并发解 config 保持一致，避免连接数过高
            val extractMs = measureTimeMillis {
                // 并发只做 restic 解出（重活），DB 激活留到 awaitAll 后单线程执行
                val results = coroutineScope {
                    needExtract.map { item ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val targetPath = transitDirOf(item.packageName, item.userId)
                                val snapshotSubPath =
                                    snapshotSubPathOf(item.packageName, item.userId)
                                val ok = extractOne(
                                    item = item,
                                    includePath = "package_restore_config.json",
                                    targetPath = targetPath,
                                    snapshotSubPath = snapshotSubPath
                                )
                                Triple(item, targetPath, ok)
                            }
                        }
                    }.awaitAll()
                }

                // 单线程汇总激活，避免并发写 DB
                for ((item, targetPath, ok) in results) {
                    if (!ok) {
                        Log.e(mTAG, "config 取回失败: ${item.packageName}/user_${item.userId}")
                        continue
                    }
                    activatePackageFromConfig(item.packageName, item.userId, targetPath)
                }
            }
            Log.d(
                mTAG,
                "耗时/onPrepareEntities.并发兜底解config: ${extractMs}ms, count=${needExtract.size}"
            )
        }
    }

    /**
     * 每个包写回前：解出该包的重型 tar（非 CONFIG 项）到中转目录。
     * 每个 item 期间接线 restic 下载进度回调，把 "网速 | 已下载 / 总大小" 写入对应 dataType 行，
     * 并用 bytesWritten/bytesTotal 驱动该行 progress（恢复有总量，可显示真实百分比）。
     */
    override suspend fun onBeforeRestorePackage(p: PackageEntity, t: TaskDetailPackageEntity, userId: Int) {
        val items = mRestoreQueueMap[p.packageName to userId]?.filter { it.dataType != DataType.PACKAGE_CONFIG }
            ?: emptyList()
        if (items.isEmpty()) {
            Log.d(mTAG, "onBeforeRestorePackage: 队列无重型项，跳过 ${p.packageName}/user_$userId")
            return
        }
        val targetPath = transitDirOf(p.packageName, userId)
        // 重型 tar 走 fd 流式入库，快照登记的归档路径是裸文件名（apk.tar 等），
        // 不存在 apps/<pkg>/user_<id>/ 前缀，故 subPath 只能用裸文件名，不能用目录路径。
        // （目录型 subPath 只适用于 CONFIG 这类真实文件入库的快照）

        for (item in items) {
            Log.d(mTAG, "解出重型 tar: ${p.packageName}/user_$userId ${item.dataType.type}")

            // 下载(解 tar)阶段先把该子项状态置为 PROCESSING，
            // 使 Card.kt 门控 (state == PROCESSING && progress > 0f) 成立，背景进度条得以填充
            t.update(dataType = item.dataType, state = OperationState.PROCESSING)

            // 每个 item 独立的进度状态
            val bytesWrittenRef = AtomicLong(0L)
            val bytesTotalRef = AtomicLong(0L)
            val speedRef = AtomicLong(0L)
            val polling = AtomicBoolean(true)

            // 速度差分基准（仅回调线程访问）
            var lastTime = System.currentTimeMillis()
            var lastBytes = 0L

            val progressCallback = object : ResticRepository.ResticProgressCallback {
                override fun onRestoreProgress(
                    filesFinished: Long,
                    filesTotal: Long,
                    bytesWritten: Long,
                    bytesTotal: Long,
                    filesSkipped: Long,
                    bytesSkipped: Long
                ) {
                    bytesWrittenRef.set(bytesWritten)
                    bytesTotalRef.set(bytesTotal)

                    // 瞬时速度：参照 ResticRestoreViewModel 的差分方式
                    val now = System.currentTimeMillis()
                    val timeDiff = now - lastTime
                    if (timeDiff > 0 && bytesWritten > lastBytes) {
                        speedRef.set((bytesWritten - lastBytes) * 1000 / timeDiff)
                    }
                    lastTime = now
                    lastBytes = bytesWritten
                }

                override fun onBackupProgress(
                    percentDone: Float, bytesDone: Long,
                    bytesTotal: Long, filesDone: Long, filesTotal: Long,
                    speed: Long
                ) {
                    // 备份进度，恢复不使用
                }
            }

            // 轮询协程：每 500ms 把 "速度 | 已下载 / 总大小" + progress 写回该 dataType 行
            val pollingJob = with(CoroutineScope(coroutineContext)) {
                launch {
                    while (polling.get()) {
                        val speed = speedRef.get()
                        val speedText = if (speed > 0) speed.formatToStorageSizePerSecond() else ""
                        val written = bytesWrittenRef.get()
                        val total = bytesTotalRef.get()
                        val downloaded = written.toDouble().formatSize()
                        val totalText = total.toDouble().formatSize()
                        val content = if (speedText.isNotEmpty()) {
                            "$speedText | $downloaded / $totalText"
                        } else {
                            "$downloaded / $totalText"
                        }
                        val progress = if (total > 0) written.toFloat() / total else 0f
                        t.update(dataType = item.dataType, content = content, progress = progress)
                        delay(500)
                    }
                }
            }

            val tarName = "${item.dataType.type}.tar"
            // 兼容两代备份格式：
            //  - 新版 fd 流式入库：快照归档路径是裸文件名 "apk.tar"
            //  - 旧版中转文件入库：快照归档路径是完整绝对路径，用目录型 subPath 定位
            // 先用裸文件名尝试；没落盘则回退旧版目录 subPath 再试一次。
            val candidateSubPaths = listOf(
                tarName,                                            // 候选1：裸文件名 —— 新 fd 流式快照
                snapshotSubPathOf(p.packageName, userId),           // 候选2：当前保存目录(迁移后=DataBackuptmp)/apps/... —— 兼容自定义 backup_directory
                legacySnapshotSubPathOf(p.packageName, userId)      // 候选3：旧共享目录 /storage/emulated/0/DataBackup/apps/... —— 旧中转快照
            )
            var ok = false
            var landed = false
            try {
                for ((idx, sub) in candidateSubPaths.withIndex()) {
                    ok = extractOne(
                        item = item,
                        includePath = tarName,
                        targetPath = targetPath,
                        snapshotSubPath = sub,
                        progressCallback = progressCallback,
                        // 仅最后一个候选失败时才 dump 快照 paths，避免每候选都整仓 listSnapshots
                        dumpOnFail = (idx == candidateSubPaths.lastIndex)
                    )
                    landed = File("$targetPath/$tarName").exists()
                    if (ok && landed) break
                    Log.w(mTAG, "重型解出未命中: ${item.dataType.type} subPath='$sub' ok=$ok landed=$landed，尝试下一个候选")
                }
            } finally {
                // 停止轮询并等待子协程真正结束（必须有，否则 pollingJob 永不退出，
                // 其 Job 继承自外层 withIOContext，会拖住 processing() 无法返回 → 卡在后处理之前）。
                polling.set(false)
                // cancel() 只发信号不等待；cancelAndJoin() 挂起等待子协程真正结束，消除残留。
                pollingJob.cancelAndJoin()
                // 定格该 dataType 行的最终进度/速度显示
                val finalSpeed = speedRef.get()
                val finalSpeedText = if (finalSpeed > 0) finalSpeed.formatToStorageSizePerSecond() else ""
                val finalWritten = bytesWrittenRef.get()
                val finalTotal = bytesTotalRef.get()
                val finalContent = if (finalSpeedText.isNotEmpty()) {
                    "$finalSpeedText | ${finalWritten.toDouble().formatSize()} / ${finalTotal.toDouble().formatSize()}"
                } else {
                    "${finalWritten.toDouble().formatSize()} / ${finalTotal.toDouble().formatSize()}"
                }
                t.update(dataType = item.dataType, content = finalContent, progress = 1f)
            }
            if (ok && landed) {
                t.update(dataType = item.dataType, state = OperationState.DOWNLOADING)
            } else {
                t.update(dataType = item.dataType, state = OperationState.ERROR)
                Log.e(mTAG, "重型解出失败: ${p.packageName} ${item.dataType.type} ok=$ok landed=$landed（后续 restore 会因缺 tar 失败并 fail-fast）")
            }
        }
    }

    override suspend fun getPackages(): List<PackageEntity> {
        Log.d(mTAG, "=== 开始获取恢复包列表 ===")

        val restoreDir = File("${mRootDir}/restore")
        val backupDir = if (restoreDir.exists()) "${mRootDir}/restore/" else mRootDir

        val allPackages = mPackageRepo.queryActivated(OpType.RESTORE, "", backupDir)
        Log.d(mTAG, "查询到总应用数: ${allPackages.size}")

        val packages = if (mTargetPackageName.isNotEmpty()) {
            allPackages.filter { it.packageName == mTargetPackageName }
        } else {
            allPackages
        }
        Log.d(mTAG, "筛选后查询到 ${packages.size} 个应用")
        return packages
    }

    override suspend fun restore(type: DataType, userId: Int, p: PackageEntity, t: TaskDetailPackageEntity, srcDir: String) {
        if (type == DataType.PACKAGE_APK) {
            mPackagesRestoreUtil.restoreApk(userId = userId, p = p, t = t, srcDir = srcDir)
        } else {
            mPackagesRestoreUtil.restoreData(userId = userId, p = p, t = t, dataType = type, srcDir = srcDir)
        }
        t.update(dataType = type, progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    override suspend fun onAfterRestorePackage(p: PackageEntity, userId: Int, success: Boolean) {
        val transitDir = transitDirOf(p.packageName, userId)
        if (File(transitDir).exists()) {
            Log.d(mTAG, "逐包清理中转目录(success=$success): $transitDir")
            mRootService.deleteRecursively(transitDir)
        } else {
            Log.d(mTAG, "中转目录不存在，跳过: $transitDir")
        }
    }

    override suspend fun clear() {
        val restoreAppsDir = "${mRootDir}/restore/apps"
        if (File(restoreAppsDir).exists()) {
            Log.d(mTAG, "整体清理临时恢复目录: $restoreAppsDir")
            mRootService.deleteRecursively(restoreAppsDir)
        }
        val queueFile = File(mContext.cacheDir, RESTORE_QUEUE_FILE)
        if (queueFile.exists()) {
            Log.d(mTAG, "删除批量恢复队列文件: ${queueFile.path}")
            queueFile.delete()
        }
    }

    // ---------- 私有工具 ----------

    /** 中转目录唯一拼法（末尾带 /，供 activatePackageFromConfig 直接拼接文件名） */
    private fun transitDirOf(pkg: String, userId: Int): String =
        "${mRootDir}/restore/apps/$pkg/user_$userId/"

    private suspend fun snapshotSubPathOf(pkg: String, userId: Int): String {
        // 必须与 ResticRestoreViewModel.restoreFromResticSnapshots 的 backupBaseDir 计算完全一致
        val backupBaseDir = mContext.readBackupDirectory() ?: mContext.localBackupSaveDir()
        return "$backupBaseDir/apps/$pkg/user_$userId"
    }

    /**
     * 旧版 fork(中转式) 快照登记的归档路径前缀是旧共享根 /storage/emulated/0/DataBackup，
     * 与迁移后的 localBackupSaveDir()(=DataBackuptmp) 不同，故需单独一条 LEGACY 候选兜底。
     * 不是 suspend —— 纯常量拼接，无需读 datastore。
     */
    private fun legacySnapshotSubPathOf(pkg: String, userId: Int): String =
        "${ConstantUtil.LEGACY_SHARED_PATH}/apps/$pkg/user_$userId"

    /** 密码解析统一走注册表：各后端 resolveResticPassword 内部 decode 自己的 extra 并回退对应 datastore */
    private suspend fun resolveCloudPassword(cloudEntity: CloudEntity): String? {
        return resticBackends.getValue(cloudEntity.type).resolveResticPassword(cloudEntity)
    }

    /**
     * 本地/云端分派解出：accountName 空→本地 restoreSnapshot；非空→按注册表多态分派云端。
     * dumpOnFail: 仅当该次 restoreSnapshot 返回 false 时才 dump 快照 paths（整仓 listSnapshots 很贵）。
     *             双候选回退时只在最后一个候选上置 true，保证整轮最多 dump 一次。
     */
    private suspend fun extractOne(
        item: ResticRestoreQueueItem,
        includePath: String,
        targetPath: String,
        snapshotSubPath: String,
        progressCallback: ResticRepository.ResticProgressCallback? = null,
        dumpOnFail: Boolean = true
    ): Boolean {
        Log.w(mTAG, "extractOne BEGIN: snapshotId=${item.snapshotId} dataType=${item.dataType.type} account='${item.accountName}' isOtg=${item.isOtg} subPath='$snapshotSubPath' include='$includePath' target='$targetPath'")
        val ok = try {
            if (item.accountName.isEmpty()) {
                val isOtg = item.isOtg
                val repoPath: String?
                val password: String?
                if (isOtg) {
                    val otgPath = mContext.readResticOtgRepoPath()
                    if (otgPath.isNullOrEmpty()) {
                        Log.e(mTAG, "extractOne: itemIsOtg=true 但未登记 OTG 仓库路径，保守中止")
                        return false
                    }
                    val otgPwd = mContext.readResticOtgPassword() ?: "databackup_default"
                    val verifyOk = runCatching { mResticRepo.verifyRepository(otgPath, otgPwd) }.getOrDefault(false)
                    Log.d("RestoreOtg", "extractOne: itemIsOtg=true repoPath=$otgPath checkOk=$verifyOk")
                    if (!verifyOk) {
                        Log.e(mTAG, "extractOne: OTG 仓库校验失败（未插盘/仓库被删/密码错），保守中止")
                        return false
                    }
                    repoPath = otgPath
                    password = otgPwd
                } else {
                    val savedPath = mContext.readResticRepoPath()
                    repoPath = if (savedPath.isNullOrEmpty()) {
                        savedPath
                    } else {
                        val savedConfigId = mContext.readResticRepoConfigId()
                        when (val r = mResticRepoLocator.resolveCurrentResticRepoPath(savedPath, savedConfigId)) {
                            is ResticRepoLocator.ResolveResult.Passthrough -> r.path
                            is ResticRepoLocator.ResolveResult.Matched -> {
                                if (r.changed) {
                                    mContext.saveResticRepoPath(r.path)
                                }
                                r.path
                            }
                            is ResticRepoLocator.ResolveResult.NotFound -> {
                                Log.e(mTAG, "未检测到 OTG 仓库，无法解出")
                                return false
                            }
                            is ResticRepoLocator.ResolveResult.Ambiguous -> {
                                Log.e(mTAG, "检测到多个 OTG 仓库候选，无法自动确定: ${r.candidates}")
                                return false
                            }
                        }
                    }
                    password = mContext.readResticPassword()
                    Log.d("RestoreOtg", "extractOne: itemIsOtg=false repoPath=$repoPath")
                }
                if (repoPath.isNullOrEmpty() || password.isNullOrEmpty()) {
                    Log.e(mTAG, "本地 restic 配置不完整，无法解出")
                    return false
                }
                val r = mResticRepo.restoreSnapshot(
                    repoPath = repoPath,
                    password = password,
                    snapshotId = item.snapshotId,
                    targetPath = targetPath,
                    snapshotSubPath = snapshotSubPath,
                    includePath = includePath,
                    progressCallback = progressCallback
                )
                // ===== 诊断：仅在失败且允许时才 dump（成功路径完全不列举快照，省掉整仓 listSnapshots）=====
                if (!r && dumpOnFail) dumpSnapshotPaths(repoPath, password, item.snapshotId)
                Log.w(mTAG, "extractOne END(local): snapshotId=${item.snapshotId} ok=$r")
                r
            } else {
                val cloudEntity = mCloudRepo.queryByName(item.accountName)
                if (cloudEntity == null) {
                    Log.e(mTAG, "云端账户未找到: ${item.accountName}")
                    return false
                }
                val password = resolveCloudPassword(cloudEntity)
                if (password.isNullOrEmpty()) {
                    Log.e(mTAG, "云端 restic 密码解析失败: ${item.accountName}")
                    return false
                }
                val r = resticBackends.getValue(cloudEntity.type).restoreSnapshot(
                    cloudEntity = cloudEntity,
                    password = password,
                    snapshotId = item.snapshotId,
                    targetPath = targetPath,
                    snapshotSubPath = snapshotSubPath,
                    includePath = includePath,
                    progressCallback = progressCallback
                )
                Log.w(mTAG, "extractOne END(cloud/${cloudEntity.type}): snapshotId=${item.snapshotId} ok=$r")
                r
            }
        } catch (e: Exception) {
            Log.e(mTAG, "extractOne 异常: snapshotId=${item.snapshotId} ${e.message}", e)
            false
        }
        // ===== 诊断：无论成败，列出解出目标目录实际落盘的文件（轻量，纯本地 File 遍历，保留）=====
        Log.w(mTAG, "extractOne AFTER: ok=$ok target='$targetPath' files=${listDirUnder(targetPath)}")
        return ok
    }

    /**
     * 读取已解出的 config→upsert PackageEntity 并激活，逻辑对齐
     * ResticRestoreViewModel.readPackageConfig + updateDatabase。
     */
    private suspend fun activatePackageFromConfig(pkg: String, userId: Int, targetPath: String) {
        try {
            val configPath = "${targetPath}package_restore_config.json"
            val entity = mRootService.readJson<PackageEntity>(configPath) ?: run {
                Log.e(mTAG, "config 读取为空: $configPath")
                return
            }
            val existingId = mPackageDao.query(pkg, OpType.RESTORE, userId)?.id ?: 0L

            val toUpsert = entity.copy(
                id = existingId,
                indexInfo = entity.indexInfo.copy(
                    opType = OpType.RESTORE,
                    packageName = pkg,
                    userId = userId,
                    cloud = "",
                    backupDir = "${mRootDir}/restore/"
                ),
                extraInfo = entity.extraInfo.copy(activated = true)
            )
            mPackageDao.upsert(toUpsert)
            Log.d(mTAG, "config→DB 已激活: $pkg/user_$userId (id=$existingId, ${if (existingId == 0L) "INSERT" else "UPDATE"})")
        } catch (e: Exception) {
            Log.e(mTAG, "activatePackageFromConfig 失败: $pkg/user_$userId - ${e.message}", e)
        }
    }

    /** 诊断：列出该快照在仓库里登记的 paths（看入库归档路径是否带 apps/<pkg>/user_<id>/ 前缀） */
    private suspend fun dumpSnapshotPaths(repoPath: String, password: String, snapshotId: String) {
        try {
            val snaps = mResticRepo.listSnapshots(repoPath, password)
            val hit = snaps.firstOrNull { it.id == snapshotId || it.id.startsWith(snapshotId) }
            if (hit == null) {
                Log.w(mTAG, "dumpSnapshotPaths: 快照 $snapshotId 不在 listSnapshots 结果里")
            } else {
                Log.w(mTAG, "dumpSnapshotPaths: snapshot=${hit.id} paths=${hit.paths} tags=${hit.tags}")
            }
        } catch (e: Exception) {
            Log.w(mTAG, "dumpSnapshotPaths 失败: ${e.message}")
        }
    }

    /** 诊断：递归列出目录下文件（相对路径+大小），目录不存在返回 "[dir-missing]" */
    private fun listDirUnder(dir: String): String {
        val f = File(dir)
        if (!f.exists()) return "[dir-missing]"
        return f.walkTopDown().filter { it.isFile }
            .joinToString(", ") { "${it.relativeTo(f).path}(${it.length()}B)" }
            .ifEmpty { "[empty]" }
    }

    @Inject
    override lateinit var mPackageDao: PackageDao

    @Inject
    override lateinit var mPackageRepo: PackageRepository

    @Inject
    override lateinit var mPackagesRestoreUtil: PackagesRestoreUtil

    override val mRootDir by lazy { mContext.localBackupSaveDir() }
    override val mAppsDir by lazy { mPathUtil.getLocalBackupAppsDir() }
    override val mConfigsDir by lazy { mPathUtil.getLocalBackupConfigsDir() }

    companion object {
        private const val RESTORE_QUEUE_FILE = "restic_restore_queue.json"
        private val QUEUE_JSON = Json { ignoreUnknownKeys = true }
    }
}