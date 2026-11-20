package com.xayah.feature.main.restore

import android.util.Log
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.datastore.readResticOtgRepoPath
import com.xayah.core.datastore.readResticOtgPassword
import com.xayah.core.datastore.readResticActiveIsOtg
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.model.restic.ResticMediaRestoreQueueItem
import com.xayah.core.restic.ResticRepository
import com.xayah.feature.main.restore.ResticFileBackupGroup
import com.xayah.core.model.ResticProgressState
import com.xayah.core.datastore.readBackupDirectory
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.OpType
import com.xayah.core.model.DataType
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.feature.main.restore.R
import dagger.hilt.android.lifecycle.HiltViewModel
import com.xayah.core.util.toFriendlyMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import java.io.File

sealed class ResticFilesRestoreUiState {
    object Loading : ResticFilesRestoreUiState()
    data class Success(val groups: List<ResticFileBackupGroup>) : ResticFilesRestoreUiState()
    data class Error(val message: String) : ResticFilesRestoreUiState()
}

@HiltViewModel
class ResticFilesRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticRepo: ResticRepository,
    private val rootService: RemoteRootService,
    private val mediaDao: MediaDao,
    private val filesRepo: FilesRepo,
) : ViewModel() {

    companion object {
        private const val TAG = "ResticFilesRestore"

        /** 性能观测专用标签：adb logcat -s RestorePerf */
        private const val PERF_TAG = "RestorePerf"

        /** 媒体恢复队列临时文件：列表页写、服务层读，用于 setup 之后才真正解 media.tar */
        const val MEDIA_RESTORE_QUEUE_FILE = "restic_media_restore_queue.json"
    }

    // 添加进度状态流
    private val _resticProgress = MutableStateFlow(ResticProgressState())
    val resticProgress: StateFlow<ResticProgressState> = _resticProgress.asStateFlow()

    // 速度跟踪变量
    private var lastBytes = 0L
    private var lastTime = System.currentTimeMillis()

    // 性能采样（RestorePerf）：候选开始时间 / 是否已打过首次进度 / 上次采样时间
    @Volatile private var perfCandidateStart = 0L
    @Volatile private var perfFirstLogged = false
    @Volatile private var perfLastLogAt = 0L

    private val _uiState = MutableStateFlow<ResticFilesRestoreUiState>(ResticFilesRestoreUiState.Loading)
    val uiState: StateFlow<ResticFilesRestoreUiState> = _uiState.asStateFlow()

    // 列表聚合模式：true = 同一 (mediaName, fullPath) 只显示一行、点开展示多版本快照；false = 平铺
    private val _groupedMode = MutableStateFlow(true)
    val groupedMode: StateFlow<Boolean> = _groupedMode.asStateFlow()

    fun toggleGroupedMode() {
        _groupedMode.value = !_groupedMode.value
    }

    // 已选 group 的 key 集合：由 ViewModel 持有，详情页往返不清空
    val selectedKeys = mutableStateListOf<String>()

    // 首次加载守卫：正常进入秒开；删除快照返回时走 forceReload 绕过
    private var hasLoaded = false

    fun forceReload() {
        hasLoaded = false
        // force=true：跳过持久缓存，避免删除快照后短暂显示已删除的条目
        loadBackedUpFiles(force = true)
    }

    /**
     * 按全局 OTG 活动标记解出恢复仓库路径与密码。
     * 返回 Pair(repoPath, password)；返回 null 表示应中止（已置 UI 错误态）。
     * OTG 分支：精确 OTG 路径键 + verifyRepository，与备份侧、应用恢复侧判据完全一致；
     * 绝不回退本地（保守中止）。native config_id 恒全 0 不可用，故不再走 resolveCurrentResticRepoPath。
     */
    private suspend fun resolveRestoreRepo(): Pair<String, String>? {
        val activeIsOtg = context.readResticActiveIsOtg()
        if (activeIsOtg) {
            val otgPath = context.readResticOtgRepoPath()
            if (otgPath.isNullOrEmpty()) {
                Log.d(TAG, "resolveRestoreRepo: activeIsOtg=true otgPath=null → 中止")
                _uiState.value = ResticFilesRestoreUiState.Error("未检测到 OTG 仓库，请插入 U 盘后重试")
                return null
            }
            val otgPwd = context.readResticOtgPassword() ?: "databackup_default"
            val ok = runCatching { resticRepo.verifyRepository(otgPath, otgPwd) }.getOrDefault(false)
            Log.d(TAG, "resolveRestoreRepo: activeIsOtg=true repoPath=$otgPath checkOk=$ok")
            if (!ok) {
                _uiState.value = ResticFilesRestoreUiState.Error("未检测到 OTG 仓库，请插入 U 盘后重试")
                return null
            }
            return otgPath to otgPwd
        } else {
            val repoPath = context.readResticRepoPath()
            val password = context.readResticPassword()
            Log.d(TAG, "resolveRestoreRepo: activeIsOtg=false repoPath=$repoPath checkOk=n/a")
            if (repoPath.isNullOrEmpty() || password.isNullOrEmpty()) {
                _uiState.value = ResticFilesRestoreUiState.Error(context.getString(R.string.restore_error_restic_not_configured))
                return null
            }
            return repoPath to password
        }
    }

    // 添加恢复方法
    suspend fun restoreFromResticSnapshots(group: ResticFileBackupGroup): Boolean {
        Log.d(TAG, "开始文件快照恢复流程，媒体名称: ${group.mediaName}")
        return try {
            val (repoPath, password) = resolveRestoreRepo() ?: return false
            val ok = extractGroup(repoPath, password, group, 0, 1)
            _resticProgress.value = if (ok) ResticProgressState(isCompleted = true) else ResticProgressState()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "文件快照恢复异常: ${e.message}", e)
            _resticProgress.value = ResticProgressState()
            false
        }
    }

    /**
     * 批量恢复准备（与 app 恢复侧 ResticRestoreViewModel.prepareBatchRestore 对齐）：
     * 1) 先清空中转目录 restore/files，避免上一批残留的 media_restore_config.json 被
     *    refreshLocalDatabase 重新激活（否则会把上一批选中的媒体一起恢复）；
     * 2) 只解 KB 级 media_restore_config.json 到 restore/files/<mediaName>/，
     *    **不解 media.tar**——重型下载推迟到 setup 之后由服务层按队列执行；
     * 3) 写媒体恢复队列（snapshotId/fullPath/repoPath），刷新媒体库并统计**预期**大小。
     * 返回 true 表示至少有一组准备成功，可以进入处理页。
     */
    suspend fun prepareBatchRestore(groups: List<ResticFileBackupGroup>): Boolean = withContext(Dispatchers.IO) {
        if (groups.isEmpty()) return@withContext false
        val tStart = System.currentTimeMillis()
        Log.d(PERF_TAG, "prepareBatchRestore 开始: groups=${groups.size} media=${groups.map { it.mediaName }}")
        try {
            val restoreBaseDir = "${context.localBackupSaveDir()}/restore/"
            val filesDirPath = "${restoreBaseDir}files"
            // 清理动作放在解析仓库之前：即使仓库不可用（如 OTG 未挂载）也要先清掉上一批残留，
            // 否则残留的 media_restore_config.json 会在下次刷新时被重新激活
            val tClean = System.currentTimeMillis()
            if (File(filesDirPath).exists() || rootService.exists(filesDirPath)) {
                Log.d(TAG, "prepareBatchRestore: 清理上一批中转目录 $filesDirPath")
                rootService.deleteRecursively(filesDirPath)
            }
            Log.d(PERF_TAG, "阶段[清理中转目录] 耗时 ${System.currentTimeMillis() - tClean}ms")

            // 作废上一批队列：任何提前 return 都不能把旧队列留给服务层，
            // 否则服务启动会按上一批的媒体恢复（表现为「第二次恢复把第一次选中的也一起恢复」）。
            runCatching { File(context.cacheDir, MEDIA_RESTORE_QUEUE_FILE).delete() }

            val tRepo = System.currentTimeMillis()
            val (repoPath, password) = resolveRestoreRepo() ?: return@withContext false
            Log.d(PERF_TAG, "阶段[解析仓库] 耗时 ${System.currentTimeMillis() - tRepo}ms repo=$repoPath")

            val succeeded = mutableListOf<ResticFileBackupGroup>()
            groups.forEachIndexed { index, group ->
                Log.d(TAG, "prepareBatchRestore: 准备第 ${index + 1}/${groups.size} 组 ${group.mediaName}")
                val tGroup = System.currentTimeMillis()
                // 只保证 config 落盘（KB 级），**不解 media.tar**：
                // 重型 tar 的下载推迟到 setup 之后由服务层按队列逐条解出，
                // 这样「下一步」只做激活 + 统计预期大小，不会卡在几十秒的下载上。
                val configLanded = isLanded("$filesDirPath/${group.mediaName}/media_restore_config.json")
                val ok = if (configLanded) {
                    Log.d(TAG, "  组 ${group.mediaName} config 已落盘")
                    true
                } else {
                    Log.w(TAG, "  组 ${group.mediaName} config 未落盘，单条补解 config")
                    extractGroup(repoPath, password, group, index, groups.size, onlyConfig = true)
                }
                if (ok) {
                    succeeded.add(group)
                    Log.d(PERF_TAG, "阶段[准备组 ${group.mediaName}] 耗时 ${System.currentTimeMillis() - tGroup}ms 成功")
                } else {
                    Log.e(PERF_TAG, "阶段[准备组 ${group.mediaName}] 耗时 ${System.currentTimeMillis() - tGroup}ms 失败")
                    // 该组失败：清掉半成品，避免被 refreshLocalDatabase 当成可恢复项
                    val groupDir = "$filesDirPath/${group.mediaName}"
                    if (File(groupDir).exists() || rootService.exists(groupDir)) {
                        rootService.deleteRecursively(groupDir)
                    }
                }
            }

            if (succeeded.isEmpty()) {
                Log.e(TAG, "prepareBatchRestore: 所有分组都准备失败")
                Log.e(PERF_TAG, "prepareBatchRestore 全部失败，总耗时 ${System.currentTimeMillis() - tStart}ms")
                _resticProgress.value = ResticProgressState()
                return@withContext false
            }

            // 阶段 C：写媒体恢复队列（含 media.tar 的 snapshotId/fullPath/repoPath），
            // 服务层在 setup 之后据此逐条下载并解包；同时收集预期大小用于 setup 页展示。
            val expectedBytes = mutableMapOf<String, Long>()
            val queue = succeeded.flatMap { group ->
                group.backups.map { backup ->
                    if (backup.dataType == DataType.PACKAGE_MEDIA && backup.totalBytesProcessed > 0) {
                        expectedBytes[group.mediaName] = backup.totalBytesProcessed
                    }
                    ResticMediaRestoreQueueItem(
                        mediaName = group.mediaName,
                        dataType = backup.dataType,
                        snapshotId = backup.snapshotId,
                        fullPath = backup.fullPath,
                        accountName = "",
                        repoPath = repoPath,
                        expectedBytes = backup.totalBytesProcessed,
                    )
                }
            }
            File(context.cacheDir, MEDIA_RESTORE_QUEUE_FILE).writeText(Json.encodeToString(queue))
            Log.d(PERF_TAG, "阶段[写恢复队列] 条目=${queue.size} 预期大小=$expectedBytes")

            val tDb = System.currentTimeMillis()
            refreshLocalDatabase(restoreBaseDir)
            Log.d(PERF_TAG, "阶段[refreshLocalDatabase] 耗时 ${System.currentTimeMillis() - tDb}ms")

            val tSize = System.currentTimeMillis()
            calculateSizesForActivatedMedia(expectedBytes)
            Log.d(PERF_TAG, "阶段[calculateSizes] 耗时 ${System.currentTimeMillis() - tSize}ms")

            _resticProgress.value = ResticProgressState(isCompleted = true)
            Log.d(PERF_TAG, "prepareBatchRestore 成功，总耗时 ${System.currentTimeMillis() - tStart}ms")
            true
        } catch (e: Exception) {
            Log.e(TAG, "prepareBatchRestore 异常: ${e.message}", e)
            Log.e(PERF_TAG, "prepareBatchRestore 异常，总耗时 ${System.currentTimeMillis() - tStart}ms", e)
            _resticProgress.value = ResticProgressState()
            false
        }
    }

    /**
     * 把单个 group 的所有快照解到中转目录 restore/files/<mediaName>/。
     * groupIndex/groupTotal 用于批量恢复时跨组累计进度。
     */
    private suspend fun extractGroup(
        repoPath: String,
        password: String,
        group: ResticFileBackupGroup,
        groupIndex: Int,
        groupTotal: Int,
        onlyConfig: Boolean = false,
    ): Boolean {
        // 按正确顺序排序：MEDIA 优先，CONFIG 最后；
        // onlyConfig=true 时只补解 config（批量未命中时的兜底），不碰 media.tar。
        val sortedBackups = group.backups
            .filter { !onlyConfig || it.dataType == DataType.PACKAGE_CONFIG }
            .sortedBy { backup ->
            when (backup.dataType) {
                DataType.PACKAGE_MEDIA -> 0
                DataType.PACKAGE_CONFIG -> 1
                else -> 2
            }
        }
        val globalTotal = groupTotal * sortedBackups.size
        // 每组重置速度基准，避免跨组算出负速度
        lastBytes = 0L
        lastTime = System.currentTimeMillis()

        sortedBackups.forEachIndexed { index, backup ->
            val globalIndex = groupIndex * sortedBackups.size + index
            Log.d(TAG, "恢复第 ${globalIndex + 1}/$globalTotal 个数据类型: ${backup.dataType.type}")
            _resticProgress.value = _resticProgress.value.copy(
                currentDataTypeIndex = globalIndex,
                totalDataTypes = globalTotal
            )

            // 为当前 backup 创建专用的进度回调
            val progressCallback = object : ResticRepository.ResticProgressCallback {
                override fun onRestoreProgress(
                    filesFinished: Long,
                    filesTotal: Long,
                    bytesWritten: Long,
                    bytesTotal: Long,
                    filesSkipped: Long,
                    bytesSkipped: Long
                ) {
                    // 恢复进度处理
                    val progress = if (bytesTotal > 0) {
                        bytesWritten.toFloat() / bytesTotal
                    } else 0f

                    // 瞬时速度计算
                    val currentTime = System.currentTimeMillis()
                    val timeDiff = currentTime - lastTime
                    val speed = if (timeDiff > 0 && bytesWritten > lastBytes) {
                        ((bytesWritten - lastBytes) * 1000 / timeDiff).formatSpeed()
                    } else "0 B/s"

                    lastTime = currentTime
                    lastBytes = bytesWritten

                    // 速度计算和状态更新
                    _resticProgress.value = ResticProgressState(
                        filesFinished = filesFinished,
                        filesTotal = filesTotal,
                        filesSkipped = filesSkipped,
                        bytesWritten = bytesWritten,
                        bytesTotal = bytesTotal,
                        bytesSkipped = bytesSkipped,
                        percentage = progress,
                        speed = speed,
                        currentDataTypeIndex = globalIndex,
                        totalDataTypes = globalTotal
                    )

                    // RestorePerf 采样：首次回调 ≈ 仓库打开 + index 加载 + plan 完成（固定开销）；
                    // 之后每秒一行，用 bytesWritten/bytesTotal 判断瓶颈在读取还是解压。
                    if (!perfFirstLogged) {
                        perfFirstLogged = true
                        Log.d(
                            PERF_TAG,
                            "  首次进度回调 耗时 ${currentTime - perfCandidateStart}ms " +
                                "bytesTotal=$bytesTotal filesTotal=$filesTotal"
                        )
                    }
                    if (currentTime - perfLastLogAt >= 1000) {
                        perfLastLogAt = currentTime
                        Log.d(
                            PERF_TAG,
                            "  进度 ${String.format("%.1f%%", progress * 100)} " +
                                "已读 $bytesWritten/$bytesTotal 速度 $speed"
                        )
                    }
                }

                override fun onBackupProgress(
                    percentDone: Float, bytesDone: Long,
                    bytesTotal: Long, filesDone: Long, filesTotal: Long,
                    speed: Long
                ) {
                    // restore 相关 VM 里 onBackupProgress 备份时不触发，保留空实现
                }
            }

            val targetPath = "${context.localBackupSaveDir()}/restore/"
            val includePath = when (backup.dataType) {
                DataType.PACKAGE_MEDIA -> "media.tar"
                DataType.PACKAGE_CONFIG -> "media_restore_config.json"
                else -> "${backup.dataType.type}.tar"
            }
            val fullTargetPath = "${targetPath}files/${group.mediaName}/"
            Log.d(TAG, "恢复 ${backup.dataType.type} 到目标: $fullTargetPath, 包含文件: $includePath")

            val success = restoreSnapshotWithFallback(
                repoPath = repoPath,
                password = password,
                snapshotId = backup.snapshotId,
                fullTargetPath = fullTargetPath,
                includePath = includePath,
                mediaName = group.mediaName,
                progressCallback = progressCallback
            )

            if (!success) {
                Log.e(TAG, "恢复失败: ${backup.dataType.type}, 快照ID: ${backup.snapshotId}")
                _resticProgress.value = ResticProgressState()
                return false
            }
            Log.d(TAG, "恢复成功: ${backup.dataType.type}")
        }
        return true
    }

    /**
     * 三候选回退恢复：解决「默认备份目录从 DataBackup 迁到 DataBackuptmp 后，
     * 旧中转模式 media 快照里登记的归档前缀是旧共享目录」导致的 node_from_snapshot_path 命不中
     * （JNI 侧抛 internal operations → restoreSnapshot 捕获后返回 false）。
     *
     * 候选顺序（与应用恢复端 ResticRestoreViewModel.restoreSnapshotWithFallback 对齐）：
     *   1) current ：$backupBaseDir/files/<media>        —— 迁移后=DataBackuptmp，兼容自定义 backup_directory
     *   2) legacy  ：LEGACY_SHARED_PATH/files/<media>     —— 旧共享目录(/storage/emulated/0/DataBackup)，旧中转快照
     *   3) null    ：裸文件名快照                          —— 新 fd 流式 media 快照
     *
     * 命中判据：restoreSnapshot 返回 true 且目标文件确实落盘（includePath 对应文件 exists）。
     * 候选之间不会写半成品——node 未命中时 native 不落盘，所以无需候选间清理。
     */
    private suspend fun restoreSnapshotWithFallback(
        repoPath: String,
        password: String,
        snapshotId: String,
        fullTargetPath: String,
        includePath: String,
        mediaName: String,
        progressCallback: ResticRepository.ResticProgressCallback?
    ): Boolean {
        val currentBase = context.readBackupDirectory() ?: context.localBackupSaveDir()
        val candidates: List<String?> = listOf(
            "$currentBase/files/$mediaName",                               // 候选1：当前目录
            "${ConstantUtil.LEGACY_SHARED_PATH}/files/$mediaName",        // 候选2：旧共享目录
            null                                                          // 候选3：裸名（新 fd 流式）
        )

        val landedFile = File(fullTargetPath, includePath)
        candidates.forEachIndexed { idx, subPath ->
            Log.d(TAG, "restoreSnapshotWithFallback: 候选${idx + 1} subPath=${subPath ?: "<null/裸名>"} include=$includePath")
            val tCandidate = System.currentTimeMillis()
            perfCandidateStart = tCandidate
            perfFirstLogged = false
            perfLastLogAt = 0L
            val ok = runCatching {
                resticRepo.restoreSnapshot(
                    repoPath = repoPath,
                    password = password,
                    snapshotId = snapshotId,
                    targetPath = fullTargetPath,
                    includePath = includePath,
                    snapshotSubPath = subPath,
                    progressCallback = progressCallback
                )
            }.getOrElse {
                Log.e(TAG, "restoreSnapshotWithFallback: 候选${idx + 1} 异常", it)
                false
            }
            val elapsed = System.currentTimeMillis() - tCandidate
            // 落盘判定必须双视角：app 进程在共享目录/OTG 上可能无读权限（exists 恒 false），
            // 而写入方是 root 守护进程。只用 File.exists() 会把「已成功」误判为未命中，
            // 于是继续跑下一个候选 → 同一份 media.tar 被重复解压 N 次（这就是「处理中」很慢的根因）。
            val landedByApp = landedFile.exists()
            val landed = landedByApp || rootService.exists(landedFile.path)
            Log.d(
                PERF_TAG,
                "候选${idx + 1}/${candidates.size} subPath=${subPath ?: "<裸名>"} 耗时 ${elapsed}ms ok=$ok landed=$landed(app=$landedByApp)"
            )
            if (ok && landed) {
                Log.d(TAG, "restoreSnapshotWithFallback: 候选${idx + 1} 命中并落盘 ${landedFile.path}")
                return true
            }
            Log.w(TAG, "restoreSnapshotWithFallback: 候选${idx + 1} 未命中（ok=$ok, landed=$landed），尝试下一个")
        }
        Log.e(TAG, "restoreSnapshotWithFallback: 所有候选都未命中 media=$mediaName include=$includePath")
        return false
    }

    suspend fun deleteLocalFileSnapshots(group: ResticFileBackupGroup): Boolean = withContext(Dispatchers.IO) {
        try {
            val (repoPath, password) = resolveRestoreRepo() ?: return@withContext false

            // 文件备份只有 MEDIA 和 CONFIG 两种类型
            val sortedBackups = group.backups.sortedBy { backup ->
                when (backup.dataType) {
                    DataType.PACKAGE_MEDIA -> 0
                    DataType.PACKAGE_CONFIG -> 1
                    else -> 2
                }
            }

            val totalSteps = sortedBackups.size + 1

            _resticProgress.value = ResticProgressState(
                totalDataTypes = totalSteps,
                currentDataTypeIndex = 0,
                isDeleting = true
            )

            // 逐个删除快照
            sortedBackups.forEachIndexed { index, backup ->
                Log.d("ResticFilesRestore", "删除第 ${index + 1}/${sortedBackups.size} 个快照: ${backup.dataType.type}")

                _resticProgress.value = _resticProgress.value.copy(
                    currentDataTypeIndex = index
                )

                val success = resticRepo.forgetSnapshot(
                    repoPath = repoPath,
                    password = password,
                    snapshotId = backup.snapshotId
                )

                if (!success) {
                    _resticProgress.value = ResticProgressState()
                    return@withContext false
                }
            }

            // 最后一步: 执行 prune
            Log.d("ResticFilesRestore", "执行 prune 清理 (步骤 ${totalSteps}/${totalSteps})")
            _resticProgress.value = _resticProgress.value.copy(
                currentDataTypeIndex = sortedBackups.size
            )

            val pruneSuccess = resticRepo.pruneRepository(repoPath, password)
            _resticProgress.value = ResticProgressState()

            pruneSuccess
        } catch (e: Exception) {
            Log.e("ResticFilesRestore", "删除本地文件快照异常: ${e.message}", e)
            _resticProgress.value = ResticProgressState()
            false
        }
    }

    suspend fun refreshLocalDatabase(backupDir: String) {
        Log.d("ResticFilesRestore", "刷新媒体数据库: $backupDir")
        try {
            // 删除所有旧的恢复记录
            val restoreDir = "${context.localBackupSaveDir()}/restore/"
            val tDel = System.currentTimeMillis()
            mediaDao.deleteByOpTypeAndBackupDir(OpType.RESTORE, restoreDir)
            Log.d(PERF_TAG, "  refreshLocalDatabase[删除旧记录] 耗时 ${System.currentTimeMillis() - tDel}ms")

            val restoreDirFile = File(backupDir)
            // 目录存在性同样要 root 兜底：app 进程在共享目录/OTG 上可能读不到
            if (restoreDirFile.exists() || rootService.exists(backupDir)) {
                Log.d("ResticFilesRestore", "开始扫描恢复目录: $backupDir")

                val filesDir = File(restoreDirFile, "files")
                if (filesDir.exists() || rootService.exists(filesDir.path)) {
                    scanFilesDirectory(filesDir)  // 修复：使用正确的扫描方法
                } else {
                    Log.w("ResticFilesRestore", "files 目录不存在: ${filesDir.path}")
                }
            } else {
                Log.w(PERF_TAG, "  refreshLocalDatabase: 恢复目录不存在 $backupDir")
            }
        } catch (e: Exception) {
            Log.e("ResticFilesRestore", "刷新数据库失败: ${e.message}", e)
        }
    }

    private suspend fun updateFilesDatabase(mediaName: String) {
        Log.d("ResticFilesRestore", "激活媒体: $mediaName")
        try {
            // 查询该媒体名称的所有记录
            val existingMedia = mediaDao.query(OpType.RESTORE, "", "${context.localBackupSaveDir()}/restore/")
                .filter { it.name == mediaName }

            if (existingMedia.isNotEmpty()) {
                existingMedia.forEach { media ->
                    mediaDao.activateById(media.id, true)
                    Log.d("ResticFilesRestore", "媒体已激活: $mediaName (ID: ${media.id})")
                }
            } else {
                Log.w("ResticFilesRestore", "未找到媒体记录: $mediaName")
            }
        } catch (e: Exception) {
            Log.e("ResticFilesRestore", "激活媒体失败: ${e.message}", e)
        }
    }

    private suspend fun scanFilesDirectory(filesDir: File) {
        val tScan = System.currentTimeMillis()
        // app 进程 listFiles 在共享目录/OTG 上可能返回 null（无读权限），此时用 root 侧列目录兜底
        val appDirs = filesDir.listFiles { file -> file.isDirectory }
        val mediaNames: List<String> = if (!appDirs.isNullOrEmpty()) {
            appDirs.map { it.name }
        } else {
            rootService.listFilePaths(filesDir.path, listFiles = false, listDirs = true)
                .map { it.trimEnd('/').substringAfterLast('/') }
                .filter { it.isNotEmpty() }
                .also { Log.d(PERF_TAG, "  scanFilesDirectory: app 列目录为空，改用 root 列出 ${it.size} 个目录") }
        }
        Log.d(PERF_TAG, "  scanFilesDirectory: 待扫描媒体目录 ${mediaNames.size} 个，列目录耗时 ${System.currentTimeMillis() - tScan}ms")

        mediaNames.forEach { mediaName ->
            val mediaDir = File(filesDir, mediaName)
            val configFile = File(mediaDir, "media_restore_config.json")
            if (configFile.exists() || rootService.exists(configFile.path)) {
                try {
                    // 读取配置文件并更新数据库
                    val mediaEntity = readMediaConfig(configFile, mediaName)
                    if (mediaEntity != null) {
                        mediaDao.upsert(mediaEntity)
                        Log.d("ResticFilesRestore", "媒体已插入数据库: $mediaName")
                        updateFilesDatabase(mediaName)
                        Log.d("ResticFilesRestore", "发现恢复的媒体: $mediaName")
                    }
                } catch (e: Exception) {
                    Log.e("ResticFilesRestore", "处理媒体配置失败: ${configFile.path}", e)
                }
            } else {
                Log.w(PERF_TAG, "  scanFilesDirectory: $mediaName 缺少 media_restore_config.json，跳过")
            }
        }
        Log.d(PERF_TAG, "  scanFilesDirectory 总耗时 ${System.currentTimeMillis() - tScan}ms")
    }

    suspend fun calculateSizesForActivatedMedia(expectedBytes: Map<String, Long> = emptyMap()) {
        try {
            Log.d("ResticFilesRestore", "=== 开始计算激活媒体的大小 ===")
            val tSize = System.currentTimeMillis()
            val backupDir = "${context.localBackupSaveDir()}/restore/"
            val activatedMedia = mediaDao.queryActivated(OpType.RESTORE, "", backupDir)

            Log.d("ResticFilesRestore", "找到 ${activatedMedia.size} 个已激活媒体")
            activatedMedia.forEach { media ->
                Log.d("ResticFilesRestore", "计算媒体大小: ${media.name}, 路径: ${media.path}")

                // 优先用已落盘的 media.tar 实际大小；未下载时回退到快照元数据里的预期大小
                // （列表页「下一步」不再下载 media.tar，此时只能靠 totalBytesProcessed 估算）。
                val mediaFile = File("${backupDir}files/${media.name}/media.tar")
                val exists = mediaFile.exists() || rootService.exists(mediaFile.path)
                val size = if (exists) mediaFile.length() else (expectedBytes[media.name] ?: 0L)
                Log.d("ResticFilesRestore", "媒体文件存在: $exists, 大小: $size")

                if (size > 0) {
                    media.mediaInfo.displayBytes = size
                    mediaDao.upsert(media)
                    Log.d("ResticFilesRestore", "大小计算完成: ${media.displayStatsBytes}")
                } else {
                    Log.w("ResticFilesRestore", "媒体文件不存在且无预期大小: ${mediaFile.absolutePath}")
                }
            }

            Log.d(PERF_TAG, "  calculateSizesForActivatedMedia 耗时 ${System.currentTimeMillis() - tSize}ms")
            Log.d("ResticFilesRestore", "=== 激活媒体大小计算完成 ===")
        } catch (e: Exception) {
            Log.e("ResticFilesRestore", "计算大小失败", e)
        }
    }

    /** 双视角判存在：app 进程在共享目录/OTG 上可能读不到，写入方是 root 守护进程 */
    private suspend fun isLanded(path: String): Boolean {
        val f = File(path)
        return f.exists() || rootService.exists(f.path)
    }

    suspend fun readBackupDirectory(): String {
        Log.d("ResticFilesRestore", "从 DataStore 读取备份目录配置")
        val backupDir = context.localBackupSaveDir()
        Log.d("ResticFilesRestore", "读取到的备份目录: $backupDir")
        return backupDir ?: throw Exception("备份目录未配置")
    }

    private fun Long.formatSpeed(): String {
        return when {
            this < 1024 -> "$this B/s"
            this < 1024 * 1024 -> "${this / 1024} KiB/s"
            this < 1024 * 1024 * 1024 -> "${this / (1024 * 1024)} MiB/s"
            else -> "${this / (1024 * 1024 * 1024)} GiB/s"
        }
    }

    private suspend fun readMediaConfig(configFile: File, mediaName: String): MediaEntity? {
        return try {
            val entity = rootService.readJson<MediaEntity>(configFile.path)
            entity?.copy(
                id = 0,
                indexInfo = entity.indexInfo.copy(
                    opType = OpType.RESTORE,
                    name = mediaName,
                    cloud = "",
                    backupDir = "${context.localBackupSaveDir()}/restore/"
                ),
                extraInfo = entity.extraInfo.copy(activated = false)
            )
        } catch (e: Exception) {
            Log.e("ResticFilesRestore", "读取媒体配置文件失败: ${configFile.path}", e)
            null
        }
    }

    fun loadBackedUpFiles(force: Boolean = false) {
        if (hasLoaded && !force) return
        Log.d("ResticFilesRestore", "=== loadBackedUpFiles 开始 ===")

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val accountId = if (context.readResticActiveIsOtg()) "otg" else "local"

            // ---- 阶段一：读持久缓存，命中秒开（不闪 Loading）----
            // force=true（删除快照返回）时跳过缓存，避免短暂显示已删除的条目。
            val cached = if (force) emptyList()
            else runCatching { ResticFilesCache.read(context, accountId) }.getOrElse { emptyList() }
            if (cached.isNotEmpty()) {
                hasLoaded = true
                _uiState.value = ResticFilesRestoreUiState.Success(groupFiles(cached))
                Log.d("ResticFilesRestore", "缓存命中，秒开 ${cached.size} 条")
            } else {
                _uiState.value = ResticFilesRestoreUiState.Loading
                Log.d("ResticFilesRestore", "UI状态设置为Loading")
            }

            // ---- 阶段二：后台重建，完成后静默替换 ----
            try {
                Log.d("ResticFilesRestore", "读取 Restic 配置（OTG 感知）")
                val repo = resolveRestoreRepo()
                if (repo == null) {
                    // resolveRestoreRepo 已置错误态；若手上有缓存则保留缓存，不让错误覆盖
                    if (cached.isNotEmpty()) _uiState.value = ResticFilesRestoreUiState.Success(groupFiles(cached))
                    return@launch
                }
                val (repoPath, password) = repo
                Log.d("ResticFilesRestore", "Restic配置读取成功")

                Log.d("ResticFilesRestore", "开始调用 listBackedUpFiles")
                val files = resticRepo.listBackedUpFiles(repoPath, password)
                Log.d("ResticFilesRestore", "listBackedUpFiles 返回 ${files.size} 个文件备份项")

                // 落盘缓存：下次进入直接秒开
                ResticFilesCache.write(context, accountId, files)

                val groupedBackups = groupFiles(files)
                val duration = System.currentTimeMillis() - startTime
                Log.d("ResticFilesRestore", "文件备份分组完成，共 ${groupedBackups.size} 个组，耗时: ${duration}ms")
                hasLoaded = true
                // 与当前列表比对：内容一致就不重发，避免列表无谓重组与滚动位置跳动
                val current = (_uiState.value as? ResticFilesRestoreUiState.Success)?.groups
                if (current != groupedBackups) {
                    _uiState.value = ResticFilesRestoreUiState.Success(groupedBackups)
                }
            } catch (e: Exception) {
                Log.e("ResticFilesRestore", "加载本地文件备份时发生异常", e)
                if (cached.isEmpty()) {
                    _uiState.value = ResticFilesRestoreUiState.Error(context.getString(R.string.restore_load_failed_reason, e.toFriendlyMessage(context)))
                } else {
                    Log.w("ResticFilesRestore", "后台刷新失败，保留缓存: ${e.message}")
                }
            }
        }
        Log.d("ResticFilesRestore", "=== loadBackedUpFiles 结束 ===")
    }

    /** 把扁平的文件备份项按 (mediaName, 前缀路径, 时间戳) 聚合成列表行。 */
    private fun groupFiles(files: List<ResticBackupFiles>): List<ResticFileBackupGroup> =
        files
            .groupBy {
                val prefixPath = it.fullPath.substringBeforeLast("/")
                Triple(it.mediaName, prefixPath, it.timestamp)
            }
            .map { (groupKey, backupsInGroup) ->
                val (mediaName, prefixPath, timestamp) = groupKey
                ResticFileBackupGroup(
                    mediaName = mediaName,
                    fullPath = prefixPath,
                    timestamp = timestamp,
                    backups = backupsInGroup.sortedBy { backup ->
                        when (backup.dataType) {
                            DataType.PACKAGE_MEDIA -> 0
                            DataType.PACKAGE_CONFIG -> 1
                            else -> 2
                        }
                    },
                    mediaLabel = mediaName
                )
            }
            .sortedByDescending { it.timestamp }
}