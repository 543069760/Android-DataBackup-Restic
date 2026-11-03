package com.xayah.feature.main.processing.packages.restore

import android.util.Log
import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.SavedStateHandle
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.model.OpType
import com.xayah.core.model.StorageMode
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.network.client.getCloud
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.packages.restore.ProcessingServiceProxyCloudImpl
import com.xayah.core.service.packages.restore.ProcessingServiceProxyLocalImpl
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.util.LogUtil
import com.xayah.core.util.decodeURL
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.UserTypeUtil
import com.xayah.core.datastore.saveResticActiveIsOtg
import com.xayah.core.util.navigateSingle
import com.xayah.core.util.toFriendlyMessage
import com.xayah.feature.main.processing.UpdateAppsWithFilter
import com.xayah.feature.main.processing.AbstractPackagesProcessingViewModel
import com.xayah.feature.main.processing.FinishSetup
import com.xayah.feature.main.processing.GetUsers
import com.xayah.feature.main.processing.IndexUiState
import com.xayah.feature.main.processing.ProcessingUiIntent
import com.xayah.feature.main.processing.R
import com.xayah.feature.main.processing.SetCloudEntity
import com.xayah.feature.main.processing.UpdateApps
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@ExperimentalCoroutinesApi
@ExperimentalMaterial3Api
@HiltViewModel
class RestoreViewModelImpl @Inject constructor(
    @ApplicationContext private val mContext: Context,
    private val mRootService: RemoteRootService,
    mTaskRepo: TaskRepository,
    private val mPkgRepo: PackageRepository,
    private val mCloudRepo: CloudRepository,
    private val appsRepo: AppsRepo,
    mLocalService: ProcessingServiceProxyLocalImpl,
    mCloudService: ProcessingServiceProxyCloudImpl,
    private val args: SavedStateHandle,
) : AbstractPackagesProcessingViewModel(mContext, mRootService, mTaskRepo, mLocalService, mCloudService) {
    private var currentPackageNameFilter: String = ""

    // 任务级 OTG 标志：来自 PackagesRestoreProcessingGraph 路由的 ARG_IS_OTG
    // 非 OTG（本地/云端）恒为 false，保持零回归
    private val isOtg: Boolean = args.get<Boolean>(MainRoutes.ARG_IS_OTG) ?: false
    override suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            is UpdateApps -> {
                val cloud: String
                val backupSaveDir: String
                if (uiState.value.cloudEntity == null) {
                    cloud = ""
                    backupSaveDir = "${mContext.localBackupSaveDir()}/restore/"
                } else {
                    cloud = ""  // 修复：云端恢复也使用空字符串，与数据库记录匹配
                    backupSaveDir = "${mContext.localBackupSaveDir()}/restore/"
                }
                val packages = mPkgRepo.queryActivated(OpType.RESTORE, cloud, backupSaveDir)
                LogUtil.log { "RestoreViewModelImpl.UpdateApps" to "Query activated apps, cloud: $cloud, backupDir: $backupSaveDir" }
                LogUtil.log { "RestoreViewModelImpl.UpdateApps" to "Queried apps count: ${packages.size}" }
                var bytes = 0.0
                packages.forEach {
                    bytes += it.storageStatsBytes
                }
                _packages.value = packages
                _packagesSize.value = bytes.formatSize()
            }

            is UpdateAppsWithFilter -> {
                currentPackageNameFilter = intent.packageNameFilter
                Log.d("RestoreViewModelImpl", "处理 UpdateAppsWithFilter: ${intent.packageNameFilter}")

                val cloud: String
                val backupSaveDir: String
                if (uiState.value.cloudEntity == null) {
                    cloud = ""
                    backupSaveDir = "${mContext.localBackupSaveDir()}/restore/"
                } else {
                    cloud = ""
                    backupSaveDir = "${mContext.localBackupSaveDir()}/restore/"
                }

                Log.d("RestoreViewModelImpl", "查询参数: cloud=$cloud, backupDir=$backupSaveDir")

                // 使用 mPkgRepo 而不是 appsRepo，并传入正确的参数
                val allActivatedApps = mPkgRepo.queryActivated(OpType.RESTORE, cloud, backupSaveDir)
                Log.d("RestoreViewModelImpl", "所有激活应用数: ${allActivatedApps.size}")
                allActivatedApps.forEach { app ->
                    Log.d("RestoreViewModelImpl", "激活应用: ${app.packageName}, cloud=${app.indexInfo.cloud}, backupDir=${app.indexInfo.backupDir}")
                }

                val packages = allActivatedApps.filter { it.packageName == intent.packageNameFilter }
                Log.d("RestoreViewModelImpl", "筛选结果: 找到 ${packages.size} 个应用")

                var bytes = 0.0
                packages.forEach {
                    bytes += it.displayStatsBytes
                }
                _packages.value = packages
                _packagesSize.value = bytes.formatSize()
                Log.d("RestoreViewModelImpl", "更新完成: packages=${packages.size}, size=${bytes.formatSize()}")
            }

            is SetCloudEntity -> {
                val name = args.get<String>(MainRoutes.ARG_ACCOUNT_NAME)?.decodeURL()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    emitState(state.copy(storageIndex = 1, storageType = StorageMode.Cloud, cloudEntity = mCloudRepo.queryByName(name)))
                } else {
                    emitState(state.copy(storageIndex = 0, storageType = StorageMode.Local, cloudEntity = null))
                }
            }

            is FinishSetup -> {
                Log.d(
                    "RestoreNavTrace",
                    "FinishSetup enter, ts=${System.currentTimeMillis()}"
                )
                if (state.storageType == StorageMode.Cloud) {
                    _isTesting.value = true
                    emitEffect(IndexUiEffect.DismissSnackbar)
                    emitEffectOnIO(
                        IndexUiEffect.ShowSnackbar(
                            type = SnackbarType.Loading,
                            message = mCloudRepo.getString(R.string.processing),
                            duration = SnackbarDuration.Indefinite,
                        )
                    )
                    runCatching {
                        val (client, _) = mCloudRepo.getClient(state.cloudEntity!!.name)
                        client.testConnection()
                        emitEffect(IndexUiEffect.DismissSnackbar)
                        mContext.saveResticActiveIsOtg(isOtg)
                        withMainContext {
                            Log.d(
                                "RestoreNavTrace",
                                "navigate PackagesRestoreProcessing + startRestore, ts=${System.currentTimeMillis()}"
                            )
                            intent.navController.popBackStack()
                            intent.navController.navigateSingle(MainRoutes.PackagesRestoreProcessing.route)
                            mLocalService.startRestore(currentPackageNameFilter)
                        }
                    }.onFailure {
                        emitEffect(IndexUiEffect.DismissSnackbar)
                        if (it.localizedMessage != null)
                            emitEffectOnIO(IndexUiEffect.ShowSnackbar(type = SnackbarType.Error, message = it.toFriendlyMessage(mContext), duration = SnackbarDuration.Long))
                    }
                    _isTesting.value = false
                } else {
                    // OTG 瞬态标记：await 写入完成后再启动服务，防止读到旧值
                    mContext.saveResticActiveIsOtg(isOtg)
                    withMainContext {
                        Log.d(
                            "RestoreNavTrace",
                            "navigate PackagesRestoreProcessing + startRestore, ts=${System.currentTimeMillis()}"
                        )
                        intent.navController.popBackStack()
                        intent.navController.navigateSingle(MainRoutes.PackagesRestoreProcessing.route)
                        mLocalService.startRestore(currentPackageNameFilter)
                    }
                }
            }

            is GetUsers -> {
                val users = mRootService.getUsers().map { it.id }.toMutableSet()
                mPkgRepo.queryUserIds(OpType.RESTORE).forEach {
                    users.add(it)
                }
                val restoreUsers = mutableListOf(
                    DialogRadioItem(
                        enum = Any(),
                        title = mContext.getString(R.string.backup_user),
                    )
                )
                users.sorted().forEach {
                    restoreUsers.add(
                        DialogRadioItem(
                            enum = Any(),
                            title = it.toString(),
                        )
                    )
                }
                _restoreUsers.value = restoreUsers
            }

            else -> {

            }
        }
    }

    private val _accounts: Flow<List<DialogRadioItem<Any>>> = mCloudRepo.clouds.map { entities ->
        entities.map {
            DialogRadioItem(
                enum = Any(),
                title = it.name,
                desc = it.user,
            )
        }
    }.flowOnIO()
    private val _isTesting: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val _packages: MutableStateFlow<List<PackageEntity>> = MutableStateFlow(listOf())
    private val _packagesSize: MutableStateFlow<String> = MutableStateFlow("")
    private val _restoreUsers: MutableStateFlow<List<DialogRadioItem<Any>>> = MutableStateFlow(listOf(DialogRadioItem(enum = Any(), title = mContext.getString(R.string.backup_user))))

    val accounts: StateFlow<List<DialogRadioItem<Any>>> = _accounts.stateInScope(listOf())
    val isTesting: StateFlow<Boolean> = _isTesting.stateInScope(false)
    val packages: StateFlow<List<PackageEntity>> = _packages.stateInScope(listOf())
    val packagesSize: StateFlow<String> = _packagesSize.stateInScope("")
    val restoreUsers: StateFlow<List<DialogRadioItem<Any>>> = _restoreUsers.stateInScope(listOf(DialogRadioItem(enum = Any(), title = mContext.getString(R.string.backup_user))))

    /**
     * FBE 预检：目标用户 CE 存储是否已解锁。
     * user 0（主用户）始终解锁，直接返回 true。
     */
    suspend fun isUserUnlocked(userId: Int): Boolean =
        if (userId == 0) true else mRootService.isUserUnlocked(userId)

    /**
     * 返回集合中处于锁定态（未解锁）的 userId 列表；为空表示全部可恢复。
     */
    suspend fun queryLockedUserIds(userIds: Set<Int>): List<Int> =
        userIds.filter { it > 0 && !mRootService.isUserUnlocked(it) }

    /** 目标用户三态：存在且解锁 / 存在但 FBE 锁定 / 设备上不存在 */
    enum class TargetUserState { UNLOCKED, LOCKED, MISSING }

    /**
     * 用 getUsers() 判断存在性、isUserUnlocked() 判断锁定态。
     * 不能用 isUserUnlocked 判存在性——它对"不存在"也返回 false。
     */
    suspend fun classifyTargetUsers(userIds: Set<Int>): Map<TargetUserState, List<Int>> {
        val existingIds = mRootService.getUsers().map { it.id }.toSet()
        return userIds.filter { it > 0 }
            .groupBy { id ->
                when {
                    id !in existingIds -> TargetUserState.MISSING
                    mRootService.isUserUnlocked(id) -> TargetUserState.UNLOCKED
                    else -> TargetUserState.LOCKED
                }
            }
    }

    suspend fun getUsers(): Set<Int> = runCatching {
        mRootService.getUsers().map { it.id }.toSet()
    }.getOrElse { emptySet() }

    /**
     * 目标用户中属于「应用分身」（userType = profile.CLONE）的 id。
     *
     * 按 userType 判定而不是 userId：分身 id 由系统分配，999 只是常见值。
     * 系统分身是 full.SECONDARY，不会落到这里，二者的提示逻辑保持独立。
     */
    suspend fun getAppCloneUserIds(userIds: Set<Int>): Set<Int> = runCatching {
        val lines = mRootService.getUserListVerbose()
        val cloneIds = UserTypeUtil.appCloneUserIds(lines)
        Log.d("AppCloneProbe", "verboseLines=${lines.size} $lines → cloneIds=$cloneIds, targets=$userIds")
        userIds.filter { it in cloneIds }.toSet()
    }.onFailure {
        Log.d("AppCloneProbe", "getAppCloneUserIds failed: $it")
    }.getOrElse { emptySet() }

    /**
     * 某个应用分身用户下本次恢复的包分布。
     *
     * - [missing]：分身里还没有 → 系统「应用分身」开关必定没开（开关开着时 ColorOS 早已把包装进分身）。
     * - [existing]：分身里已有 → 可能开关是开的，也可能是上一次恢复时被 `pm install --user` 塞进去的，
     *   两者用现有 API 无法区分（dumpsys / /data/system/oplus-vapp / com.oplus.multiapp 均读不到开关）。
     *   这种情况仍要提示，否则用户会陷入「提示永远不出现」的死角。
     */
    data class AppCloneTarget(
        val userId: Int,
        val missing: List<String>,
        val existing: List<String>,
        /**
         * 主用户（user 0）里尚未安装的包。
         *
         * 实测：分身图标由主用户桌面维护，主用户没有安装同一个应用时，即使把 APK 与数据
         * 写进分身用户（甚至先分身、后主用户补装），桌面也不会出现分身图标。
         * 所以恢复分身之前必须先确认主用户已安装。
         */
        val hostMissing: List<String> = emptyList(),
    )

    /**
     * 列出本次恢复中落到应用分身用户上的包，并区分「分身里未安装」与「已存在」。
     *
     * 系统「应用分身」开关打开时，ColorOS 必定已把该包装进对应分身用户；反过来包不存在，
     * 就说明这个应用的分身还没开——此时直接恢复只会把 APK 与数据写进 /data/user/<id>，
     * 主桌面不会出现分身图标（实测确认）。
     *
     * 返回空列表表示本次恢复不涉及应用分身用户。
     */
    suspend fun findAppCloneTargets(
        packages: List<PackageEntity>,
        cloneUserIds: Set<Int>,
        explicitTargetUser: Int = -1,
    ): List<AppCloneTarget> = runCatching {
        // 目标用户口径必须与 AbstractRestoreService.onPreprocessing 完全一致：
        // restoreUser != -1（用户在设置里显式选了「恢复用户」）时，所有包都投到该用户，
        // 与包自身的备份源 userId 无关；否则才用 p.userId。
        // 只按备份源 userId 过滤会在「显式选 999」时全部落空——这正是提示不出现的根因。
        val byUser: Map<Int, List<String>> = if (explicitTargetUser > 0) {
            mapOf(explicitTargetUser to packages.map { it.packageName }.distinct())
        } else {
            packages.groupBy({ it.userId }, { it.packageName }).mapValues { it.value.distinct() }
        }
        val targets = byUser.filterKeys { it in cloneUserIds }
            .map { (userId, names) ->
                val (existing, missing) = names.partition { mRootService.queryInstalled(it, userId) }
                // 主用户（USER_SYSTEM = 0）未安装的包：分身图标依赖主用户里的同名应用，
                // 缺了它即使分身体内数据完整，桌面也不会出现分身图标（实测，且顺序不可逆）。
                val hostMissing = names.filter { !mRootService.queryInstalled(it, 0) }
                AppCloneTarget(
                    userId = userId,
                    missing = missing,
                    existing = existing,
                    hostMissing = hostMissing,
                )
            }
            .sortedBy { it.userId }
        Log.d(
            "AppCloneProbe",
            "byUser=${byUser.mapValues { it.value.size }}, cloneUserIds=$cloneUserIds, targets=$targets"
        )
        targets
    }.onFailure {
        Log.d("AppCloneProbe", "findAppCloneTargets failed: $it")
    }.getOrElse { emptyList() }

    /** 恢复分身前的两步自检结果 */
    data class AppCloneStepState(
        /** 步骤一：主用户（user 0）已安装 */
        val mainInstalled: Boolean,
        /** 步骤二：分身用户里已存在该包（包名维度） */
        val cloneEnabled: Boolean,
    )

    /**
     * 实时自检「主用户已安装」与「分身用户里已有该包」。
     *
     * 步骤二只按包名维度判定：分身用户下存在该包即视为「分身已开启」。
     * 不用 enabledSetting 之类的状态位——各 ROM 写法不一（DEFAULT(0) 同样代表启用，
     * 只有被显式 disable 才是 2/3/4），拿它当门槛会在部分 ROM 上误报未开启。
     */
    suspend fun checkAppCloneSteps(packages: List<String>, cloneUserId: Int): AppCloneStepState = runCatching {
        val names = packages.distinct()
        if (names.isEmpty()) AppCloneStepState(mainInstalled = false, cloneEnabled = false)
        else AppCloneStepState(
            mainInstalled = names.all { mRootService.queryInstalled(it, 0) },
            cloneEnabled = names.all { mRootService.queryInstalled(it, cloneUserId) },
        )
    }.onFailure {
        Log.d("AppCloneProbe", "checkAppCloneSteps failed: $it")
    }.getOrElse { AppCloneStepState(mainInstalled = false, cloneEnabled = false) }

    /** root 创建用户，返回真实分配的新 userId，失败返回 -1 */
    suspend fun createUser(name: String): Int = mRootService.createUser(name)

    /** root 删除用户（回滚用），返回是否成功 */
    suspend fun removeUser(userId: Int): Boolean = mRootService.removeUser(userId)

    /** 后台启动用户以激活 CE 存储（区分未启动 vs 真 FBE 锁定），返回最终是否已解锁 */
    suspend fun startUser(userId: Int): Boolean = mRootService.startUser(userId)

    /** 已存在的「backup-<源id>」用户 → 直接复用其 id，避免取消重试后重复创建 */
    suspend fun findCreatedBackupUser(missingId: Int): Int =
        mRootService.getUsers().firstOrNull { it.name == "backup-$missingId" }?.id ?: -1

    /** 创建用户失败等错误提示（IndexUiEffect/SnackbarType/SnackbarDuration 已 import） */
    suspend fun showErrorSnackbar(message: String) =
        emitEffectOnIO(
            IndexUiEffect.ShowSnackbar(
                type = SnackbarType.Error,
                message = message,
                duration = SnackbarDuration.Long,
            )
        )
}