package com.xayah.core.restic;

import android.content.Context;
import android.util.Log;
import com.topjohnwu.superuser.Shell;
import com.xayah.core.util.GsonUtil;
import com.xayah.core.model.DataType;
import com.xayah.core.model.restic.ResticBackupApp;
import com.xayah.core.model.restic.ResticBackupFiles;
import com.xayah.core.model.database.S3Extra;
import com.xayah.core.model.database.S3Protocol;
import com.xayah.core.model.database.CloudEntity;
import dagger.hilt.android.qualifiers.ApplicationContext;
import kotlinx.coroutines.Dispatchers;
import kotlinx.serialization.Serializable;
import java.io.File;
import javax.inject.Inject;
import javax.inject.Singleton;

@javax.inject.Singleton()
@kotlin.Metadata(mv = {2, 1, 0}, k = 1, xi = 48, d1 = {"\u0000\u009e\u0001\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0010\u000e\n\u0002\b\u0005\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0011\n\u0000\n\u0002\u0010$\n\u0000\n\u0002\u0010\u000b\n\u0002\b\u0002\n\u0002\u0010 \n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0006\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0007\n\u0002\u0018\u0002\n\u0002\b\u0006\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0010\n\u0002\u0018\u0002\n\u0002\u0010\b\n\u0002\b\u000b\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0004\b\u0007\u0018\u0000 _2\u00020\u0001:\u0003_`aB#\b\u0007\u0012\b\b\u0001\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0005\u0012\u0006\u0010\u0006\u001a\u00020\u0007\u00a2\u0006\u0004\b\b\u0010\tJB\u0010\u0010\u001a\u00020\u00112\u0012\u0010\u0012\u001a\n\u0012\u0006\b\u0001\u0012\u00020\u000b0\u0013\"\u00020\u000b2\u0014\b\u0002\u0010\u0014\u001a\u000e\u0012\u0004\u0012\u00020\u000b\u0012\u0004\u0012\u00020\u000b0\u00152\b\b\u0002\u0010\u0016\u001a\u00020\u0017H\u0082@\u00a2\u0006\u0002\u0010\u0018J$\u0010\u0019\u001a\b\u0012\u0004\u0012\u00020\u001b0\u001a2\u0006\u0010\u001c\u001a\u00020\u001d2\u0006\u0010\u001e\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0002\u0010\u001fJ\u0010\u0010 \u001a\u00020\u000b2\u0006\u0010!\u001a\u00020\u000bH\u0002J\u0010\u0010\"\u001a\u00020\u000b2\u0006\u0010#\u001a\u00020$H\u0002J\u0016\u0010%\u001a\b\u0012\u0004\u0012\u00020\u001b0\u001a2\u0006\u0010&\u001a\u00020\'H\u0002JR\u0010(\u001a\u00020\u00172\u0006\u0010)\u001a\u00020\u000b2\u0006\u0010\u001e\u001a\u00020\u000b2\u0006\u0010*\u001a\u00020\u000b2\u0006\u0010+\u001a\u00020\u000b2\n\b\u0002\u0010,\u001a\u0004\u0018\u00010\u000b2\n\b\u0002\u0010-\u001a\u0004\u0018\u00010\u000b2\n\b\u0002\u0010.\u001a\u0004\u0018\u00010/H\u0086@\u00a2\u0006\u0002\u00100JR\u00101\u001a\u00020\u00172\u0006\u0010\u001c\u001a\u00020\u001d2\u0006\u0010\u001e\u001a\u00020\u000b2\u0006\u0010*\u001a\u00020\u000b2\u0006\u0010+\u001a\u00020\u000b2\n\b\u0002\u0010,\u001a\u0004\u0018\u00010\u000b2\n\b\u0002\u0010-\u001a\u0004\u0018\u00010\u000b2\n\b\u0002\u0010.\u001a\u0004\u0018\u00010/H\u0086@\u00a2\u0006\u0002\u00102J\u0010\u00103\u001a\u0004\u0018\u00010\u000bH\u0086@\u00a2\u0006\u0002\u00104J&\u00105\u001a\b\u0012\u0004\u0012\u00020\u000b062\u0006\u0010)\u001a\u00020\u000b2\u0006\u0010\u001e\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0004\b7\u00108J$\u00109\u001a\b\u0012\u0004\u0012\u00020:0\u001a2\u0006\u0010)\u001a\u00020\u000b2\u0006\u0010\u001e\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0002\u00108J\u0016\u0010;\u001a\b\u0012\u0004\u0012\u00020:0\u001a2\u0006\u0010&\u001a\u00020\'H\u0002J$\u0010<\u001a\b\u0012\u0004\u0012\u00020=0\u001a2\u0006\u0010)\u001a\u00020\u000b2\u0006\u0010\u001e\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0002\u00108J\u0016\u0010>\u001a\b\u0012\u0004\u0012\u00020=0\u001a2\u0006\u0010&\u001a\u00020\'H\u0002J&\u0010?\u001a\u00020\u00172\u0006\u0010\u001c\u001a\u00020\u001d2\u0006\u0010\u001e\u001a\u00020\u000b2\u0006\u0010*\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0002\u0010@J\u001e\u0010A\u001a\u00020\u00172\u0006\u0010\u001c\u001a\u00020\u001d2\u0006\u0010\u001e\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0002\u0010\u001fJ&\u0010B\u001a\u00020\u00172\u0006\u0010)\u001a\u00020\u000b2\u0006\u0010\u001e\u001a\u00020\u000b2\u0006\u0010*\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0002\u0010CJ\u001e\u0010D\u001a\u00020\u00172\u0006\u0010)\u001a\u00020\u000b2\u0006\u0010\u001e\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0002\u00108J\u001e\u0010E\u001a\u00020\u00172\u0006\u0010)\u001a\u00020\u000b2\u0006\u0010\u001e\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0002\u00108J\u0016\u0010F\u001a\u00020\u00172\u0006\u0010)\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0002\u0010GJ\u001e\u0010H\u001a\u00020\u00172\u0006\u0010)\u001a\u00020\u000b2\u0006\u0010\u001e\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0002\u00108J$\u0010I\u001a\b\u0012\u0004\u0012\u00020\u001b0\u001a2\u0006\u0010)\u001a\u00020\u000b2\u0006\u0010\u001e\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0002\u00108J$\u0010J\u001a\b\u0012\u0004\u0012\u00020=0\u001a2\u0006\u0010\u001c\u001a\u00020\u001d2\u0006\u0010\u001e\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0002\u0010\u001fJ$\u0010K\u001a\b\u0012\u0004\u0012\u00020=0\u001a2\u0006\u0010\u001c\u001a\u00020\u001d2\u0006\u0010\u001e\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0002\u0010\u001fJ$\u0010L\u001a\b\u0012\u0004\u0012\u00020\u001b0\u001a2\u0006\u0010\u001c\u001a\u00020\u001d2\u0006\u0010\u001e\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0002\u0010\u001fJb\u0010M\u001a\u000e\u0012\u0004\u0012\u00020O\u0012\u0004\u0012\u00020\u000b0N2\u0006\u0010)\u001a\u00020\u000b2\u0006\u0010\u001e\u001a\u00020\u000b2\u0006\u0010P\u001a\u00020\u000b2\f\u0010Q\u001a\b\u0012\u0004\u0012\u00020\u000b0\u001a2\u0014\b\u0002\u0010R\u001a\u000e\u0012\u0004\u0012\u00020\u000b\u0012\u0004\u0012\u00020\u000b0\u00152\n\b\u0002\u0010.\u001a\u0004\u0018\u00010/H\u0086@\u00a2\u0006\u0002\u0010SJ\u0016\u0010T\u001a\u00020\u000b2\u0006\u0010#\u001a\u00020$2\u0006\u0010!\u001a\u00020\u000bJ.\u0010U\u001a\b\u0012\u0004\u0012\u00020\u000b062\u0006\u0010#\u001a\u00020$2\u0006\u0010!\u001a\u00020\u000b2\u0006\u0010\u001e\u001a\u00020\u000bH\u0086@\u00a2\u0006\u0004\bV\u0010WJT\u0010X\u001a\u000e\u0012\u0004\u0012\u00020O\u0012\u0004\u0012\u00020\u000b0N2\u0006\u0010#\u001a\u00020$2\u0006\u0010!\u001a\u00020\u000b2\u0006\u0010P\u001a\u00020\u000b2\f\u0010Q\u001a\b\u0012\u0004\u0012\u00020\u000b0\u001a2\u0006\u0010\u001e\u001a\u00020\u000b2\n\b\u0002\u0010.\u001a\u0004\u0018\u00010/H\u0086@\u00a2\u0006\u0002\u0010YJ\u0012\u0010Z\u001a\u0004\u0018\u00010[2\u0006\u0010\\\u001a\u00020\u000bH\u0002J\u0012\u0010]\u001a\u0004\u0018\u00010^2\u0006\u0010\\\u001a\u00020\u000bH\u0002R\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0004\u001a\u00020\u0005X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0006\u001a\u00020\u0007X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u001b\u0010\n\u001a\u00020\u000b8BX\u0082\u0084\u0002\u00a2\u0006\f\n\u0004\b\u000e\u0010\u000f\u001a\u0004\b\f\u0010\r\u00a8\u0006b"}, d2 = {"Lcom/xayah/core/restic/ResticRepository;", "", "context", "Landroid/content/Context;", "logger", "Lcom/xayah/core/restic/ResticLogger;", "resticNative", "Lcom/xayah/core/restic/ResticNative;", "<init>", "(Landroid/content/Context;Lcom/xayah/core/restic/ResticLogger;Lcom/xayah/core/restic/ResticNative;)V", "resticPath", "", "getResticPath", "()Ljava/lang/String;", "resticPath$delegate", "Lkotlin/Lazy;", "executeRestic", "Lcom/topjohnwu/superuser/Shell$Result;", "args", "", "env", "", "usePty", "", "([Ljava/lang/String;Ljava/util/Map;ZLkotlin/coroutines/Continuation;)Ljava/lang/Object;", "listBackedUpAppsFromS3WithSql", "", "Lcom/xayah/core/model/restic/ResticBackupApp;", "cloudEntity", "Lcom/xayah/core/model/database/CloudEntity;", "password", "(Lcom/xayah/core/model/database/CloudEntity;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "formatOpenDALRoot", "remotePath", "buildOpenDALEndpoint", "extra", "Lcom/xayah/core/model/database/S3Extra;", "parseSqlFileForApps", "sqlFile", "Ljava/io/File;", "restoreSnapshot", "repoPath", "snapshotId", "targetPath", "snapshotSubPath", "includePath", "progressCallback", "Lcom/xayah/core/restic/ResticRepository$ResticProgressCallback;", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/xayah/core/restic/ResticRepository$ResticProgressCallback;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "restoreSnapshotFromS3", "(Lcom/xayah/core/model/database/CloudEntity;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/xayah/core/restic/ResticRepository$ResticProgressCallback;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "getVersion", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "initRepository", "Lkotlin/Result;", "initRepository-0E7RQCE", "(Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "listSnapshots", "Lcom/xayah/core/restic/ResticSnapshot;", "parseSqlFileForSnapshots", "listBackedUpFiles", "Lcom/xayah/core/model/restic/ResticBackupFiles;", "parseSqlFileForFiles", "forgetSnapshotFromS3", "(Lcom/xayah/core/model/database/CloudEntity;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "pruneS3Repository", "forgetSnapshot", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "pruneRepository", "validateRepository", "deleteRepository", "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "checkRepository", "listBackedUpApps", "listBackedUpFilesFromS3WithSql", "listBackedUpFilesFromS3", "listBackedUpAppsFromS3", "backupWithResticToLocal", "Lkotlin/Pair;", "", "filePath", "tags", "additionalEnv", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Ljava/util/Map;Lcom/xayah/core/restic/ResticRepository$ResticProgressCallback;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "buildS3ResticUrl", "initS3Repository", "initS3Repository-BWLJW6A", "(Lcom/xayah/core/model/database/S3Extra;Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "backupFileToS3", "(Lcom/xayah/core/model/database/S3Extra;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Ljava/lang/String;Lcom/xayah/core/restic/ResticRepository$ResticProgressCallback;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "parseBackupProgress", "Lcom/xayah/core/restic/ResticRepository$ResticBackupProgress;", "line", "parseRestoreProgress", "Lcom/xayah/core/restic/ResticRestoreProgress;", "Companion", "ResticBackupProgress", "ResticProgressCallback", "restic_debug"})
public final class ResticRepository {
    @org.jetbrains.annotations.NotNull()
    private final android.content.Context context = null;
    @org.jetbrains.annotations.NotNull()
    private final com.xayah.core.restic.ResticLogger logger = null;
    @org.jetbrains.annotations.NotNull()
    private final com.xayah.core.restic.ResticNative resticNative = null;
    @org.jetbrains.annotations.NotNull()
    private static final java.lang.String TAG = "ResticRepository";
    @org.jetbrains.annotations.NotNull()
    private static final kotlinx.serialization.json.Json json = null;
    @org.jetbrains.annotations.NotNull()
    private final kotlin.Lazy resticPath$delegate = null;
    @org.jetbrains.annotations.NotNull()
    public static final com.xayah.core.restic.ResticRepository.Companion Companion = null;
    
    @javax.inject.Inject()
    public ResticRepository(@dagger.hilt.android.qualifiers.ApplicationContext()
    @org.jetbrains.annotations.NotNull()
    android.content.Context context, @org.jetbrains.annotations.NotNull()
    com.xayah.core.restic.ResticLogger logger, @org.jetbrains.annotations.NotNull()
    com.xayah.core.restic.ResticNative resticNative) {
        super();
    }
    
    private final java.lang.String getResticPath() {
        return null;
    }
    
    /**
     * 核心执行方法:使用 libsu 执行 Root 命令
     * @param usePty 是否使用 PTY 模拟(用于需要终端的命令,如 --sql)
     */
    private final java.lang.Object executeRestic(java.lang.String[] args, java.util.Map<java.lang.String, java.lang.String> env, boolean usePty, kotlin.coroutines.Continuation<? super com.topjohnwu.superuser.Shell.Result> $completion) {
        return null;
    }
    
    /**
     * 使用 Rustic OpenDAL SQL 模式从 S3 获取应用备份列表
     */
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object listBackedUpAppsFromS3WithSql(@org.jetbrains.annotations.NotNull()
    com.xayah.core.model.database.CloudEntity cloudEntity, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.xayah.core.model.restic.ResticBackupApp>> $completion) {
        return null;
    }
    
    /**
     * 格式化 OpenDAL Root 路径
     * 确保以 / 开头,以 / 结尾(与您的命令示例一致)
     */
    private final java.lang.String formatOpenDALRoot(java.lang.String remotePath) {
        return null;
    }
    
    /**
     * 构建 OpenDAL Endpoint
     * 格式: protocol://endpoint (不包含 bucket)
     */
    private final java.lang.String buildOpenDALEndpoint(com.xayah.core.model.database.S3Extra extra) {
        return null;
    }
    
    /**
     * 从 SQL 文件解析应用备份信息(使用 v_snapshots_full 视图)
     */
    private final java.util.List<com.xayah.core.model.restic.ResticBackupApp> parseSqlFileForApps(java.io.File sqlFile) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object restoreSnapshot(@org.jetbrains.annotations.NotNull()
    java.lang.String repoPath, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    java.lang.String snapshotId, @org.jetbrains.annotations.NotNull()
    java.lang.String targetPath, @org.jetbrains.annotations.Nullable()
    java.lang.String snapshotSubPath, @org.jetbrains.annotations.Nullable()
    java.lang.String includePath, @org.jetbrains.annotations.Nullable()
    com.xayah.core.restic.ResticRepository.ResticProgressCallback progressCallback, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.Boolean> $completion) {
        return null;
    }
    
    /**
     * 从 S3 恢复快照（包含完整 S3 环境变量）
     */
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object restoreSnapshotFromS3(@org.jetbrains.annotations.NotNull()
    com.xayah.core.model.database.CloudEntity cloudEntity, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    java.lang.String snapshotId, @org.jetbrains.annotations.NotNull()
    java.lang.String targetPath, @org.jetbrains.annotations.Nullable()
    java.lang.String snapshotSubPath, @org.jetbrains.annotations.Nullable()
    java.lang.String includePath, @org.jetbrains.annotations.Nullable()
    com.xayah.core.restic.ResticRepository.ResticProgressCallback progressCallback, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.Boolean> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object getVersion(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.String> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object listSnapshots(@org.jetbrains.annotations.NotNull()
    java.lang.String repoPath, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.xayah.core.restic.ResticSnapshot>> $completion) {
        return null;
    }
    
    /**
     * 从 SQL 文件解析快照信息
     */
    private final java.util.List<com.xayah.core.restic.ResticSnapshot> parseSqlFileForSnapshots(java.io.File sqlFile) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object listBackedUpFiles(@org.jetbrains.annotations.NotNull()
    java.lang.String repoPath, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.xayah.core.model.restic.ResticBackupFiles>> $completion) {
        return null;
    }
    
    /**
     * 从 SQL 文件解析文件备份信息
     */
    private final java.util.List<com.xayah.core.model.restic.ResticBackupFiles> parseSqlFileForFiles(java.io.File sqlFile) {
        return null;
    }
    
    /**
     * 从 S3 删除单个快照
     */
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object forgetSnapshotFromS3(@org.jetbrains.annotations.NotNull()
    com.xayah.core.model.database.CloudEntity cloudEntity, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    java.lang.String snapshotId, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.Boolean> $completion) {
        return null;
    }
    
    /**
     * 清理 S3 仓库中未引用的数据
     */
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object pruneS3Repository(@org.jetbrains.annotations.NotNull()
    com.xayah.core.model.database.CloudEntity cloudEntity, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.Boolean> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object forgetSnapshot(@org.jetbrains.annotations.NotNull()
    java.lang.String repoPath, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    java.lang.String snapshotId, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.Boolean> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object pruneRepository(@org.jetbrains.annotations.NotNull()
    java.lang.String repoPath, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.Boolean> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object validateRepository(@org.jetbrains.annotations.NotNull()
    java.lang.String repoPath, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.Boolean> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object deleteRepository(@org.jetbrains.annotations.NotNull()
    java.lang.String repoPath, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.Boolean> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object checkRepository(@org.jetbrains.annotations.NotNull()
    java.lang.String repoPath, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.Boolean> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object listBackedUpApps(@org.jetbrains.annotations.NotNull()
    java.lang.String repoPath, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.xayah.core.model.restic.ResticBackupApp>> $completion) {
        return null;
    }
    
    /**
     * 使用 Rustic OpenDAL SQL 模式从 S3 获取文件备份列表
     */
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object listBackedUpFilesFromS3WithSql(@org.jetbrains.annotations.NotNull()
    com.xayah.core.model.database.CloudEntity cloudEntity, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.xayah.core.model.restic.ResticBackupFiles>> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object listBackedUpFilesFromS3(@org.jetbrains.annotations.NotNull()
    com.xayah.core.model.database.CloudEntity cloudEntity, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.xayah.core.model.restic.ResticBackupFiles>> $completion) {
        return null;
    }
    
    /**
     * 从 S3 仓库获取备份的应用列表
     */
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object listBackedUpAppsFromS3(@org.jetbrains.annotations.NotNull()
    com.xayah.core.model.database.CloudEntity cloudEntity, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.xayah.core.model.restic.ResticBackupApp>> $completion) {
        return null;
    }
    
    /**
     * 使用 Restic 备份到本地仓库
     * @param repoPath 本地仓库路径
     * @param password 仓库密码
     * @param filePath 要备份的文件路径
     * @param tags 备份标签
     * @param progressCallback 进度回调
     * @return Pair<Int, String> 退出码和 JSON 输出
     */
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object backupWithResticToLocal(@org.jetbrains.annotations.NotNull()
    java.lang.String repoPath, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    java.lang.String filePath, @org.jetbrains.annotations.NotNull()
    java.util.List<java.lang.String> tags, @org.jetbrains.annotations.NotNull()
    java.util.Map<java.lang.String, java.lang.String> additionalEnv, @org.jetbrains.annotations.Nullable()
    com.xayah.core.restic.ResticRepository.ResticProgressCallback progressCallback, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Pair<java.lang.Integer, java.lang.String>> $completion) {
        return null;
    }
    
    /**
     * 构建通用的 S3 Restic URL（最稳妥版）
     * 确保协议、Endpoint、Bucket 和 RemotePath 之间的斜杠处理万无一失
     */
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String buildS3ResticUrl(@org.jetbrains.annotations.NotNull()
    com.xayah.core.model.database.S3Extra extra, @org.jetbrains.annotations.NotNull()
    java.lang.String remotePath) {
        return null;
    }
    
    /**
     * 使用 S3 后端备份文件
     * @param extra S3 配置信息
     * @param remotePath 远程路径
     * @param filePath 要备份的文件路径
     * @param tags 备份标签
     * @param password 仓库密码
     * @param progressCallback 进度回调
     * @return Pair<Int, String> 退出码和 JSON 输出
     */
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object backupFileToS3(@org.jetbrains.annotations.NotNull()
    com.xayah.core.model.database.S3Extra extra, @org.jetbrains.annotations.NotNull()
    java.lang.String remotePath, @org.jetbrains.annotations.NotNull()
    java.lang.String filePath, @org.jetbrains.annotations.NotNull()
    java.util.List<java.lang.String> tags, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.Nullable()
    com.xayah.core.restic.ResticRepository.ResticProgressCallback progressCallback, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Pair<java.lang.Integer, java.lang.String>> $completion) {
        return null;
    }
    
    /**
     * 解析备份进度信息
     */
    private final com.xayah.core.restic.ResticRepository.ResticBackupProgress parseBackupProgress(java.lang.String line) {
        return null;
    }
    
    private final com.xayah.core.restic.ResticRestoreProgress parseRestoreProgress(java.lang.String line) {
        return null;
    }
    
    @kotlin.Metadata(mv = {2, 1, 0}, k = 1, xi = 48, d1 = {"\u0000\u0018\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0003\n\u0002\u0010\u000e\n\u0000\n\u0002\u0018\u0002\n\u0000\b\u0086\u0003\u0018\u00002\u00020\u0001B\t\b\u0002\u00a2\u0006\u0004\b\u0002\u0010\u0003R\u000e\u0010\u0004\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0006\u001a\u00020\u0007X\u0082\u0004\u00a2\u0006\u0002\n\u0000\u00a8\u0006\b"}, d2 = {"Lcom/xayah/core/restic/ResticRepository$Companion;", "", "<init>", "()V", "TAG", "", "json", "Lkotlinx/serialization/json/Json;", "restic_debug"})
    public static final class Companion {
        
        private Companion() {
            super();
        }
    }
    
    /**
     * Restic 备份进度数据类
     */
    @kotlinx.serialization.Serializable()
    @kotlin.Metadata(mv = {2, 1, 0}, k = 1, xi = 48, d1 = {"\u00006\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\u0007\n\u0000\n\u0002\u0010\t\n\u0002\b\u0007\n\u0002\u0010 \n\u0002\b\u001f\n\u0002\u0010\u000b\n\u0002\b\u0002\n\u0002\u0010\b\n\u0002\b\u0004\b\u0087\b\u0018\u0000 52\u00020\u0001:\u000245B\u0081\u0001\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\n\b\u0002\u0010\u0004\u001a\u0004\u0018\u00010\u0005\u0012\n\b\u0002\u0010\u0006\u001a\u0004\u0018\u00010\u0007\u0012\n\b\u0002\u0010\b\u001a\u0004\u0018\u00010\u0007\u0012\n\b\u0002\u0010\t\u001a\u0004\u0018\u00010\u0007\u0012\n\b\u0002\u0010\n\u001a\u0004\u0018\u00010\u0007\u0012\n\b\u0002\u0010\u000b\u001a\u0004\u0018\u00010\u0007\u0012\n\b\u0002\u0010\f\u001a\u0004\u0018\u00010\u0007\u0012\n\b\u0002\u0010\r\u001a\u0004\u0018\u00010\u0007\u0012\u0010\b\u0002\u0010\u000e\u001a\n\u0012\u0004\u0012\u00020\u0003\u0018\u00010\u000f\u00a2\u0006\u0004\b\u0010\u0010\u0011J\t\u0010\"\u001a\u00020\u0003H\u00c6\u0003J\u0010\u0010#\u001a\u0004\u0018\u00010\u0005H\u00c6\u0003\u00a2\u0006\u0002\u0010\u0015J\u0010\u0010$\u001a\u0004\u0018\u00010\u0007H\u00c6\u0003\u00a2\u0006\u0002\u0010\u0018J\u0010\u0010%\u001a\u0004\u0018\u00010\u0007H\u00c6\u0003\u00a2\u0006\u0002\u0010\u0018J\u0010\u0010&\u001a\u0004\u0018\u00010\u0007H\u00c6\u0003\u00a2\u0006\u0002\u0010\u0018J\u0010\u0010\'\u001a\u0004\u0018\u00010\u0007H\u00c6\u0003\u00a2\u0006\u0002\u0010\u0018J\u0010\u0010(\u001a\u0004\u0018\u00010\u0007H\u00c6\u0003\u00a2\u0006\u0002\u0010\u0018J\u0010\u0010)\u001a\u0004\u0018\u00010\u0007H\u00c6\u0003\u00a2\u0006\u0002\u0010\u0018J\u0010\u0010*\u001a\u0004\u0018\u00010\u0007H\u00c6\u0003\u00a2\u0006\u0002\u0010\u0018J\u0011\u0010+\u001a\n\u0012\u0004\u0012\u00020\u0003\u0018\u00010\u000fH\u00c6\u0003J\u008a\u0001\u0010,\u001a\u00020\u00002\b\b\u0002\u0010\u0002\u001a\u00020\u00032\n\b\u0002\u0010\u0004\u001a\u0004\u0018\u00010\u00052\n\b\u0002\u0010\u0006\u001a\u0004\u0018\u00010\u00072\n\b\u0002\u0010\b\u001a\u0004\u0018\u00010\u00072\n\b\u0002\u0010\t\u001a\u0004\u0018\u00010\u00072\n\b\u0002\u0010\n\u001a\u0004\u0018\u00010\u00072\n\b\u0002\u0010\u000b\u001a\u0004\u0018\u00010\u00072\n\b\u0002\u0010\f\u001a\u0004\u0018\u00010\u00072\n\b\u0002\u0010\r\u001a\u0004\u0018\u00010\u00072\u0010\b\u0002\u0010\u000e\u001a\n\u0012\u0004\u0012\u00020\u0003\u0018\u00010\u000fH\u00c6\u0001\u00a2\u0006\u0002\u0010-J\u0013\u0010.\u001a\u00020/2\b\u00100\u001a\u0004\u0018\u00010\u0001H\u00d6\u0003J\t\u00101\u001a\u000202H\u00d6\u0001J\t\u00103\u001a\u00020\u0003H\u00d6\u0001R\u0011\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0012\u0010\u0013R\u0015\u0010\u0004\u001a\u0004\u0018\u00010\u0005\u00a2\u0006\n\n\u0002\u0010\u0016\u001a\u0004\b\u0014\u0010\u0015R\u0015\u0010\u0006\u001a\u0004\u0018\u00010\u0007\u00a2\u0006\n\n\u0002\u0010\u0019\u001a\u0004\b\u0017\u0010\u0018R\u0015\u0010\b\u001a\u0004\u0018\u00010\u0007\u00a2\u0006\n\n\u0002\u0010\u0019\u001a\u0004\b\u001a\u0010\u0018R\u0015\u0010\t\u001a\u0004\u0018\u00010\u0007\u00a2\u0006\n\n\u0002\u0010\u0019\u001a\u0004\b\u001b\u0010\u0018R\u0015\u0010\n\u001a\u0004\u0018\u00010\u0007\u00a2\u0006\n\n\u0002\u0010\u0019\u001a\u0004\b\u001c\u0010\u0018R\u0015\u0010\u000b\u001a\u0004\u0018\u00010\u0007\u00a2\u0006\n\n\u0002\u0010\u0019\u001a\u0004\b\u001d\u0010\u0018R\u0015\u0010\f\u001a\u0004\u0018\u00010\u0007\u00a2\u0006\n\n\u0002\u0010\u0019\u001a\u0004\b\u001e\u0010\u0018R\u0015\u0010\r\u001a\u0004\u0018\u00010\u0007\u00a2\u0006\n\n\u0002\u0010\u0019\u001a\u0004\b\u001f\u0010\u0018R\u0019\u0010\u000e\u001a\n\u0012\u0004\u0012\u00020\u0003\u0018\u00010\u000f\u00a2\u0006\b\n\u0000\u001a\u0004\b \u0010!\u00a8\u00066"}, d2 = {"Lcom/xayah/core/restic/ResticRepository$ResticBackupProgress;", "", "message_type", "", "percent_done", "", "bytes_done", "", "total_bytes", "files_done", "total_files", "seconds_elapsed", "seconds_remaining", "error_count", "current_files", "", "<init>", "(Ljava/lang/String;Ljava/lang/Float;Ljava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;Ljava/util/List;)V", "getMessage_type", "()Ljava/lang/String;", "getPercent_done", "()Ljava/lang/Float;", "Ljava/lang/Float;", "getBytes_done", "()Ljava/lang/Long;", "Ljava/lang/Long;", "getTotal_bytes", "getFiles_done", "getTotal_files", "getSeconds_elapsed", "getSeconds_remaining", "getError_count", "getCurrent_files", "()Ljava/util/List;", "component1", "component2", "component3", "component4", "component5", "component6", "component7", "component8", "component9", "component10", "copy", "(Ljava/lang/String;Ljava/lang/Float;Ljava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;Ljava/util/List;)Lcom/xayah/core/restic/ResticRepository$ResticBackupProgress;", "equals", "", "other", "hashCode", "", "toString", "$serializer", "Companion", "restic_debug"})
    public static final class ResticBackupProgress {
        @org.jetbrains.annotations.NotNull()
        private final java.lang.String message_type = null;
        @org.jetbrains.annotations.Nullable()
        private final java.lang.Float percent_done = null;
        @org.jetbrains.annotations.Nullable()
        private final java.lang.Long bytes_done = null;
        @org.jetbrains.annotations.Nullable()
        private final java.lang.Long total_bytes = null;
        @org.jetbrains.annotations.Nullable()
        private final java.lang.Long files_done = null;
        @org.jetbrains.annotations.Nullable()
        private final java.lang.Long total_files = null;
        @org.jetbrains.annotations.Nullable()
        private final java.lang.Long seconds_elapsed = null;
        @org.jetbrains.annotations.Nullable()
        private final java.lang.Long seconds_remaining = null;
        @org.jetbrains.annotations.Nullable()
        private final java.lang.Long error_count = null;
        @org.jetbrains.annotations.Nullable()
        private final java.util.List<java.lang.String> current_files = null;
        @org.jetbrains.annotations.NotNull()
        public static final com.xayah.core.restic.ResticRepository.ResticBackupProgress.Companion Companion = null;
        
        public ResticBackupProgress(@org.jetbrains.annotations.NotNull()
        java.lang.String message_type, @org.jetbrains.annotations.Nullable()
        java.lang.Float percent_done, @org.jetbrains.annotations.Nullable()
        java.lang.Long bytes_done, @org.jetbrains.annotations.Nullable()
        java.lang.Long total_bytes, @org.jetbrains.annotations.Nullable()
        java.lang.Long files_done, @org.jetbrains.annotations.Nullable()
        java.lang.Long total_files, @org.jetbrains.annotations.Nullable()
        java.lang.Long seconds_elapsed, @org.jetbrains.annotations.Nullable()
        java.lang.Long seconds_remaining, @org.jetbrains.annotations.Nullable()
        java.lang.Long error_count, @org.jetbrains.annotations.Nullable()
        java.util.List<java.lang.String> current_files) {
            super();
        }
        
        @org.jetbrains.annotations.NotNull()
        public final java.lang.String getMessage_type() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Float getPercent_done() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Long getBytes_done() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Long getTotal_bytes() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Long getFiles_done() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Long getTotal_files() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Long getSeconds_elapsed() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Long getSeconds_remaining() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Long getError_count() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.util.List<java.lang.String> getCurrent_files() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final java.lang.String component1() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.util.List<java.lang.String> component10() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Float component2() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Long component3() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Long component4() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Long component5() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Long component6() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Long component7() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Long component8() {
            return null;
        }
        
        @org.jetbrains.annotations.Nullable()
        public final java.lang.Long component9() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final com.xayah.core.restic.ResticRepository.ResticBackupProgress copy(@org.jetbrains.annotations.NotNull()
        java.lang.String message_type, @org.jetbrains.annotations.Nullable()
        java.lang.Float percent_done, @org.jetbrains.annotations.Nullable()
        java.lang.Long bytes_done, @org.jetbrains.annotations.Nullable()
        java.lang.Long total_bytes, @org.jetbrains.annotations.Nullable()
        java.lang.Long files_done, @org.jetbrains.annotations.Nullable()
        java.lang.Long total_files, @org.jetbrains.annotations.Nullable()
        java.lang.Long seconds_elapsed, @org.jetbrains.annotations.Nullable()
        java.lang.Long seconds_remaining, @org.jetbrains.annotations.Nullable()
        java.lang.Long error_count, @org.jetbrains.annotations.Nullable()
        java.util.List<java.lang.String> current_files) {
            return null;
        }
        
        @java.lang.Override()
        public boolean equals(@org.jetbrains.annotations.Nullable()
        java.lang.Object other) {
            return false;
        }
        
        @java.lang.Override()
        public int hashCode() {
            return 0;
        }
        
        @java.lang.Override()
        @org.jetbrains.annotations.NotNull()
        public java.lang.String toString() {
            return null;
        }
        
        /**
         * Restic 备份进度数据类
         */
        @kotlin.Metadata(mv = {2, 1, 0}, k = 1, xi = 48, d1 = {"\u00006\n\u0000\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0010\u0011\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0003\b\u00c7\u0002\u0018\u00002\b\u0012\u0004\u0012\u00020\u00020\u0001B\t\b\u0002\u00a2\u0006\u0004\b\u0003\u0010\u0004J\u0015\u0010\u0005\u001a\f\u0012\b\u0012\u0006\u0012\u0002\b\u00030\u00070\u0006\u00a2\u0006\u0002\u0010\bJ\u000e\u0010\t\u001a\u00020\u00022\u0006\u0010\n\u001a\u00020\u000bJ\u0016\u0010\f\u001a\u00020\r2\u0006\u0010\u000e\u001a\u00020\u000f2\u0006\u0010\u0010\u001a\u00020\u0002R\u0011\u0010\u0011\u001a\u00020\u0012\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0013\u0010\u0014\u00a8\u0006\u0015"}, d2 = {"com/xayah/core/restic/ResticRepository.ResticBackupProgress.$serializer", "Lkotlinx/serialization/internal/GeneratedSerializer;", "Lcom/xayah/core/restic/ResticRepository$ResticBackupProgress;", "<init>", "()V", "childSerializers", "", "Lkotlinx/serialization/KSerializer;", "()[Lkotlinx/serialization/KSerializer;", "deserialize", "decoder", "Lkotlinx/serialization/encoding/Decoder;", "serialize", "", "encoder", "Lkotlinx/serialization/encoding/Encoder;", "value", "descriptor", "Lkotlinx/serialization/descriptors/SerialDescriptor;", "getDescriptor", "()Lkotlinx/serialization/descriptors/SerialDescriptor;", "restic_debug"})
        @java.lang.Deprecated()
        public static final class $serializer implements kotlinx.serialization.internal.GeneratedSerializer<com.xayah.core.restic.ResticRepository.ResticBackupProgress> {
            @org.jetbrains.annotations.NotNull()
            public static final com.xayah.core.restic.ResticRepository.ResticBackupProgress.$serializer INSTANCE = null;
            @org.jetbrains.annotations.NotNull()
            private static final kotlinx.serialization.descriptors.SerialDescriptor descriptor = null;
            
            /**
             * Restic 备份进度数据类
             */
            @java.lang.Override()
            @org.jetbrains.annotations.NotNull()
            public final kotlinx.serialization.KSerializer<?>[] childSerializers() {
                return null;
            }
            
            /**
             * Restic 备份进度数据类
             */
            @java.lang.Override()
            @org.jetbrains.annotations.NotNull()
            public final com.xayah.core.restic.ResticRepository.ResticBackupProgress deserialize(@org.jetbrains.annotations.NotNull()
            kotlinx.serialization.encoding.Decoder decoder) {
                return null;
            }
            
            /**
             * Restic 备份进度数据类
             */
            @java.lang.Override()
            public final void serialize(@org.jetbrains.annotations.NotNull()
            kotlinx.serialization.encoding.Encoder encoder, @org.jetbrains.annotations.NotNull()
            com.xayah.core.restic.ResticRepository.ResticBackupProgress value) {
            }
            
            private $serializer() {
                super();
            }
            
            /**
             * Restic 备份进度数据类
             */
            @java.lang.Override()
            @org.jetbrains.annotations.NotNull()
            public final kotlinx.serialization.descriptors.SerialDescriptor getDescriptor() {
                return null;
            }
        }
        
        /**
         * Restic 备份进度数据类
         */
        @kotlin.Metadata(mv = {2, 1, 0}, k = 1, xi = 48, d1 = {"\u0000\u0016\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\b\u0086\u0003\u0018\u00002\u00020\u0001B\t\b\u0002\u00a2\u0006\u0004\b\u0002\u0010\u0003J\f\u0010\u0004\u001a\b\u0012\u0004\u0012\u00020\u00060\u0005\u00a8\u0006\u0007"}, d2 = {"Lcom/xayah/core/restic/ResticRepository$ResticBackupProgress$Companion;", "", "<init>", "()V", "serializer", "Lkotlinx/serialization/KSerializer;", "Lcom/xayah/core/restic/ResticRepository$ResticBackupProgress;", "restic_debug"})
        public static final class Companion {
            
            /**
             * Restic 备份进度数据类
             */
            @org.jetbrains.annotations.NotNull()
            public final kotlinx.serialization.KSerializer<com.xayah.core.restic.ResticRepository.ResticBackupProgress> serializer() {
                return null;
            }
            
            private Companion() {
                super();
            }
        }
    }
    
    @kotlin.Metadata(mv = {2, 1, 0}, k = 1, xi = 48, d1 = {"\u0000 \n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u0002\n\u0000\n\u0002\u0010\t\n\u0002\b\u0007\n\u0002\u0010\u0007\n\u0002\b\u0003\bf\u0018\u00002\u00020\u0001J8\u0010\u0002\u001a\u00020\u00032\u0006\u0010\u0004\u001a\u00020\u00052\u0006\u0010\u0006\u001a\u00020\u00052\u0006\u0010\u0007\u001a\u00020\u00052\u0006\u0010\b\u001a\u00020\u00052\u0006\u0010\t\u001a\u00020\u00052\u0006\u0010\n\u001a\u00020\u0005H&J0\u0010\u000b\u001a\u00020\u00032\u0006\u0010\f\u001a\u00020\r2\u0006\u0010\u000e\u001a\u00020\u00052\u0006\u0010\b\u001a\u00020\u00052\u0006\u0010\u000f\u001a\u00020\u00052\u0006\u0010\u0006\u001a\u00020\u0005H&\u00a8\u0006\u0010"}, d2 = {"Lcom/xayah/core/restic/ResticRepository$ResticProgressCallback;", "", "onRestoreProgress", "", "filesFinished", "", "filesTotal", "bytesWritten", "bytesTotal", "filesSkipped", "bytesSkipped", "onBackupProgress", "percentDone", "", "bytesDone", "filesDone", "restic_debug"})
    public static abstract interface ResticProgressCallback {
        
        public abstract void onRestoreProgress(long filesFinished, long filesTotal, long bytesWritten, long bytesTotal, long filesSkipped, long bytesSkipped);
        
        public abstract void onBackupProgress(float percentDone, long bytesDone, long bytesTotal, long filesDone, long filesTotal);
    }
}