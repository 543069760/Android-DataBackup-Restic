package com.xayah.core.restic;

import android.content.Context;
import android.util.Log;
import com.topjohnwu.superuser.Shell;
import com.xayah.core.model.DataType;
import com.xayah.core.model.restic.ResticBackupApp;
import com.xayah.core.model.restic.ResticBackupFiles;
import dagger.hilt.android.qualifiers.ApplicationContext;
import kotlinx.coroutines.Dispatchers;
import kotlinx.serialization.Serializable;
import java.io.File;
import javax.inject.Inject;
import javax.inject.Singleton;

@javax.inject.Singleton()
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u0080\u0001\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0005\n\u0002\u0018\u0002\n\u0002\u0010\b\n\u0002\b\u0004\n\u0002\u0010 \n\u0002\b\u0004\n\u0002\u0010\u000b\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0011\n\u0000\n\u0002\u0010$\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0007\n\u0002\u0018\u0002\n\u0002\b\u0005\b\u0007\u0018\u0000 ?2\u00020\u0001:\u0002?@B!\b\u0007\u0012\b\b\u0001\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0005\u0012\u0006\u0010\u0006\u001a\u00020\u0007\u00a2\u0006\u0002\u0010\bJ@\u0010\u000f\u001a\u000e\u0012\u0004\u0012\u00020\u0011\u0012\u0004\u0012\u00020\n0\u00102\u0006\u0010\u0012\u001a\u00020\n2\u0006\u0010\u0013\u001a\u00020\n2\u0006\u0010\u0014\u001a\u00020\n2\f\u0010\u0015\u001a\b\u0012\u0004\u0012\u00020\n0\u0016H\u0086@\u00a2\u0006\u0002\u0010\u0017JB\u0010\u0018\u001a\u000e\u0012\u0004\u0012\u00020\u0011\u0012\u0004\u0012\u00020\n0\u00102\b\b\u0002\u0010\u0019\u001a\u00020\n2\u0006\u0010\u0013\u001a\u00020\n2\u0006\u0010\u0014\u001a\u00020\n2\f\u0010\u0015\u001a\b\u0012\u0004\u0012\u00020\n0\u0016H\u0086@\u00a2\u0006\u0002\u0010\u0017J\u001e\u0010\u001a\u001a\u00020\u001b2\u0006\u0010\u0012\u001a\u00020\n2\u0006\u0010\u0013\u001a\u00020\nH\u0086@\u00a2\u0006\u0002\u0010\u001cJ\u0016\u0010\u001d\u001a\u00020\u001b2\u0006\u0010\u0012\u001a\u00020\nH\u0086@\u00a2\u0006\u0002\u0010\u001eJ8\u0010\u001f\u001a\u00020 2\u0012\u0010!\u001a\n\u0012\u0006\b\u0001\u0012\u00020\n0\"\"\u00020\n2\u0014\b\u0002\u0010#\u001a\u000e\u0012\u0004\u0012\u00020\n\u0012\u0004\u0012\u00020\n0$H\u0082@\u00a2\u0006\u0002\u0010%J\u0010\u0010&\u001a\u0004\u0018\u00010\nH\u0086@\u00a2\u0006\u0002\u0010\'J,\u0010(\u001a\b\u0012\u0004\u0012\u00020\n0)2\u0006\u0010\u0012\u001a\u00020\n2\u0006\u0010\u0013\u001a\u00020\nH\u0086@\u00f8\u0001\u0000\u00f8\u0001\u0001\u00a2\u0006\u0004\b*\u0010\u001cJ.\u0010+\u001a\b\u0012\u0004\u0012\u00020\n0)2\b\b\u0002\u0010\u0019\u001a\u00020\n2\u0006\u0010\u0013\u001a\u00020\nH\u0086@\u00f8\u0001\u0000\u00f8\u0001\u0001\u00a2\u0006\u0004\b,\u0010\u001cJ$\u0010-\u001a\b\u0012\u0004\u0012\u00020.0\u00162\u0006\u0010\u0012\u001a\u00020\n2\u0006\u0010\u0013\u001a\u00020\nH\u0086@\u00a2\u0006\u0002\u0010\u001cJ$\u0010/\u001a\b\u0012\u0004\u0012\u0002000\u00162\u0006\u0010\u0012\u001a\u00020\n2\u0006\u0010\u0013\u001a\u00020\nH\u0086@\u00a2\u0006\u0002\u0010\u001cJ$\u00101\u001a\b\u0012\u0004\u0012\u0002020\u00162\u0006\u0010\u0012\u001a\u00020\n2\u0006\u0010\u0013\u001a\u00020\nH\u0086@\u00a2\u0006\u0002\u0010\u001cJ\u0012\u00103\u001a\u0004\u0018\u0001042\u0006\u00105\u001a\u00020\nH\u0002JR\u00106\u001a\u00020\u001b2\u0006\u0010\u0012\u001a\u00020\n2\u0006\u0010\u0013\u001a\u00020\n2\u0006\u00107\u001a\u00020\n2\u0006\u00108\u001a\u00020\n2\n\b\u0002\u00109\u001a\u0004\u0018\u00010\n2\n\b\u0002\u0010:\u001a\u0004\u0018\u00010\n2\n\b\u0002\u0010;\u001a\u0004\u0018\u00010<H\u0086@\u00a2\u0006\u0002\u0010=J\u001e\u0010>\u001a\u00020\u001b2\u0006\u0010\u0012\u001a\u00020\n2\u0006\u0010\u0013\u001a\u00020\nH\u0086@\u00a2\u0006\u0002\u0010\u001cR\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0004\u001a\u00020\u0005X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0006\u001a\u00020\u0007X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u001b\u0010\t\u001a\u00020\n8BX\u0082\u0084\u0002\u00a2\u0006\f\n\u0004\b\r\u0010\u000e\u001a\u0004\b\u000b\u0010\f\u0082\u0002\u000b\n\u0002\b!\n\u0005\b\u00a1\u001e0\u0001\u00a8\u0006A"}, d2 = {"Lcom/xayah/core/restic/ResticRepository;", "", "context", "Landroid/content/Context;", "logger", "Lcom/xayah/core/restic/ResticLogger;", "resticNative", "Lcom/xayah/core/restic/ResticNative;", "(Landroid/content/Context;Lcom/xayah/core/restic/ResticLogger;Lcom/xayah/core/restic/ResticNative;)V", "resticPath", "", "getResticPath", "()Ljava/lang/String;", "resticPath$delegate", "Lkotlin/Lazy;", "backupFile", "Lkotlin/Pair;", "", "repoPath", "password", "filePath", "tags", "", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "backupFileWithResticBackend", "resticServerUrl", "checkRepository", "", "(Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "deleteRepository", "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "executeRestic", "Lcom/topjohnwu/superuser/Shell$Result;", "args", "", "env", "", "([Ljava/lang/String;Ljava/util/Map;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "getVersion", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "initRepository", "Lkotlin/Result;", "initRepository-0E7RQCE", "initRepositoryWithResticBackend", "initRepositoryWithResticBackend-0E7RQCE", "listBackedUpApps", "Lcom/xayah/core/model/restic/ResticBackupApp;", "listBackedUpFiles", "Lcom/xayah/core/model/restic/ResticBackupFiles;", "listSnapshots", "Lcom/xayah/core/restic/ResticSnapshot;", "parseRestoreProgress", "Lcom/xayah/core/restic/ResticRestoreProgress;", "line", "restoreSnapshot", "snapshotId", "targetPath", "snapshotSubPath", "includePath", "progressCallback", "Lcom/xayah/core/restic/ResticRepository$ResticProgressCallback;", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/xayah/core/restic/ResticRepository$ResticProgressCallback;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "validateRepository", "Companion", "ResticProgressCallback", "restic_debug"})
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
     * 核心执行方法：使用 libsu 执行 Root 命令
     */
    private final java.lang.Object executeRestic(java.lang.String[] args, java.util.Map<java.lang.String, java.lang.String> env, kotlin.coroutines.Continuation<? super com.topjohnwu.superuser.Shell.Result> $completion) {
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
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object getVersion(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.lang.String> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object backupFile(@org.jetbrains.annotations.NotNull()
    java.lang.String repoPath, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    java.lang.String filePath, @org.jetbrains.annotations.NotNull()
    java.util.List<java.lang.String> tags, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Pair<java.lang.Integer, java.lang.String>> $completion) {
        return null;
    }
    
    /**
     * 使用 REST 后端备份文件
     */
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object backupFileWithResticBackend(@org.jetbrains.annotations.NotNull()
    java.lang.String resticServerUrl, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    java.lang.String filePath, @org.jetbrains.annotations.NotNull()
    java.util.List<java.lang.String> tags, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Pair<java.lang.Integer, java.lang.String>> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object listSnapshots(@org.jetbrains.annotations.NotNull()
    java.lang.String repoPath, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.xayah.core.restic.ResticSnapshot>> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object listBackedUpFiles(@org.jetbrains.annotations.NotNull()
    java.lang.String repoPath, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.xayah.core.model.restic.ResticBackupFiles>> $completion) {
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
    
    private final com.xayah.core.restic.ResticRestoreProgress parseRestoreProgress(java.lang.String line) {
        return null;
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u0018\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0000\n\u0002\u0018\u0002\n\u0000\b\u0086\u0003\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002R\u000e\u0010\u0003\u001a\u00020\u0004X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0005\u001a\u00020\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000\u00a8\u0006\u0007"}, d2 = {"Lcom/xayah/core/restic/ResticRepository$Companion;", "", "()V", "TAG", "", "json", "Lkotlinx/serialization/json/Json;", "restic_debug"})
    public static final class Companion {
        
        private Companion() {
            super();
        }
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u0018\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u0002\n\u0000\n\u0002\u0010\t\n\u0002\b\u0006\bf\u0018\u00002\u00020\u0001J8\u0010\u0002\u001a\u00020\u00032\u0006\u0010\u0004\u001a\u00020\u00052\u0006\u0010\u0006\u001a\u00020\u00052\u0006\u0010\u0007\u001a\u00020\u00052\u0006\u0010\b\u001a\u00020\u00052\u0006\u0010\t\u001a\u00020\u00052\u0006\u0010\n\u001a\u00020\u0005H&\u00a8\u0006\u000b"}, d2 = {"Lcom/xayah/core/restic/ResticRepository$ResticProgressCallback;", "", "onProgress", "", "filesFinished", "", "filesTotal", "bytesWritten", "bytesTotal", "filesSkipped", "bytesSkipped", "restic_debug"})
    public static abstract interface ResticProgressCallback {
        
        public abstract void onProgress(long filesFinished, long filesTotal, long bytesWritten, long bytesTotal, long filesSkipped, long bytesSkipped);
    }
}