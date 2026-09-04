package com.xayah.core.service.packages.restore

import android.util.Log
import android.content.Intent
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

    private var mTargetPackageName: String = ""

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
        Log.d(mTAG, "Intent: $intent")
        Log.d(mTAG, "Flags: $flags, StartId: $startId")

        // 检查restore目录
        val restoreDir = File("${mRootDir}/restore")
        Log.d(mTAG, "检查restore目录: ${restoreDir.path}")
        Log.d(mTAG, "restore目录存在: ${restoreDir.exists()}")

        return super.onStartCommand(intent, flags, startId)
    }

    override suspend fun getPackages(): List<PackageEntity> {
        Log.d(mTAG, "=== 开始获取恢复包列表 ===")

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
        val allPackages = mPackageRepo.queryActivated(OpType.RESTORE, "", backupDir)
        Log.d(mTAG, "查询到总应用数: ${allPackages.size}")

        // 使用传递的包名进行筛选
        val packages = if (mTargetPackageName.isNotEmpty()) {
            Log.d(mTAG, "筛选目标包名: $mTargetPackageName")
            allPackages.filter { it.packageName == mTargetPackageName }
        } else {
            Log.d(mTAG, "无包名筛选，返回所有应用")
            allPackages
        }

        Log.d(mTAG, "筛选后查询到 ${packages.size} 个应用")
        packages.forEach { pkg ->
            Log.d(mTAG, "应用: ${pkg.packageName}, 用户: ${pkg.userId}, 激活: ${pkg.extraInfo.activated}")
        }

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

    override suspend fun clear() {
        if (mTaskEntity.failureCount != 0) {
            Log.d(mTAG, "存在失败项(failureCount=${mTaskEntity.failureCount})，保留中转目录用于排查/重试，跳过清理")
            return
        }
        val restoreAppsDir = "${mRootDir}/restore/apps"
        if (File(restoreAppsDir).exists()) {
            Log.d(mTAG, "清理临时恢复目录: $restoreAppsDir")
            mRootService.deleteRecursively(restoreAppsDir)
        } else {
            Log.d(mTAG, "临时恢复目录不存在，跳过清理: $restoreAppsDir")
        }
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
