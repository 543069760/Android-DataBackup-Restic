package com.xayah.core.service.packages.restore

import android.annotation.SuppressLint
import com.xayah.core.datastore.readKillAppOption
import com.xayah.core.datastore.readResetRestoreList
import com.xayah.core.datastore.readRestorePermissions
import com.xayah.core.datastore.readRestoreSsaid
import com.xayah.core.datastore.readRestoreUser
import com.xayah.core.datastore.readRestoreUserMap
import com.xayah.core.datastore.saveLastRestoreTime
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.ProcessingInfoType
import com.xayah.core.model.ProcessingType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.Info
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.util.get
import com.xayah.core.service.R
import com.xayah.core.service.packages.AbstractPackagesService
import com.xayah.core.service.util.PackagesRestoreUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.command.PreparationUtil
import kotlinx.coroutines.flow.first
import java.io.File

internal abstract class AbstractRestoreService : AbstractPackagesService() {
    override suspend fun onInitializingPreprocessingEntities(entities: MutableList<ProcessingInfoEntity>) {
        entities.apply {
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.set_up_inst_env),
                type = ProcessingType.PREPROCESSING,
                infoType = ProcessingInfoType.SET_UP_INST_ENV
            ).apply {
                id = mTaskDao.upsert(this)
            })
        }
    }

    override suspend fun onInitializingPostProcessingEntities(entities: MutableList<ProcessingInfoEntity>) {
        entities.apply {
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.necessary_remaining_data_processing),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING
            ).apply {
                id = mTaskDao.upsert(this)
            })
        }
    }

    @SuppressLint("StringFormatInvalid")
    override suspend fun onInitializing() {
        // 批量恢复：在查询激活包之前先完成 config 取回与 DB 激活（默认空实现，仅批量场景覆写）
        onPrepareEntities()

        val packages = getPackages()
        packages.forEach { pkg ->
            mPkgEntities.add(
                TaskDetailPackageEntity(
                    taskId = mTaskEntity.id,
                    packageEntity = pkg,
                    apkInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_restore, DataType.PACKAGE_APK.type.uppercase())),
                    userInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_restore, DataType.PACKAGE_USER.type.uppercase())),
                    userDeInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_restore, DataType.PACKAGE_USER_DE.type.uppercase())),
                    dataInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_restore, DataType.PACKAGE_DATA.type.uppercase())),
                    obbInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_restore, DataType.PACKAGE_OBB.type.uppercase())),
                    mediaInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_restore, DataType.PACKAGE_MEDIA.type.uppercase())),
                ).apply {
                    id = mTaskDao.upsert(this)
                }
            )
        }
    }

    override suspend fun beforePreprocessing() {
        NotificationUtil.notify(mContext, mNotificationBuilder, mContext.getString(R.string.restoring), mContext.getString(R.string.preprocessing))
    }

    abstract suspend fun getPackages(): List<PackageEntity>
    protected open suspend fun beforeSettingUpEnv() {}
    abstract suspend fun restore(type: DataType, userId: Int, p: PackageEntity, t: TaskDetailPackageEntity, srcDir: String)
    protected open suspend fun clear() {}

    /**
     * 批量恢复钩子：在查询激活包之前调用，用于取回各包 CONFIG 并写入/激活 DB。
     * 默认空实现，仅 RestoreServiceLocalImpl 在批量 restic 场景覆写。
     */
    protected open suspend fun onPrepareEntities() {}

    /**
     * 批量恢复钩子：单个包写回之前调用，用于按队列从 restic 仓库解出该包的重型 tar 到中转目录。
     * 默认空实现，仅 RestoreServiceLocalImpl 在批量 restic 场景覆写。
     */
    protected open suspend fun onBeforeRestorePackage(p: PackageEntity, t: TaskDetailPackageEntity, userId: Int) {}

    /**
     * 批量恢复钩子：单个包写回结束（无论成败）后调用，用于清理该包中转目录、释放空间。
     * 默认空实现，仅 RestoreServiceLocalImpl 在批量 restic 场景覆写。
     */
    protected open suspend fun onAfterRestorePackage(p: PackageEntity, userId: Int, success: Boolean) {}

    abstract val mPackagesRestoreUtil: PackagesRestoreUtil

    private var restoreUser = -1

    /**
     * 源备份 userId -> 目标设备 userId 的重映射。
     * 用于「备份中的用户在当前设备不存在、已用 root 创建新用户」的场景：
     * 只改恢复目标 userId，不改 srcDir（srcDir 仍按 p.userId 定位备份源目录）。
     * 由 Setup 页写入 DataStore（格式 "10:15,12:16"），此处一次性读入解析。
     */
    private var restoreUserRemap: Map<Int, Int> = emptyMap()

    override suspend fun onPreprocessing(entity: ProcessingInfoEntity) {
        restoreUser = mContext.readRestoreUser().first()
        restoreUserRemap = mContext.readRestoreUserMap()
            .split(",")
            .mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size != 2) return@mapNotNull null
                val from = parts[0].toIntOrNull() ?: return@mapNotNull null
                val to = parts[1].toIntOrNull() ?: return@mapNotNull null
                from to to
            }
            .toMap()
        log { "Restore user: $restoreUser, remap: $restoreUserRemap" }
        when (entity.infoType) {
            ProcessingInfoType.SET_UP_INST_ENV -> {
                if (runCatchingOnService { beforeSettingUpEnv() }) {
                    log { "Trying to grant adb install permissions." }
                    PreparationUtil.setInstallEnv().apply {
                        entity.update(state = if (isSuccess) OperationState.DONE else OperationState.ERROR)
                    }
                } else {
                    entity.update(state = OperationState.ERROR, log = log { "Failed to grant adb install permissions." })
                }
                entity.update(progress = 1f)
            }

            else -> {}
        }
    }

    override suspend fun onProcessing() {
        mTaskEntity.update(rawBytes = mTaskRepo.getRawBytes(TaskType.PACKAGE), availableBytes = mTaskRepo.getAvailableBytes(OpType.RESTORE), totalBytes = mTaskRepo.getTotalBytes(OpType.RESTORE), totalCount = mPkgEntities.size)
        log { "Task count: ${mPkgEntities.size}." }

        val killAppOption = mContext.readKillAppOption().first()
        log { "Kill app option: $killAppOption" }

        // 每个 app 固定 6 个阶段（6 个 DataType）
        val stagesPerApp = 6
        val totalStages = mPkgEntities.size * stagesPerApp

        mPkgEntities.forEachIndexed { index, pkg ->
            // 当前 app 内已完成阶段数
            var stageInApp = 0
            fun notifyStage() {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.restoring),
                    pkg.packageEntity.packageInfo.label,
                    totalStages,
                    index * stagesPerApp + stageInApp
                )
            }

            executeAtLeast {
                notifyStage()
                log { "Current package: ${pkg.packageEntity}" }

                killApp(killAppOption, pkg)

                pkg.update(state = OperationState.PROCESSING)
                val p = pkg.packageEntity
                val baseDir = if (File("${mRootDir}/restore").exists()) {
                    "${mRootDir}/restore/apps"
                } else {
                    mAppsDir
                }
                val srcDir = "${baseDir}/${p.archivesRelativeDir}"
                // remap 优先：只对「缺失的备份源 userId」改投新用户，不影响其余包与源目录路径
                val baseUserId = if (restoreUser == -1) p.userId else restoreUser
                val userId = restoreUserRemap[baseUserId] ?: baseUserId
                // 归档解出侧必须用原始 p.userId：队列 key (mRestoreQueueMap) 与
                // transitDirOf 的 user_<id> 都是按备份源 userId 建的，传 remap 后的 id 会查不到/解错目录
                onBeforeRestorePackage(p, pkg, p.userId)

                var failedFast = false
                val dataTypes = listOf(
                    DataType.PACKAGE_APK,
                    DataType.PACKAGE_USER,
                    DataType.PACKAGE_USER_DE,
                    DataType.PACKAGE_DATA,
                    DataType.PACKAGE_OBB,
                    DataType.PACKAGE_MEDIA,
                )

                // FBE 预检：目标用户（次级用户/系统分身）CE 存储未解锁（FBE 锁定）时，
                // /data/user/<id>/ 加密不可写，tar 会走到 ENOKEY(rc=2)。此处每包只检一次，
                // 覆盖该包全部 dataType，提前友好报错并跳过 tar。userId==0（主用户，始终解锁）不检，行为不变。
                if (userId != 0 && !mRootService.isUserUnlocked(userId)) {
                    val msg = mContext.getString(R.string.restore_target_user_locked)
                    log { "目标用户 $userId CE 存储未解锁（FBE 锁定）：$msg" }
                    dataTypes.forEach { type ->
                        pkg.update(dataType = type, state = OperationState.ERROR, log = msg)
                    }
                    failedFast = true
                }

                if (!failedFast) {
                    for (type in dataTypes) {
                        restore(type = type, userId = userId, p = p, t = pkg, srcDir = srcDir)
                        // 每完成一个 DataType 推进一个阶段
                        stageInApp++
                        notifyStage()
                        if (pkg.get(type).state == OperationState.ERROR) {
                            log { "Fail-fast: ${p.packageName} 在 ${type.type} 步骤失败，跳过该 App 剩余恢复步骤。" }
                            failedFast = true
                            break
                        }
                    }
                }

                if (!failedFast) {
                    if (mContext.readRestorePermissions().first()) {
                        mPackagesRestoreUtil.restorePermissions(userId = userId, p = p)
                    }
                    if (mContext.readRestoreSsaid().first()) {
                        mPackagesRestoreUtil.restoreSsaid(userId = userId, p = p)
                    }
                }

                if (pkg.isSuccess) {
                    pkg.update(packageEntity = p)
                    mTaskEntity.update(successCount = mTaskEntity.successCount + 1)
                } else {
                    mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                }
                pkg.update(state = if (pkg.isSuccess) OperationState.DONE else OperationState.ERROR)

                // 同上：中转目录清理也必须用原始 p.userId，否则指向 user_<新id> 清不到真正目录
                onAfterRestorePackage(p, p.userId, pkg.isSuccess)
            }

            // fail-fast break 等情况下补齐到满阶段，保证单调
            stageInApp = stagesPerApp
            notifyStage()

            mTaskEntity.update(processingIndex = mTaskEntity.processingIndex + 1)
        }

        // 边界日志：整个包循环跑完、onProcessing 即将正常返回。
        // 若此行出现、但 AbstractProcessingService 的 "postProcessing() entered" 不出现，
        // 说明卡在 processing() 返回之后、postProcessing() 进入之前（主线程 hop / 结构化并发残留子协程）。
        log { "onProcessing loop done, returning" }
    }

    override suspend fun onPostProcessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.restoring),
                    mContext.getString(R.string.wait_for_remaining_data_processing)
                )

                if (mContext.readResetRestoreList().first() && mTaskEntity.failureCount == 0) {
                    mPackageDao.clearActivated(OpType.RESTORE)
                }
                val isSuccess = runCatchingOnService { clear() }
                entity.update(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR)
            }

            else -> {}
        }
    }

    override suspend fun afterPostProcessing() {
        mContext.saveLastRestoreTime(mEndTimestamp)
        val time = DateUtil.getShortRelativeTimeSpanString(context = mContext, time1 = mStartTimestamp, time2 = mEndTimestamp)
        NotificationUtil.notify(
            mContext,
            mNotificationBuilder,
            mContext.getString(R.string.restore_completed),
            "${time}, ${mTaskEntity.successCount} ${mContext.getString(R.string.succeed)}, ${mTaskEntity.failureCount} ${mContext.getString(R.string.failed)}",
            ongoing = false
        )
    }
}