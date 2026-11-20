package com.xayah.feature.main.restore

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.datastore.readBackupDirectory
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.model.DataType
import com.xayah.core.model.CloudType
import com.xayah.core.model.OpType
import com.xayah.core.model.ResticProgressState
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.restic.ResticBackupFiles
import com.xayah.core.model.restic.ResticMediaRestoreQueueItem
import com.xayah.core.restic.CloudResticBackend
import com.xayah.core.restic.ResticRepository
import com.xayah.core.restic.RestoreBatchItem
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.decodeURL
import com.xayah.feature.main.restore.R
import dagger.hilt.android.lifecycle.HiltViewModel
import com.xayah.core.util.toFriendlyMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import java.io.File

@HiltViewModel
class CloudFilesRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resticBackends: Map<CloudType, @JvmSuppressWildcards CloudResticBackend>,
    private val mediaDao: MediaDao,
    private val filesRepo: FilesRepo,
    private val rootService: RemoteRootService,
    private val cloudRepo: CloudRepository
) : ViewModel() {

    companion object {
        private const val TAG = "CloudFilesRestore"

        /** 性能观测专用标签：adb logcat -s RestorePerf */
        private const val PERF_TAG = "RestorePerf"

        /** 媒体恢复队列临时文件：列表页写、服务层读，用于 setup 之后才真正解 media.tar */
        const val MEDIA_RESTORE_QUEUE_FILE = "restic_media_restore_queue.json"
    }

    private var lastBytes = 0L
    private var lastTime = System.currentTimeMillis()
    private var accountName: String = ""

    // 性能采样（RestorePerf）：候选开始时间 / 是否已打过首次进度 / 上次采样时间
    @Volatile private var perfCandidateStart = 0L
    @Volatile private var perfFirstLogged = false
    @Volatile private var perfLastLogAt = 0L

    /** 已成功加载过的账户名：返回列表页时避免重复拉取（forceReload 会清空） */
    private var loadedAccount: String? = null

    /** 列表聚合模式：true = 同一 (mediaName, fullPath) 只显示一行；false = 平铺 */
    private val _groupedMode = MutableStateFlow(true)
    val groupedMode: StateFlow<Boolean> = _groupedMode.asStateFlow()

    /** 已选 group 的 key 集合（"mediaName-fullPath-timestamp"），由 VM 持有，详情页往返不清空 */
    val selectedKeys = mutableStateListOf<String>()

    fun toggleGroupedMode() {
        _groupedMode.value = !_groupedMode.value
    }

    private val _uiState = MutableStateFlow<CloudFilesRestoreUiState>(CloudFilesRestoreUiState.Loading)
    val uiState: StateFlow<CloudFilesRestoreUiState> = _uiState.asStateFlow()

    private val _resticProgress = MutableStateFlow(ResticProgressState())
    val resticProgress: StateFlow<ResticProgressState> = _resticProgress.asStateFlow()

    /** 按 CloudType 取对应后端；找不到 key 抛 NoSuchElementException（fail-fast，替代原 else -> COS 兜底） */
    private fun backend(cloudEntity: CloudEntity): CloudResticBackend =
        resticBackends.getValue(cloudEntity.type)

    /** 按账户类型解析 restic 仓库密码：账户级(extra) 优先，回落 DataStore 全局值 */
    private suspend fun resolveResticPassword(cloudEntity: CloudEntity): String? {
        return backend(cloudEntity).resolveResticPassword(cloudEntity)
    }

    fun setCloudEntity(accountName: String) {
        Log.d(TAG, "=== setCloudEntity 开始 ===")
        Log.d(TAG, "原始账户名: $accountName")

        val cleanAccountName = accountName.replace("accountName=", "").decodeURL()
        if (this.accountName != cleanAccountName) {
            // 切换账户：清空上一账户的勾选，避免脏选择
            selectedKeys.clear()
        }
        this.accountName = cleanAccountName
        Log.d(TAG, "清理后账户名: $cleanAccountName")

        // 同一账户已加载过：直接复用列表（返回列表页时不再重复拉取）
        if (loadedAccount == cleanAccountName) {
            Log.d(TAG, "账户 $cleanAccountName 已加载，跳过重复拉取")
            return
        }

        viewModelScope.launch {
            Log.d(TAG, "开始查询云端账户: $cleanAccountName")
            try {
                val cloudEntity = cloudRepo.queryByName(cleanAccountName)
                if (cloudEntity != null) {
                    Log.d(TAG, "成功找到云端账户: ${cloudEntity.name}, 类型: ${cloudEntity.type}, 远程路径: ${cloudEntity.remote}")
                    loadCloudBackedUpFiles(cloudEntity)
                } else {
                    Log.e(TAG, "云端账户查询失败: $cleanAccountName")
                    _uiState.value = CloudFilesRestoreUiState.Error(context.getString(R.string.restore_account_not_found, cleanAccountName))
                }
            } catch (e: Exception) {
                Log.e(TAG, "查询云端账户时发生异常: $cleanAccountName", e)
                _uiState.value = CloudFilesRestoreUiState.Error(context.getString(R.string.restore_query_account_failed, e.toFriendlyMessage(context)))
            }
        }
        Log.d(TAG, "=== setCloudEntity 结束 ===")
    }

    /** 强制重新拉取（删除快照返回列表时用，绕过已加载守卫） */
    fun forceReload() {
        val name = accountName
        if (name.isEmpty()) return
        loadedAccount = null
        viewModelScope.launch {
            val cloudEntity = runCatching { cloudRepo.queryByName(name) }.getOrNull()
            if (cloudEntity == null) {
                _uiState.value = CloudFilesRestoreUiState.Error(
                    context.getString(R.string.restore_account_not_found, name)
                )
                return@launch
            }
            loadCloudBackedUpFiles(cloudEntity, force = true)
        }
    }

    fun loadCloudBackedUpFiles(cloudEntity: CloudEntity, force: Boolean = false) {
        Log.d(TAG, "=== loadCloudBackedUpFiles 开始 ===")
        Log.d(TAG, "云端实体: ${cloudEntity.name}, 远程路径: ${cloudEntity.remote}")

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val accountId = cloudEntity.name

            // ---- 阶段一：读持久缓存，命中秒开（不闪 Loading）----
            // force=true（删除快照返回）时跳过缓存，避免短暂显示已删除的条目。
            val cached = if (force) emptyList()
            else runCatching { ResticFilesCache.read(context, accountId) }.getOrElse { emptyList() }
            if (cached.isNotEmpty()) {
                loadedAccount = accountId
                _uiState.value = CloudFilesRestoreUiState.Success(groupFiles(cached))
                Log.d(TAG, "缓存命中，秒开 ${cached.size} 条")
            } else {
                _uiState.value = CloudFilesRestoreUiState.Loading
                Log.d(TAG, "UI状态设置为Loading")
            }

            // ---- 阶段二：后台重建，完成后静默替换 ----
            try {
                Log.d(TAG, "读取 Restic 密码配置")
                val password = resolveResticPassword(cloudEntity)
                if (password.isNullOrEmpty()) {
                    Log.e(TAG, "Restic密码未配置或为空")
                    if (cached.isEmpty()) {
                        _uiState.value = CloudFilesRestoreUiState.Error(context.getString(R.string.restore_password_not_configured))
                    }
                    return@launch
                }
                Log.d(TAG, "Restic密码配置已读取 (长度: ${password.length})")

                Log.d(TAG, "开始调用 listBackedUpFiles (JNI 模式), type=${cloudEntity.type}")
                val files: List<ResticBackupFiles> = backend(cloudEntity).listBackedUpFiles(cloudEntity, password)

                Log.d(TAG, "获取到 ${files.size} 个文件备份项")

                // 落盘缓存：下次进入直接秒开
                ResticFilesCache.write(context, accountId, files)

                val groupedBackups = groupFiles(files)
                val duration = System.currentTimeMillis() - startTime
                Log.d(TAG, "文件备份分组完成，共 ${groupedBackups.size} 个组，耗时: ${duration}ms")
                loadedAccount = accountId
                // 与当前列表比对：内容一致就不重发，避免列表无谓重组与滚动位置跳动
                val current = (_uiState.value as? CloudFilesRestoreUiState.Success)?.groups
                if (current != groupedBackups) {
                    _uiState.value = CloudFilesRestoreUiState.Success(groupedBackups)
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载云端文件备份时发生异常", e)
                if (cached.isEmpty()) {
                    _uiState.value = CloudFilesRestoreUiState.Error(context.getString(R.string.restore_load_failed_reason, e.toFriendlyMessage(context)))
                } else {
                    Log.w(TAG, "后台刷新失败，保留缓存: ${e.message}")
                }
            }
        }
        Log.d(TAG, "=== loadCloudBackedUpFiles 结束 ===")
    }

    /** 把扁平的文件备份项按 (mediaName, 前缀路径, 时间戳) 聚合成列表行。 */
    private fun groupFiles(files: List<ResticBackupFiles>): List<ResticFileBackupGroup> =
        files
            .groupBy {
                // 提取前缀路径（去掉最后一层），与本地版本保持一致
                val prefixPath = it.fullPath.substringBeforeLast("/")
                Triple(it.mediaName, prefixPath, it.timestamp)
            }
            .map { (groupKey, backupsInGroup) ->
                val (mediaName, prefixPath, timestamp) = groupKey
                ResticFileBackupGroup(
                    mediaName = mediaName,
                    fullPath = prefixPath, // 使用前缀路径，与本地版本保持一致
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

    suspend fun deleteCloudFileSnapshots(group: ResticFileBackupGroup): Boolean = withContext(Dispatchers.IO) {
        try {
            val cloudEntity = cloudRepo.queryByName(accountName) ?: return@withContext false
            val password = resolveResticPassword(cloudEntity) ?: return@withContext false
            val backend = backend(cloudEntity)

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

            sortedBackups.forEachIndexed { index, backup ->
                _resticProgress.value = _resticProgress.value.copy(currentDataTypeIndex = index)

                val success = backend.forgetSnapshot(
                    cloudEntity = cloudEntity, password = password, snapshotId = backup.snapshotId
                )

                if (!success) {
                    _resticProgress.value = ResticProgressState()
                    return@withContext false
                }
            }

            _resticProgress.value = _resticProgress.value.copy(currentDataTypeIndex = sortedBackups.size)

            val pruneSuccess = backend.pruneRepository(cloudEntity, password)
            _resticProgress.value = ResticProgressState()

            pruneSuccess
        } catch (e: Exception) {
            Log.e(TAG, "删除文件快照异常: ${e.message}", e)
            _resticProgress.value = ResticProgressState()
            false
        }
    }

    suspend fun restoreFromCloudFileSnapshots(group: ResticFileBackupGroup): Boolean {
        Log.d(TAG, "=== restoreFromCloudFileSnapshots 开始 ===")
        Log.d(TAG, "恢复组: ${group.mediaName}, 时间戳: ${group.timestamp}, 备份数量: ${group.backups.size}")

        return withContext(Dispatchers.IO) {
            try {
                val cloudEntity = cloudRepo.queryByName(accountName)
                if (cloudEntity == null) {
                    Log.e(TAG, "云端账户不存在: $accountName")
                    return@withContext false
                }
                val password = resolveResticPassword(cloudEntity)
                if (password.isNullOrEmpty()) {
                    Log.e(TAG, "Restic密码为空")
                    return@withContext false
                }
                val ok = extractGroup(cloudEntity, backend(cloudEntity), password, group, 0, 1)
                _resticProgress.value = if (ok) ResticProgressState(isCompleted = true) else ResticProgressState()
                ok
            } catch (e: Exception) {
                Log.e(TAG, "云端文件恢复异常: ${e.message}", e)
                _resticProgress.value = ResticProgressState()
                false
            }
        }
    }

    /**
     * 批量恢复准备（与 app 恢复侧 CloudRestoreViewModel.prepareBatchRestore 对齐）：
     * 1) 先清空中转目录 restore/files，避免上一批残留的 media_restore_config.json 被
     *    refreshLocalDatabase 重新激活（否则会把上一批选中的媒体一起恢复）；
     * 2) 批量解 KB 级 media_restore_config.json 到 restore/files/<mediaName>/，
     *    **不解 media.tar**——重型下载推迟到 setup 之后由服务层按队列执行；
     * 3) 写媒体恢复队列（snapshotId/fullPath/accountName），刷新媒体库并统计**预期**大小。
     * 返回 true 表示至少有一组准备成功，可以进入处理页。
     */
    suspend fun prepareBatchRestore(groups: List<ResticFileBackupGroup>): Boolean = withContext(Dispatchers.IO) {
        if (groups.isEmpty()) return@withContext false
        val tStart = System.currentTimeMillis()
        Log.d(PERF_TAG, "prepareBatchRestore 开始: groups=${groups.size} media=${groups.map { it.mediaName }}")
        try {
            val restoreBaseDir = "${context.localBackupSaveDir()}/restore/"
            val filesDirPath = "${restoreBaseDir}files"
            // 清理动作放在解析账户之前：即使账户/仓库不可用也要先清掉上一批残留
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
            val cloudEntity = cloudRepo.queryByName(accountName) ?: return@withContext false
            val password = resolveResticPassword(cloudEntity)
            if (password.isNullOrEmpty()) {
                Log.e(TAG, "prepareBatchRestore: Restic 密码为空")
                return@withContext false
            }
            val backend = backend(cloudEntity)
            Log.d(PERF_TAG, "阶段[解析账户/密码] 耗时 ${System.currentTimeMillis() - tRepo}ms account=${cloudEntity.name}")

            val succeeded = mutableListOf<ResticFileBackupGroup>()

            // 阶段 A：批量解 config。一次打开仓库解 N 个 KB 级 config，
            // 把 N 次 open_repository + index 加载的固定开销摊薄成 1 次。
            // 用 groups.map（而非 mapNotNull）保证下标与 groups 严格对齐；
            // 没有 config 的组传空 snapshotId，Rust 侧该条直接失败，不影响其他条目。
            val configItems = groups.map { group ->
                val configBackup = group.backups.firstOrNull { it.dataType == DataType.PACKAGE_CONFIG }
                // 关键：快照里存的是创建时的绝对路径（sourcePaths[0]），裸 id 会让 ls 从快照根
                // 列出 storage/emulated/0/... 整棵树，解到目标目录后变成嵌套错位。
                // 用 backup.fullPath 拼成 "id:path" 精确命中，与单条 restoreSnapshot 的
                // "$snapshotId:$snapshotSubPath" 完全一致。
                val subPath = configBackup?.fullPath?.takeIf { it.isNotBlank() && it != "/" }
                RestoreBatchItem(
                    snapshotId = when {
                        configBackup == null -> ""
                        subPath != null -> "${configBackup.snapshotId}:$subPath"
                        else -> configBackup.snapshotId
                    },
                    destinationPath = "$filesDirPath/${group.mediaName}/",
                    includeGlob = "media_restore_config.json",
                )
            }
            val tBatch = System.currentTimeMillis()
            val batchResults = if (configItems.isNotEmpty()) {
                backend.restoreSnapshotsBatch(cloudEntity, password, configItems)
            } else {
                emptyList()
            }
            Log.d(
                PERF_TAG,
                "阶段[批量解 config] 耗时 ${System.currentTimeMillis() - tBatch}ms " +
                    "items=${configItems.size} results=$batchResults " +
                    "subPaths=${configItems.map { it.snapshotId.substringAfter(':', "<bare>") }}"
            )

            // 阶段 B：只保证 config 落盘（KB 级），**不解 media.tar**。
            // 重型 tar 的下载推迟到 setup 之后由服务层按队列逐条解出，
            // 这样「下一步」只做激活 + 统计预期大小，不会卡在几十秒的下载上。
            groups.forEachIndexed { index, group ->
                Log.d(TAG, "prepareBatchRestore: 准备第 ${index + 1}/${groups.size} 组 ${group.mediaName}")
                val tGroup = System.currentTimeMillis()
                val configLanded = isLanded("$filesDirPath/${group.mediaName}/media_restore_config.json")
                val batchOk = batchResults.getOrNull(index) == true && configLanded
                val ok = if (batchOk) {
                    Log.d(TAG, "  组 ${group.mediaName} config 已批量落盘")
                    true
                } else {
                    Log.w(TAG, "  组 ${group.mediaName} config 批量未命中，单条补解 config")
                    // 批量若解到了错误层级（裸 id 会把绝对路径树整体解出），先清掉残留再补解，
                    // 否则 refreshLocalDatabase 会把嵌套目录当成可恢复项。
                    val groupDir = "$filesDirPath/${group.mediaName}"
                    if (File(groupDir).exists() || rootService.exists(groupDir)) {
                        Log.w(TAG, "  组 ${group.mediaName} 清理批量残留 $groupDir")
                        rootService.deleteRecursively(groupDir)
                    }
                    extractGroup(cloudEntity, backend, password, group, index, groups.size, onlyConfig = true)
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

            // 阶段 C：写媒体恢复队列（含 media.tar 的 snapshotId/fullPath），
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
                        accountName = accountName,
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

    /** 落盘判定双视角：app 进程在共享目录/OTG 上可能读不到，需同时问 rootService。 */
    private suspend fun isLanded(path: String): Boolean =
        File(path).exists() || rootService.exists(path)

    /**
     * 把单个 group 的所有快照解到中转目录 restore/files/<mediaName>/。
     * groupIndex/groupTotal 用于批量恢复时跨组累计进度。
     */
    private suspend fun extractGroup(
        cloudEntity: CloudEntity,
        backend: CloudResticBackend,
        password: String,
        group: ResticFileBackupGroup,
        groupIndex: Int,
        groupTotal: Int,
        skipConfig: Boolean = false,
        onlyConfig: Boolean = false,
    ): Boolean {
        // skipConfig=true 时 config 已由批量接口解好，这里只解 media.tar 等重型快照；
        // onlyConfig=true 时反过来只补解 config（批量未命中时的兜底），不碰 media.tar。
        val sortedBackups = group.backups
            .filter { !skipConfig || it.dataType != DataType.PACKAGE_CONFIG }
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

            val progressCallback = object : ResticRepository.ResticProgressCallback {
                    override fun onRestoreProgress(
                        filesFinished: Long, filesTotal: Long,
                        bytesWritten: Long, bytesTotal: Long,
                        filesSkipped: Long, bytesSkipped: Long
                    ) {
                        val progress = if (bytesTotal > 0) bytesWritten.toFloat() / bytesTotal else 0f
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = currentTime - lastTime
                        val speedStr = if (timeDiff > 0 && bytesWritten > lastBytes) {
                            ((bytesWritten - lastBytes) * 1000 / timeDiff).formatSpeed()
                        } else "0 B/s"

                        lastTime = currentTime
                        lastBytes = bytesWritten
                        _resticProgress.value = _resticProgress.value.copy(
                            filesFinished = filesFinished,
                            filesTotal = filesTotal,
                            filesSkipped = filesSkipped,
                            bytesWritten = bytesWritten,
                            bytesTotal = bytesTotal,
                            bytesSkipped = bytesSkipped,
                            percentage = progress,
                            speed = speedStr
                        )
                        Log.v(TAG, "恢复进度: ${String.format("%.1f%%", progress * 100)}, 速度: $speedStr, 文件: $filesFinished/$filesTotal")

                        // RestorePerf 采样：首次回调 ≈ 仓库打开 + index 加载 + plan 完成（固定开销）；
                        // 之后每秒一行，用 bytesWritten/bytesTotal 判断是网络下载慢还是解压慢。
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
                                    "已读 $bytesWritten/$bytesTotal 速度 $speedStr"
                            )
                        }
                    }

                    override fun onBackupProgress(
                        percentDone: Float, bytesDone: Long,
                        bytesTotal: Long, filesDone: Long, filesTotal: Long,
                        speed: Long
                    ) {
                        // 原有方法体保持不变；restore 相关 VM 里 onBackupProgress 备份时不触发，可保留空/日志实现
                    }
                }

                val targetPath = "${context.localBackupSaveDir()}/restore/"
            val includePath = when (backup.dataType) {
                DataType.PACKAGE_MEDIA -> "media.tar"
                DataType.PACKAGE_CONFIG -> "media_restore_config.json"
                else -> "${backup.dataType.type}.tar"
            }
            val fullTargetPath = "${targetPath}files/${group.mediaName}/"

            // 候选子路径前缀：当前保存目录(迁移后=DataBackuptmp)、旧共享目录(/storage/emulated/0/DataBackup)、
            // 以及裸名（新 fd 流式快照直接把 media.tar 放在快照根）。与本地恢复侧保持同一套候选。
            val currentBaseDir = context.readBackupDirectory() ?: context.localBackupSaveDir()
            val candidateSubPaths: List<String?> = listOf(
                "$currentBaseDir/files/${group.mediaName}",                        // 候选1：当前保存目录
                "${ConstantUtil.LEGACY_SHARED_PATH}/files/${group.mediaName}",     // 候选2：旧共享目录（旧中转快照）
                null                                                              // 候选3：裸名（fd 流式快照）
            ).distinct()

            Log.d(TAG, "恢复路径配置:")
            Log.d(TAG, "  目标路径: $fullTargetPath")
            Log.d(TAG, "  候选子路径: $candidateSubPaths")
            Log.d(TAG, "  包含文件: $includePath")
            Log.d(TAG, "  快照ID: ${backup.snapshotId}")

            val landedFile = File(fullTargetPath, includePath)
            val restoreStartTime = System.currentTimeMillis()
            var success = false
            for ((ci, sub) in candidateSubPaths.withIndex()) {
                Log.d(TAG, "调用 restoreSnapshot (type=${cloudEntity.type}) 候选${ci + 1}/${candidateSubPaths.size}: subPath=${sub ?: "<裸名>"}")
                val tCandidate = System.currentTimeMillis()
                perfCandidateStart = tCandidate
                perfFirstLogged = false
                perfLastLogAt = 0L
                val ok = runCatching {
                    backend.restoreSnapshot(
                        cloudEntity = cloudEntity,
                        password = password,
                        snapshotId = backup.snapshotId,
                        targetPath = fullTargetPath,
                        snapshotSubPath = sub,
                        includePath = includePath,
                        progressCallback = progressCallback
                    )
                }.getOrElse {
                    Log.e(TAG, "候选${ci + 1} 异常", it)
                    false
                }
                val elapsed = System.currentTimeMillis() - tCandidate
                // 落盘判定双视角：app 进程在共享目录/OTG 上可能读不到（exists 恒 false），
                // 只用 File.exists() 会把已成功的候选误判为未命中，导致同一份 media.tar 被重复解压。
                val landedByApp = landedFile.exists()
                val landed = landedByApp || rootService.exists(landedFile.path)
                Log.d(
                    PERF_TAG,
                    "候选${ci + 1}/${candidateSubPaths.size} subPath=${sub ?: "<裸名>"} 耗时 ${elapsed}ms ok=$ok landed=$landed(app=$landedByApp)"
                )
                if (ok && landed) {
                    success = true
                    Log.d(TAG, "候选${ci + 1} 命中: ${sub ?: "<裸名>"}")
                    break
                } else {
                    Log.w(TAG, "候选${ci + 1} 未命中(ok=$ok, landed=$landed): ${sub ?: "<裸名>"}")
                }
            }
            val restoreDuration = System.currentTimeMillis() - restoreStartTime
            Log.d(TAG, "restoreSnapshot 完成，结果: $success, 耗时: ${restoreDuration}ms")

            if (!success) {
                Log.e(TAG, "恢复失败: ${backup.dataType.type}, 快照ID: ${backup.snapshotId}")
                _resticProgress.value = ResticProgressState()
                return false
            }
            Log.d(TAG, "备份项恢复成功: ${backup.dataType.type}")
        }
        return true
    }

    suspend fun calculateSizesForActivatedMedia(expectedBytes: Map<String, Long> = emptyMap()) {
        try {
            Log.d(TAG, "=== 开始计算激活媒体的大小 ===")
            val tSize = System.currentTimeMillis()
            val backupDir = "${context.localBackupSaveDir()}/restore/"
            val activatedMedia = mediaDao.queryActivated(OpType.RESTORE, "", backupDir)

            Log.d(TAG, "找到 ${activatedMedia.size} 个已激活媒体")
            activatedMedia.forEach { media ->
                Log.d(TAG, "计算媒体大小: ${media.name}")
                Log.d(TAG, "原始路径: ${media.path}")

                // 优先用已落盘的 media.tar 实际大小；未下载时回退到快照元数据里的预期大小
                // （列表页「下一步」不再下载 media.tar，此时只能靠 totalBytesProcessed 估算）。
                val mediaFile = File("${backupDir}files/${media.name}/media.tar")
                val exists = mediaFile.exists() || rootService.exists(mediaFile.path)
                val size = if (exists) mediaFile.length() else (expectedBytes[media.name] ?: 0L)
                Log.d(TAG, "恢复文件路径: ${mediaFile.absolutePath}")
                Log.d(TAG, "媒体文件存在: $exists, 大小: $size")

                if (size > 0) {
                    media.mediaInfo.displayBytes = size
                    mediaDao.upsert(media)
                    Log.d(TAG, "大小计算完成: ${media.displayStatsBytes}")
                } else {
                    Log.w(TAG, "媒体文件不存在且无预期大小: ${mediaFile.absolutePath}")
                }
            }

            Log.d(PERF_TAG, "  calculateSizesForActivatedMedia 耗时 ${System.currentTimeMillis() - tSize}ms")
            Log.d(TAG, "=== 激活媒体大小计算完成 ===")
        } catch (e: Exception) {
            Log.e(TAG, "计算大小失败", e)
        }
    }

    suspend fun refreshLocalDatabase(backupDir: String) {
        Log.d(TAG, "=== refreshLocalDatabase 开始 ===")
        Log.d(TAG, "备份目录: $backupDir")

        try {
            val restoreDir = "${context.localBackupSaveDir()}/restore/"
            Log.d(TAG, "恢复目录: $restoreDir")

            Log.d(TAG, "清理旧的恢复记录")
            val tDel = System.currentTimeMillis()
            mediaDao.deleteByOpTypeAndBackupDir(OpType.RESTORE, restoreDir)
            Log.d(PERF_TAG, "  refreshLocalDatabase[删除旧记录] 耗时 ${System.currentTimeMillis() - tDel}ms")

            val restoreDirFile = File(restoreDir)
            val restoreDirExists = restoreDirFile.exists() || rootService.exists(restoreDir)
            Log.d(TAG, "检查恢复目录是否存在: $restoreDirExists")
            if (restoreDirExists) {
                val filesDir = File(restoreDirFile, "files")
                val filesDirExists = filesDir.exists() || rootService.exists(filesDir.path)
                Log.d(TAG, "检查files目录是否存在: $filesDirExists, 路径: ${filesDir.path}")
                if (filesDirExists) {
                    Log.d(TAG, "开始扫描files目录")
                    scanFilesDirectory(filesDir)
                    Log.d(TAG, "files目录扫描完成")
                } else {
                    Log.w(TAG, "files目录不存在: ${filesDir.path}")
                }
            } else {
                Log.w(TAG, "恢复目录不存在: $restoreDir")
            }
            Log.d(TAG, "=== refreshLocalDatabase 完成 ===")
        } catch (e: Exception) {
            Log.e(TAG, "数据库刷新失败", e)
        }
    }

    private suspend fun scanFilesDirectory(filesDir: File) {
        Log.d(TAG, "=== scanFilesDirectory 开始 ===")
        Log.d(TAG, "files目录路径: ${filesDir.path}")
        Log.d(TAG, "files目录是否存在: ${filesDir.exists()}")

        val tScan = System.currentTimeMillis()
        try {
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
            Log.d(TAG, "找到的媒体目录数量: ${mediaNames.size}")
            Log.d(PERF_TAG, "  scanFilesDirectory[列目录] 耗时 ${System.currentTimeMillis() - tScan}ms")

            mediaNames.forEach { mediaName ->
                Log.d(TAG, "处理媒体目录: $mediaName")
                val mediaDir = File(filesDir, mediaName)
                val configFile = File(mediaDir, "media_restore_config.json")
                val configExists = configFile.exists() || rootService.exists(configFile.path)
                Log.d(TAG, "配置文件路径: ${configFile.path}")
                Log.d(TAG, "配置文件是否存在: $configExists")

                if (configExists) {
                    try {
                        Log.d(TAG, "开始读取配置文件: ${configFile.path}")
                        val mediaEntity = readMediaConfig(configFile, mediaName)

                        if (mediaEntity != null) {
                            Log.d(TAG, "配置文件解析成功: ${mediaEntity.name}")
                            Log.d(TAG, "媒体信息: 名称=${mediaEntity.name}, 激活状态=${mediaEntity.extraInfo.activated}")

                            Log.d(TAG, "插入媒体实体到数据库")
                            mediaDao.upsert(mediaEntity)

                            Log.d(TAG, "更新媒体数据库状态")
                            updateFilesDatabase(mediaName)

                            Log.d(TAG, "媒体目录处理完成: $mediaName")
                        } else {
                            Log.w(TAG, "配置文件解析为空: ${configFile.path}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "处理媒体配置失败: ${configFile.path}", e)
                    }
                } else {
                    Log.w(TAG, "配置文件不存在: ${configFile.path}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "扫描媒体目录时发生异常", e)
        }

        Log.d(PERF_TAG, "  scanFilesDirectory 总耗时 ${System.currentTimeMillis() - tScan}ms")
        Log.d(TAG, "=== scanFilesDirectory 结束 ===")
    }

    private suspend fun readMediaConfig(configFile: File, mediaName: String): MediaEntity? {
        Log.d(TAG, "=== readMediaConfig 开始 ===")
        Log.d(TAG, "配置文件: ${configFile.path}")
        Log.d(TAG, "媒体名称: $mediaName")

        return try {
            Log.d(TAG, "使用RemoteRootService读取JSON配置")
            val entity = rootService.readJson<MediaEntity>(configFile.path)

            if (entity != null) {
                Log.d(TAG, "原始配置读取成功: 名称=${entity.name}, 备份目录=${entity.indexInfo.backupDir}")

                val modifiedEntity = entity.copy(
                    id = 0,
                    indexInfo = entity.indexInfo.copy(
                        opType = OpType.RESTORE,
                        name = mediaName,
                        cloud = "",
                        backupDir = "${context.localBackupSaveDir()}/restore/"
                    ),
                    extraInfo = entity.extraInfo.copy(activated = false)
                )
                Log.d(TAG, "配置实体修改完成")
                modifiedEntity
            } else {
                Log.w(TAG, "读取的配置实体为null")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取媒体配置文件失败: ${configFile.path}", e)
            null
        }
    }

    private suspend fun updateFilesDatabase(mediaName: String) {
        Log.d(TAG, "=== updateFilesDatabase 开始: $mediaName ===")
        try {
            val backupDir = "${context.localBackupSaveDir()}/restore/"
            Log.d(TAG, "查询参数: opType=RESTORE, cloud=, backupDir=$backupDir")

            // 查询该媒体名称的所有记录
            Log.d(TAG, "开始查询媒体记录: $mediaName")
            val existingMedia = mediaDao.query(OpType.RESTORE, "", backupDir)
                .filter { it.name == mediaName }

            Log.d(TAG, "查询到的媒体记录数: ${existingMedia.size}")
            existingMedia.forEach { media ->
                Log.d(TAG, "媒体记录: ${media.name}, backupDir: ${media.indexInfo.backupDir}, activated: ${media.extraInfo.activated}, id: ${media.id}")
            }

            if (existingMedia.isNotEmpty()) {
                Log.d(TAG, "开始激活媒体: $mediaName, 找到 ${existingMedia.size} 个记录")
                existingMedia.forEach { media ->
                    Log.d(TAG, "激活媒体ID: ${media.id}")
                    mediaDao.activateById(media.id, true)
                    Log.d(TAG, "媒体已激活: $mediaName (ID: ${media.id})")
                }
            } else {
                Log.w(TAG, "未找到媒体记录: $mediaName")

                // 添加调试查询
                val allMedia = mediaDao.query(OpType.RESTORE, "", backupDir)
                Log.d(TAG, "数据库中所有相关媒体 (${allMedia.size} 个):")
                allMedia.forEach { media ->
                    Log.d(TAG, "- ${media.name}, backupDir: ${media.indexInfo.backupDir}, activated: ${media.extraInfo.activated}")
                }
            }

            Log.d(TAG, "=== 媒体激活完成: $mediaName ===")
        } catch (e: Exception) {
            Log.e(TAG, "激活媒体失败: ${e.message}", e)
        }
    }

    private fun Long.formatSpeed(): String {
        return when {
            this < 1024 -> "$this B/s"
            this < 1024 * 1024 -> "${this / 1024} KiB/s"
            this < 1024 * 1024 * 1024 -> "${this / (1024 * 1024)} MiB/s"
            else -> "${this / (1024 * 1024 * 1024)} GiB/s"
        }
    }
}

sealed interface CloudFilesRestoreUiState {
    data object Loading : CloudFilesRestoreUiState
    data class Success(val groups: List<ResticFileBackupGroup>) : CloudFilesRestoreUiState
    data class Error(val message: String) : CloudFilesRestoreUiState
}