package com.xayah.core.service.packages.restore

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
import com.xayah.core.service.util.PackagesRestoreUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import java.io.File

@AndroidEntryPoint
internal class RestoreServiceLocalImpl @Inject constructor() : AbstractRestoreService() {
    override val mTAG: String = "RestoreServiceLocalImpl"

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
            opType = OpType.RESTORE,
            taskType = TaskType.PACKAGE,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    override suspend fun getPackages(): List<PackageEntity> {
        // 检查是否为 Restic 恢复场景
        val restoreDir = File("${mRootDir}/restore")
        val backupDir = if (restoreDir.exists()) {
            Log.d(mTAG, "检测到 Restic 恢复场景，使用 restore 子目录")
            "${mRootDir}/restore/"
        } else {
            Log.d(mTAG, "使用标准恢复路径")
            mRootDir
        }

        Log.d(mTAG, "查询参数: cloud=, backupDir=$backupDir")
        val packages = mPackageRepo.queryActivated(OpType.RESTORE, "", backupDir)
        Log.d(mTAG, "查询到 ${packages.size} 个激活的应用")

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

    @Inject
    override lateinit var mPackageDao: PackageDao

    @Inject
    override lateinit var mPackageRepo: PackageRepository

    @Inject
    override lateinit var mPackagesRestoreUtil: PackagesRestoreUtil

    override val mRootDir by lazy { mContext.localBackupSaveDir() }
    override val mAppsDir by lazy { mPathUtil.getLocalBackupAppsDir() }
    override val mConfigsDir by lazy { mPathUtil.getLocalBackupConfigsDir() }
}
