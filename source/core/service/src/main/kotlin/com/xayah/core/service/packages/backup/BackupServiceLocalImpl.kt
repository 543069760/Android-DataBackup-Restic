package com.xayah.core.service.packages.backup

import android.util.Log
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.model.util.get
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

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

        // 在tar完成后使用 Restic 进行块备份
        if (result.isSuccess && t.get(type).state != OperationState.SKIP) {
            Log.d("ResticFlow", "About to call backupWithRestic for ${p.packageName} $type")
            val compressedFile = findCompressedFile(dstDir, type)
            if (compressedFile != null) {
                log { "COMPRESSED_FILE_FOUND: Found compressed file for $type at ${compressedFile.absolutePath}" }

                val resticSuccess = backupWithRestic(p.packageName, compressedFile, type, t)

                Log.d("ResticFlow", "backupWithRestic returned: $resticSuccess")
                if (resticSuccess) {
                    log { "Restic backup successful for ${p.packageName} $type" }
                } else {
                    log { "Restic backup failed for ${p.packageName} $type" }
                }
            } else {
                log { "COMPRESSED_FILE_NOT_FOUND: No compressed file found for $type at ${dstDir}/${type.type}.tar" }
            }
        }
        t.update(dataType = type, progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    // 辅助方法：查找APK backup文件
    private fun findCompressedFile(dstDir: String, dataType: DataType): File? {
        val file = when (dataType) {
            DataType.PACKAGE_APK -> File("$dstDir/${DataType.PACKAGE_APK.type}.tar")
            DataType.PACKAGE_USER -> File("$dstDir/${DataType.PACKAGE_USER.type}.tar")
            DataType.PACKAGE_USER_DE -> File("$dstDir/${DataType.PACKAGE_USER_DE.type}.tar")
            DataType.PACKAGE_DATA -> File("$dstDir/${DataType.PACKAGE_DATA.type}.tar")
            DataType.PACKAGE_OBB -> File("$dstDir/${DataType.PACKAGE_OBB.type}.tar")
            DataType.PACKAGE_MEDIA -> File("$dstDir/${DataType.PACKAGE_MEDIA.type}.tar")
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

        log { "Cleaning up temporary directory: $mRootDir" }
        runCatching {
            mRootService.deleteRecursively(mRootDir)
        }.onSuccess {
            log { "Successfully deleted temporary directory" }
        }.onFailure { e ->
            log { "Failed to delete temporary directory: ${e.message}" }
        }

        cleanupStopFiles()
    }

    override suspend fun onIconsSaved(path: String, entity: ProcessingInfoEntity) {
        val iconFile = File(path)
        if (!iconFile.exists()) {
            log { "SAVE_ICONS: icon.tar not found at $path, skip icon snapshot" }
            return
        }

        try {
            val repoPath = getResticRepoPath()
            val password = getResticPassword()
            // 专用标签，非 user_X-包名-时间戳-类型 四段式，避免被 parseAppsDb 当成应用
            val tag = "__icons__-local-$mBackupTimestamp"

            val result = resticRepo.backupWithResticToLocal(
                repoPath = repoPath,
                password = password,
                filePath = path,
                tags = listOf(tag),
                cancelId = System.nanoTime()
            )

            if (result.first == 0) {
                log { "SAVE_ICONS: icon snapshot pushed to local repo, tag=$tag" }
            } else {
                log { "SAVE_ICONS: icon snapshot failed, code=${result.first}, msg=${result.second}" }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 图标失败不影响主备份流程
            log { "SAVE_ICONS: icon snapshot exception: ${e.message}" }
        }
    }

    override suspend fun clear() {
        log { "Attempting to delete local backup directory: $mRootDir" }
        val result = runCatching {
            mRootService.deleteRecursively(mRootDir)
        }
        if (result.isSuccess) {
            log { "Successfully deleted local backup directory" }
        } else {
            log { "Failed to delete local backup directory: ${result.exceptionOrNull()?.message}" }
        }

        cleanupStopFiles()
    }
}