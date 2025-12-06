package com.xayah.core.service.packages.backup

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import com.xayah.core.common.util.toLineString
import com.xayah.core.datastore.readBackupConfigs
import com.xayah.core.datastore.readBackupItself
import com.xayah.core.datastore.readKillAppOption
import com.xayah.core.datastore.readResetBackupList
import com.xayah.core.datastore.saveLastBackupTime
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
import com.xayah.core.model.util.set
import com.xayah.core.service.R
import com.xayah.core.service.model.NecessaryInfo
import com.xayah.core.service.packages.AbstractPackagesService
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.PreparationUtil
import com.xayah.core.restic.ResticRepository
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.datastore.readResticPassword
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.delay

@AndroidEntryPoint
internal abstract class AbstractBackupService : AbstractPackagesService() {
    protected var mBackupTimestamp: Long = 0L

    @Inject
    protected lateinit var resticRepo: ResticRepository

    override suspend fun onInitializingPreprocessingEntities(entities: MutableList<ProcessingInfoEntity>) {
        entities.apply {
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.necessary_preparations),
                type = ProcessingType.PREPROCESSING,
                infoType = ProcessingInfoType.NECESSARY_PREPARATIONS
            ).apply {
                id = mTaskDao.upsert(this)
            })
        }
    }

    override suspend fun onInitializingPostProcessingEntities(entities: MutableList<ProcessingInfoEntity>) {
        entities.apply {
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.backup_itself),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.BACKUP_ITSELF
            ).apply {
                id = mTaskDao.upsert(this)
            })
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.save_icons),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.SAVE_ICONS
            ).apply {
                id = mTaskDao.upsert(this)
            })
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
        // 生成本次备份的统一时间戳
        mBackupTimestamp = DateUtil.getTimestamp()

        val packages = mPackageRepo.queryActivated(OpType.BACKUP)

        packages.forEach { pkg ->
            pkg.indexInfo.backupTimestamp = mBackupTimestamp

            val info = mRootService.getPackageInfoAsUser(
                pkg.packageName,
                0,
                pkg.userId
            )

            if (info != null) {
                pkg.packageInfo.label = info.applicationInfo?.loadLabel(mContext.packageManager).toString()
                pkg.packageInfo.versionName = info.versionName ?: ""
                pkg.packageInfo.versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
                pkg.packageInfo.flags = info.applicationInfo?.flags ?: 0
                pkg.packageInfo.firstInstallTime = info.firstInstallTime
                pkg.packageInfo.lastUpdateTime = info.lastUpdateTime

                pkg.extraInfo.uid = info.applicationInfo?.uid ?: -1
                pkg.extraInfo.permissions = mRootService.getPermissions(packageInfo = info)
                pkg.extraInfo.enabled = info.applicationInfo?.enabled ?: false
            }

            mPkgEntities.add(
                TaskDetailPackageEntity(
                    taskId = mTaskEntity.id,
                    packageEntity = pkg,
                    apkInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_APK.type.uppercase())),
                    userInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_USER.type.uppercase())),
                    userDeInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_USER_DE.type.uppercase())),
                    dataInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_DATA.type.uppercase())),
                    obbInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_OBB.type.uppercase())),
                    mediaInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_MEDIA.type.uppercase())),
                ).apply {
                    id = mTaskDao.upsert(this)
                }
            )
        }
    }

    override suspend fun beforePreprocessing() {
        NotificationUtil.notify(mContext, mNotificationBuilder, mContext.getString(R.string.backing_up), mContext.getString(R.string.preprocessing))
    }

    protected open suspend fun onTargetDirsCreated() {}
    protected open suspend fun onAppDirCreated(archivesRelativeDir: String): Boolean = true
    abstract suspend fun backup(type: DataType, p: PackageEntity, r: PackageEntity?, t: TaskDetailPackageEntity, dstDir: String)
    protected open suspend fun onConfigSaved(path: String, archivesRelativeDir: String) {}
    protected open suspend fun onItselfSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun onConfigsSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun onIconsSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun clear() {}
    protected open suspend fun onCleanupFailedBackup(archivesRelativeDir: String) {}
    override suspend fun onCleanupIncompleteBackup(currentIndex: Int) {}

    protected abstract val mPackagesBackupUtil: PackagesBackupUtil

    private lateinit var necessaryInfo: NecessaryInfo

    // Restic 辅助方法：获取仓库路径
    protected suspend fun getResticRepoPath(): String {
        // 从 DataStore 读取用户配置的路径，与 ResticViewModel 保持一致
        return mContext.readResticRepoPath() ?: File(mContext.filesDir, "restic_repo").absolutePath
    }

    // Restic 辅助方法：生成密码
    protected suspend fun getResticPassword(): String {
        // 从 DataStore 读取用户配置的密码，如果没有则使用默认值
        return mContext.readResticPassword() ?: "databackup_${mBackupTimestamp}"
    }

    // Restic 辅助方法：更新数据库中的快照信息（存根）
    private suspend fun updateResticInfo(packageName: String, snapshotId: String) {
        // 实际应用中，这里需要找到对应的 PackageEntity 并更新其 indexInfo.resticSnapshotId
        log { "Updated Restic info for $packageName with snapshot $snapshotId" }
        // 示例：
        /*
        mPkgEntities.find { it.packageEntity.packageName == packageName }?.apply {
            this.packageEntity.indexInfo.resticSnapshotId = snapshotId
            mPackageDao.upsert(this.packageEntity)
        }
        */
    }


    override suspend fun onPreprocessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.NECESSARY_PREPARATIONS -> {
                necessaryInfo = NecessaryInfo(inputMethods = PreparationUtil.getInputMethods().outString.trim(), accessibilityServices = PreparationUtil.getAccessibilityServices().outString.trim())
                log { "InputMethods: ${necessaryInfo.inputMethods}." }
                log { "AccessibilityServices: ${necessaryInfo.accessibilityServices}." }

                log { "Trying to create: $mAppsDir." }
                log { "Trying to create: $mConfigsDir." }
                mRootService.mkdirs(mAppsDir)
                mRootService.mkdirs(mConfigsDir)
                val isSuccess = runCatchingOnService { onTargetDirsCreated() }
                entity.update(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR)
            }
            else -> {}
        }
    }

    override suspend fun onProcessing() {
        mTaskEntity.update(rawBytes = mTaskRepo.getRawBytes(TaskType.PACKAGE), availableBytes = mTaskRepo.getAvailableBytes(OpType.BACKUP), totalBytes = mTaskRepo.getTotalBytes(OpType.BACKUP), totalCount = mPkgEntities.size)
        log { "Task count: ${mPkgEntities.size}." }

        val killAppOption = mContext.readKillAppOption().first()
        log { "Kill app option: $killAppOption" }

        for (index in mPkgEntities.indices) {
            if (isCanceled()) {
                log { "Backup canceled by user at index: $index" }
                break
            }

            val pkg = mPkgEntities[index]
            executeAtLeast {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    pkg.packageEntity.packageInfo.label,
                    mPkgEntities.size,
                    index
                )
                log { "Current package: ${pkg.packageEntity}" }

                killApp(killAppOption, pkg)

                pkg.update(state = OperationState.PROCESSING)
                val p = pkg.packageEntity
                val dstDir = "${mAppsDir}/${p.archivesRelativeDir}"
                var restoreEntity = mPackageDao.query(
                    p.packageName,
                    OpType.RESTORE,
                    p.userId,
                    p.indexInfo.compressionType,
                    mTaskEntity.cloud,
                    mTaskEntity.backupDir,
                    p.indexInfo.backupTimestamp
                )
                mRootService.mkdirs(dstDir)

                if (onAppDirCreated(archivesRelativeDir = p.archivesRelativeDir)) {
                    backup(type = DataType.PACKAGE_APK, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_USER, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_USER_DE, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_DATA, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_OBB, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_MEDIA, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)

                    if (isCanceled()) {
                        log { "Backup canceled after data backup, skipping config save" }
                        pkg.update(state = OperationState.ERROR)
                        mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                    } else {
                        mPackagesBackupUtil.backupPermissions(p = p)
                        mPackagesBackupUtil.backupSsaid(p = p)

                        if (isCanceled()) {
                            log { "Backup canceled before saving config" }
                            pkg.update(state = OperationState.ERROR)
                            mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                        } else if (pkg.isSuccess) {
                            p.extraInfo.lastBackupTime = DateUtil.getTimestamp()
                            val id = restoreEntity?.id ?: 0
                            restoreEntity = p.copy(
                                id = id,
                                indexInfo = p.indexInfo.copy(opType = OpType.RESTORE, cloud = mTaskEntity.cloud, backupDir = mTaskEntity.backupDir),
                                extraInfo = p.extraInfo.copy(activated = false)
                            )
                            val configDst = PathUtil.getPackageRestoreConfigDst(dstDir = dstDir)
                            mRootService.writeJson(data = restoreEntity, dst = configDst)
                            onConfigSaved(path = configDst, archivesRelativeDir = p.archivesRelativeDir)
                            mPackageDao.upsert(restoreEntity)
                            mPackageDao.upsert(p)
                            pkg.update(packageEntity = p)
                            mTaskEntity.update(successCount = mTaskEntity.successCount + 1)
                        } else {
                            log { "Backup failed for ${p.packageName}, cleaning up remote files..." }
                            runCatching {
                                onCleanupFailedBackup(archivesRelativeDir = p.archivesRelativeDir)
                            }.onFailure { e ->
                                log { "Failed to cleanup remote files: ${e.message}" }
                            }
                            mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                        }
                        pkg.update(state = if (pkg.isSuccess) OperationState.DONE else OperationState.ERROR)
                    }
                } else {
                    pkg.update(dataType = DataType.PACKAGE_APK, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_USER, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_USER_DE, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_DATA, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_OBB, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_MEDIA, state = OperationState.ERROR)
                    pkg.update(state = OperationState.ERROR)
                    mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                }
            }
            mTaskEntity.update(processingIndex = mTaskEntity.processingIndex + 1)
        }
    }

    // Restic 无状态备份方法
    protected suspend fun backupWithRestic(
        packageName: String,
        compressedFile: File
    ): Boolean {
        val repoPath = getResticRepoPath()
        val password = getResticPassword()

        // 动态检查仓库是否已初始化
        if (!resticRepo.checkRepository(repoPath, password)) {
            log { "Restic repository not initialized, skipping backup for $packageName" }
            return false
        }

        return try {
            val filePath = compressedFile.absolutePath
            val packageTag = "package:$packageName"
            val timestampTag = "timestamp:$mBackupTimestamp"
            val tags = listOf(packageTag, timestampTag, "compression:zstd")

            log { "Starting Restic backup for $packageName: $filePath" }
            val result = resticRepo.backupFile(repoPath, password, filePath, tags)

            if (result.first == 0) {
                log { "Restic backup completed successfully for $packageName" }
                updateResticInfo(packageName, result.second)
                true
            } else {
                val errorMsg = result.second
                log { "Restic backup failed for $packageName: $errorMsg" }
                false
            }
        } catch (e: Exception) {
            val baseMessage = "Error during Restic backup"
            log { "$baseMessage for $packageName" }
            log { "Exception type: ${e.javaClass.simpleName}" }
            log { "Exception message: ${e.message}" }
            false
        }
    }

    override suspend fun onPostProcessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.BACKUP_ITSELF -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.backup_itself)
                )
                if (mContext.readBackupItself().first()) {
                    log { "Backup itself enabled." }
                    mCommonBackupUtil.backupItself(dstDir = mRootDir).apply {
                        entity.set(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = outString)
                        if (isSuccess) {
                            onItselfSaved(path = mCommonBackupUtil.getItselfDst(mRootDir), entity = entity)
                        }
                    }
                    entity.update(progress = 1f)
                } else {
                    entity.update(progress = 1f, state = OperationState.SKIP)
                }
            }
            ProcessingInfoType.SAVE_ICONS -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.save_icons)
                )
                mPackagesBackupUtil.backupIcons(dstDir = mConfigsDir).apply {
                    entity.set(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = outString)
                    if (isSuccess) {
                        onIconsSaved(path = mPackagesBackupUtil.getIconsDst(mConfigsDir), entity = entity)
                    }
                }
                entity.update(progress = 1f)
            }

            ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.wait_for_remaining_data_processing)
                )

                var isSuccess = true
                val out = mutableListOf<String>()
                if (mContext.readBackupConfigs().first()) {
                    log { "Backup configs enabled." }
                    mCommonBackupUtil.backupConfigs(dstDir = mConfigsDir).also { result ->
                        if (result.isSuccess.not()) {
                            isSuccess = false
                        }
                        out.add(result.outString)
                        if (result.isSuccess) {
                            onConfigsSaved(path = mCommonBackupUtil.getConfigsDst(mConfigsDir), entity = entity)
                        }
                    }
                }
                entity.update(progress = 0.5f)

                // Restore keyboard and services.
                if (necessaryInfo.inputMethods.isNotEmpty()) {
                    PreparationUtil.setInputMethods(inputMethods = necessaryInfo.inputMethods)
                    log { "InputMethods restored: ${necessaryInfo.inputMethods}." }
                } else {
                    log { "InputMethods is empty, skip restoring." }
                }
                if (necessaryInfo.accessibilityServices.isNotEmpty()) {
                    PreparationUtil.setAccessibilityServices(accessibilityServices = necessaryInfo.accessibilityServices)
                    log { "AccessibilityServices restored: ${necessaryInfo.accessibilityServices}." }
                } else {
                    log { "AccessibilityServices is empty, skip restoring." }
                }
                if (mContext.readResetBackupList().first() && mTaskEntity.failureCount == 0) {
                    mPackageDao.clearActivated(OpType.BACKUP)
                }
                if (runCatchingOnService { clear() }.not()) {
                    isSuccess = false
                }
                entity.set(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
            }

            else -> {}
        }
    }

    override suspend fun afterPostProcessing() {
        mContext.saveLastBackupTime(mEndTimestamp)
        val time = DateUtil.getShortRelativeTimeSpanString(context = mContext, time1 = mStartTimestamp, time2 = mEndTimestamp)
        NotificationUtil.notify(
            mContext,
            mNotificationBuilder,
            mContext.getString(R.string.backup_completed),
            "${time}, ${mTaskEntity.successCount} ${mContext.getString(R.string.succeed)}, ${mTaskEntity.failureCount} ${mContext.getString(R.string.failed)}",
            ongoing = false
        )
    }
}