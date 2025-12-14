package com.xayah.core.service.packages.backup

import android.util.Log
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.model.OperationState
import com.xayah.core.model.util.get
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import java.io.File

@AndroidEntryPoint
internal class BackupServiceLocalImpl @Inject constructor() : AbstractBackupService() {
    override val mTAG: String = "BackupServiceLocalImpl"

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

    override val mTaskEntity by lazy {
        TaskEntity(
            id = 0,
            opType = OpType.BACKUP,
            taskType = TaskType.PACKAGE,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    override suspend fun backup(type: DataType, p: PackageEntity, r: PackageEntity?, t: TaskDetailPackageEntity, dstDir: String) {
        val result = if (type == DataType.PACKAGE_APK) {
            mPackagesBackupUtil.backupApk(
                p = p,
                t = t,
                r = r,
                dstDir = dstDir,
                isCanceled = { isCanceled() }
            )
        } else {
            mPackagesBackupUtil.backupData(
                p = p,
                t = t,
                r = r,
                dataType = type,
                dstDir = dstDir,
                isCanceled = { isCanceled() }
            )
        }

        // 检查备份结果,如果失败(可能是取消导致)则立即返回
        if (!result.isSuccess) {
            log { "COMPRESSION_FAILED: Backup compression failed for ${p.packageName} $type, skipping Restic backup" }
            return
        }

        // 新增：在压缩完成后使用 Restic 进行块备份
        if (result.isSuccess && t.get(type).state != OperationState.SKIP) {
            Log.d("ResticFlow", "About to call backupWithRestic for ${p.packageName} $type")
            // 查找压缩文件
            val compressedFile = findCompressedFile(dstDir, type)
            if (compressedFile != null) {
                log { "COMPRESSED_FILE_FOUND: Found compressed file for $type at ${compressedFile.absolutePath}" }
                // 调用 Restic 备份
                val resticSuccess = backupWithRestic(p.packageName, compressedFile, type)
                Log.d("ResticFlow", "backupWithRestic returned: $resticSuccess")
                if (resticSuccess) {
                    log { "Restic backup successful for ${p.packageName} $type" }
                } else {
                    log { "Restic backup failed for ${p.packageName} $type" }
                }
            } else {
                log { "COMPRESSED_FILE_NOT_FOUND: No compressed file found for $type at ${dstDir}/${type.type}.tar.zst" }
            }
        }

        t.update(dataType = type, progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    // 辅助方法：查找压缩文件
    private fun findCompressedFile(dstDir: String, dataType: DataType): File? {
        val file = when (dataType) {
            DataType.PACKAGE_APK -> File("$dstDir/${DataType.PACKAGE_APK.type}.tar.zst")
            DataType.PACKAGE_USER -> File("$dstDir/${DataType.PACKAGE_USER.type}.tar.zst")
            DataType.PACKAGE_USER_DE -> File("$dstDir/${DataType.PACKAGE_USER_DE.type}.tar.zst")
            DataType.PACKAGE_DATA -> File("$dstDir/${DataType.PACKAGE_DATA.type}.tar.zst")
            DataType.PACKAGE_OBB -> File("$dstDir/${DataType.PACKAGE_OBB.type}.tar.zst")
            DataType.PACKAGE_MEDIA -> File("$dstDir/${DataType.PACKAGE_MEDIA.type}.tar.zst")
            DataType.PACKAGE_CONFIG -> File("$dstDir/package_restore_config.json")
            else -> null
        }

        return file?.takeIf {
            if (it.exists()) {
                log { "COMPRESSED_FILE_FOUND: Found compressed file for $dataType at ${it.absolutePath}" }
                true
            } else {
                log { "COMPRESSED_FILE_NOT_FOUND: No compressed file found for $dataType at ${it.absolutePath}" }
                false
            }
        }
    }

    @Inject
    override lateinit var mPackageDao: PackageDao

    @Inject
    override lateinit var mPackageRepo: PackageRepository

    @Inject
    override lateinit var mPackagesBackupUtil: PackagesBackupUtil

    override val mRootDir by lazy { mContext.localBackupSaveDir() }
    override val mAppsDir by lazy { mPathUtil.getLocalBackupAppsDir() }
    override val mConfigsDir by lazy { mPathUtil.getLocalBackupConfigsDir() }

    // 实现清理未完成备份的逻辑
    override suspend fun onCleanupIncompleteBackup(currentIndex: Int) {
        log { "Cleaning up incomplete local backup from index: $currentIndex" }

        mPkgEntities.forEachIndexed { index, pkg ->
            if (index >= currentIndex) {
                val localAppDir = "${mAppsDir}/${pkg.packageEntity.archivesRelativeDir}"
                log { "Cleaning up incomplete backup at: $localAppDir" }
                runCatching {
                    mRootService.deleteRecursively(localAppDir)
                }.onSuccess {
                    log { "Successfully cleaned up: $localAppDir" }
                }.onFailure { e ->
                    log { "Failed to cleanup: ${e.message}" }
                }
            }
        }
    }
}
