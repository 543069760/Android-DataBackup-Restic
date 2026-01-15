package com.xayah.core.restic;

import android.util.Log;
import com.xayah.core.datastore.di.Dispatcher;
import kotlinx.coroutines.CoroutineDispatcher;
import javax.inject.Inject;
import javax.inject.Singleton;

@javax.inject.Singleton()
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000N\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\u0003\n\u0002\b\u0007\n\u0002\u0010\u0006\n\u0000\n\u0002\u0010\t\n\u0002\b\u000b\n\u0002\u0010 \n\u0000\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\b\n\u0002\b\u0013\b\u0007\u0018\u0000 82\u00020\u0001:\u00018B\u0011\b\u0007\u0012\b\b\u0001\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\u0002\u0010\u0004J\u001a\u0010\u0005\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\b2\n\b\u0002\u0010\t\u001a\u0004\u0018\u00010\nJ\u001a\u0010\u000b\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\b2\n\b\u0002\u0010\t\u001a\u0004\u0018\u00010\nJ\u0010\u0010\f\u001a\u00020\b2\u0006\u0010\u0007\u001a\u00020\bH\u0002J\u001a\u0010\r\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\b2\n\b\u0002\u0010\t\u001a\u0004\u0018\u00010\nJ\u000e\u0010\u000e\u001a\u00020\u00062\u0006\u0010\u000f\u001a\u00020\bJ\u001e\u0010\u0010\u001a\u00020\u00062\u0006\u0010\u0011\u001a\u00020\u00122\u0006\u0010\u0013\u001a\u00020\u00142\u0006\u0010\u0015\u001a\u00020\u0014J\u000e\u0010\u0016\u001a\u00020\u00062\u0006\u0010\u0017\u001a\u00020\bJ\u000e\u0010\u0018\u001a\u00020\u00062\u0006\u0010\u0017\u001a\u00020\bJ\u000e\u0010\u0019\u001a\u00020\u00062\u0006\u0010\u001a\u001a\u00020\bJ\u000e\u0010\u001b\u001a\u00020\u00062\u0006\u0010\u0017\u001a\u00020\bJ\u000e\u0010\u001c\u001a\u00020\u00062\u0006\u0010\u0017\u001a\u00020\bJ\u000e\u0010\u001d\u001a\u00020\u00062\u0006\u0010\u0017\u001a\u00020\bJ\u0014\u0010\u001e\u001a\u00020\u00062\f\u0010\u001f\u001a\b\u0012\u0004\u0012\u00020\b0 J\u0012\u0010!\u001a\u00020\u00062\n\u0010\u000b\u001a\u00060\"j\u0002`#J\u0016\u0010$\u001a\u00020\u00062\u0006\u0010%\u001a\u00020&2\u0006\u0010\'\u001a\u00020\bJ\u0012\u0010(\u001a\u00020\u00062\n\u0010\u000b\u001a\u00060\"j\u0002`#J\u0006\u0010)\u001a\u00020\u0006J\u000e\u0010*\u001a\u00020\u00062\u0006\u0010+\u001a\u00020\bJ\u000e\u0010,\u001a\u00020\u00062\u0006\u0010\u000f\u001a\u00020\bJ\u0006\u0010-\u001a\u00020\u0006J\u0006\u0010.\u001a\u00020\u0006J\u0016\u0010/\u001a\u00020\u00062\u0006\u0010\u001a\u001a\u00020\b2\u0006\u00100\u001a\u00020\bJ\u0006\u00101\u001a\u00020\u0006J\u0012\u00102\u001a\u00020\u00062\n\u0010\u000b\u001a\u00060\"j\u0002`#J\u0016\u00103\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\bH\u0086@\u00a2\u0006\u0002\u00104J\u000e\u00105\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\bJ\u001a\u00106\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\b2\n\b\u0002\u0010\t\u001a\u0004\u0018\u00010\nJ\u001a\u00107\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\b2\n\b\u0002\u0010\t\u001a\u0004\u0018\u00010\nR\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004\u00a2\u0006\u0002\n\u0000\u00a8\u00069"}, d2 = {"Lcom/xayah/core/restic/ResticLogger;", "", "ioDispatcher", "Lkotlinx/coroutines/CoroutineDispatcher;", "(Lkotlinx/coroutines/CoroutineDispatcher;)V", "d", "", "message", "", "throwable", "", "e", "formatMessage", "i", "logBackupFailed", "error", "logBackupProgress", "percent", "", "files", "", "bytes", "logBackupStart", "path", "logBackupStarted", "logBackupSuccess", "snapshotId", "logBinaryLoad", "logBinaryNotFound", "logBinaryPathFound", "logCommand", "command", "", "logCommandFailed", "Ljava/lang/Exception;", "Lkotlin/Exception;", "logCommandResult", "exitCode", "", "output", "logPermissionSetFailed", "logPermissionSetSuccess", "logRepositoryInit", "repoPath", "logRepositoryInitFailed", "logRepositoryInitStarted", "logRepositoryInitSuccess", "logRestoreStarted", "targetPath", "logSnapshotsListStarted", "logSnapshotsParseFailed", "logSuspend", "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "logThread", "v", "w", "Companion", "restic_debug"})
public final class ResticLogger {
    @org.jetbrains.annotations.NotNull()
    private final kotlinx.coroutines.CoroutineDispatcher ioDispatcher = null;
    @org.jetbrains.annotations.NotNull()
    private static final java.lang.String TAG = "ResticBackup";
    @org.jetbrains.annotations.NotNull()
    private static final java.lang.String PREFIX = "[Restic]";
    @org.jetbrains.annotations.NotNull()
    public static final com.xayah.core.restic.ResticLogger.Companion Companion = null;
    
    @javax.inject.Inject()
    public ResticLogger(@com.xayah.core.datastore.di.Dispatcher(dbDispatchers = com.xayah.core.datastore.di.DbDispatchers.IO)
    @org.jetbrains.annotations.NotNull()
    kotlinx.coroutines.CoroutineDispatcher ioDispatcher) {
        super();
    }
    
    public final void v(@org.jetbrains.annotations.NotNull()
    java.lang.String message, @org.jetbrains.annotations.Nullable()
    java.lang.Throwable throwable) {
    }
    
    public final void d(@org.jetbrains.annotations.NotNull()
    java.lang.String message, @org.jetbrains.annotations.Nullable()
    java.lang.Throwable throwable) {
    }
    
    public final void i(@org.jetbrains.annotations.NotNull()
    java.lang.String message, @org.jetbrains.annotations.Nullable()
    java.lang.Throwable throwable) {
    }
    
    public final void w(@org.jetbrains.annotations.NotNull()
    java.lang.String message, @org.jetbrains.annotations.Nullable()
    java.lang.Throwable throwable) {
    }
    
    public final void e(@org.jetbrains.annotations.NotNull()
    java.lang.String message, @org.jetbrains.annotations.Nullable()
    java.lang.Throwable throwable) {
    }
    
    public final void logThread(@org.jetbrains.annotations.NotNull()
    java.lang.String message) {
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object logSuspend(@org.jetbrains.annotations.NotNull()
    java.lang.String message, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super kotlin.Unit> $completion) {
        return null;
    }
    
    public final void logBinaryLoad(@org.jetbrains.annotations.NotNull()
    java.lang.String path) {
    }
    
    public final void logBinaryNotFound(@org.jetbrains.annotations.NotNull()
    java.lang.String path) {
    }
    
    public final void logRepositoryInit(@org.jetbrains.annotations.NotNull()
    java.lang.String repoPath) {
    }
    
    public final void logBackupStart(@org.jetbrains.annotations.NotNull()
    java.lang.String path) {
    }
    
    public final void logBackupProgress(double percent, long files, long bytes) {
    }
    
    public final void logBackupSuccess(@org.jetbrains.annotations.NotNull()
    java.lang.String snapshotId) {
    }
    
    public final void logBackupFailed(@org.jetbrains.annotations.NotNull()
    java.lang.String error) {
    }
    
    public final void logBinaryPathFound(@org.jetbrains.annotations.NotNull()
    java.lang.String path) {
    }
    
    public final void logPermissionSetSuccess() {
    }
    
    public final void logPermissionSetFailed(@org.jetbrains.annotations.NotNull()
    java.lang.Exception e) {
    }
    
    public final void logRepositoryInitStarted() {
    }
    
    public final void logRepositoryInitSuccess() {
    }
    
    public final void logRepositoryInitFailed(@org.jetbrains.annotations.NotNull()
    java.lang.String error) {
    }
    
    public final void logBackupStarted(@org.jetbrains.annotations.NotNull()
    java.lang.String path) {
    }
    
    public final void logRestoreStarted(@org.jetbrains.annotations.NotNull()
    java.lang.String snapshotId, @org.jetbrains.annotations.NotNull()
    java.lang.String targetPath) {
    }
    
    public final void logSnapshotsListStarted() {
    }
    
    public final void logSnapshotsParseFailed(@org.jetbrains.annotations.NotNull()
    java.lang.Exception e) {
    }
    
    public final void logCommand(@org.jetbrains.annotations.NotNull()
    java.util.List<java.lang.String> command) {
    }
    
    public final void logCommandResult(int exitCode, @org.jetbrains.annotations.NotNull()
    java.lang.String output) {
    }
    
    public final void logCommandFailed(@org.jetbrains.annotations.NotNull()
    java.lang.Exception e) {
    }
    
    private final java.lang.String formatMessage(java.lang.String message) {
        return null;
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u0014\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0002\b\u0086\u0003\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002R\u000e\u0010\u0003\u001a\u00020\u0004X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0005\u001a\u00020\u0004X\u0082T\u00a2\u0006\u0002\n\u0000\u00a8\u0006\u0006"}, d2 = {"Lcom/xayah/core/restic/ResticLogger$Companion;", "", "()V", "PREFIX", "", "TAG", "restic_debug"})
    public static final class Companion {
        
        private Companion() {
            super();
        }
    }
}