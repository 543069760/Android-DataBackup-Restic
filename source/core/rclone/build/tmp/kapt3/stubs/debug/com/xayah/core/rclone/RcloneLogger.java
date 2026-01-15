package com.xayah.core.rclone;

import android.util.Log;
import com.xayah.core.datastore.di.Dispatcher;
import kotlinx.coroutines.CoroutineDispatcher;
import javax.inject.Inject;
import javax.inject.Singleton;

@javax.inject.Singleton()
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000N\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\u0003\n\u0002\b\t\n\u0002\u0010 \n\u0000\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\b\n\u0002\b\u0016\n\u0002\u0010\u0006\n\u0000\n\u0002\u0010\t\n\u0002\b\r\b\u0007\u0018\u0000 ?2\u00020\u0001:\u0001?B\u0011\b\u0007\u0012\b\b\u0001\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\u0002\u0010\u0004J\u001a\u0010\u0005\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\b2\n\b\u0002\u0010\t\u001a\u0004\u0018\u00010\nJ\u001a\u0010\u000b\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\b2\n\b\u0002\u0010\t\u001a\u0004\u0018\u00010\nJ\u0010\u0010\f\u001a\u00020\b2\u0006\u0010\u0007\u001a\u00020\bH\u0002J\u001a\u0010\r\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\b2\n\b\u0002\u0010\t\u001a\u0004\u0018\u00010\nJ\u000e\u0010\u000e\u001a\u00020\u00062\u0006\u0010\u000f\u001a\u00020\bJ\u000e\u0010\u0010\u001a\u00020\u00062\u0006\u0010\u000f\u001a\u00020\bJ\u000e\u0010\u0011\u001a\u00020\u00062\u0006\u0010\u000f\u001a\u00020\bJ\u0014\u0010\u0012\u001a\u00020\u00062\f\u0010\u0013\u001a\b\u0012\u0004\u0012\u00020\b0\u0014J\u0012\u0010\u0015\u001a\u00020\u00062\n\u0010\u000b\u001a\u00060\u0016j\u0002`\u0017J\u0016\u0010\u0018\u001a\u00020\u00062\u0006\u0010\u0019\u001a\u00020\u001a2\u0006\u0010\u001b\u001a\u00020\bJ\u000e\u0010\u001c\u001a\u00020\u00062\u0006\u0010\u001d\u001a\u00020\bJ\u000e\u0010\u001e\u001a\u00020\u00062\u0006\u0010\u001f\u001a\u00020\bJ\u0006\u0010 \u001a\u00020\u0006J\u0006\u0010!\u001a\u00020\u0006J\u0012\u0010\"\u001a\u00020\u00062\n\u0010\u000b\u001a\u00060\u0016j\u0002`\u0017J\u0006\u0010#\u001a\u00020\u0006J\u0012\u0010$\u001a\u00020\u00062\n\u0010\u000b\u001a\u00060\u0016j\u0002`\u0017J\u000e\u0010%\u001a\u00020\u00062\u0006\u0010&\u001a\u00020\bJ\u0016\u0010\'\u001a\u00020\u00062\u0006\u0010&\u001a\u00020\b2\u0006\u0010(\u001a\u00020\bJ\u000e\u0010)\u001a\u00020\u00062\u0006\u0010(\u001a\u00020\bJ\u0006\u0010*\u001a\u00020\u0006J\u0006\u0010+\u001a\u00020\u0006J\u0016\u0010,\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\bH\u0086@\u00a2\u0006\u0002\u0010-J\u000e\u0010.\u001a\u00020\u00062\u0006\u0010\u001f\u001a\u00020\bJ\u001e\u0010/\u001a\u00020\u00062\u0006\u00100\u001a\u0002012\u0006\u00102\u001a\u0002032\u0006\u00104\u001a\u000203J\u0016\u00105\u001a\u00020\u00062\u0006\u00106\u001a\u00020\b2\u0006\u00107\u001a\u00020\bJ\u0016\u00108\u001a\u00020\u00062\u0006\u0010&\u001a\u00020\b2\u0006\u00109\u001a\u00020\bJ\u000e\u0010:\u001a\u00020\u00062\u0006\u0010;\u001a\u000203J\u000e\u0010<\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\bJ\u001a\u0010=\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\b2\n\b\u0002\u0010\t\u001a\u0004\u0018\u00010\nJ\u001a\u0010>\u001a\u00020\u00062\u0006\u0010\u0007\u001a\u00020\b2\n\b\u0002\u0010\t\u001a\u0004\u0018\u00010\nR\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004\u00a2\u0006\u0002\n\u0000\u00a8\u0006@"}, d2 = {"Lcom/xayah/core/rclone/RcloneLogger;", "", "ioDispatcher", "Lkotlinx/coroutines/CoroutineDispatcher;", "(Lkotlinx/coroutines/CoroutineDispatcher;)V", "d", "", "message", "", "throwable", "", "e", "formatMessage", "i", "logBinaryLoad", "path", "logBinaryNotFound", "logBinaryPathFound", "logCommand", "command", "", "logCommandFailed", "Ljava/lang/Exception;", "Lkotlin/Exception;", "logCommandResult", "exitCode", "", "output", "logConfigInit", "configPath", "logConfigInitFailed", "error", "logConfigInitStarted", "logConfigInitSuccess", "logPermissionSetFailed", "logPermissionSetSuccess", "logRemoteListFailed", "logRemoteListStarted", "remote", "logResticServerStart", "addr", "logResticServerStarted", "logResticServerStop", "logResticServerStopped", "logSuspend", "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "logSyncFailed", "logSyncProgress", "percent", "", "files", "", "bytes", "logSyncStart", "remotePath", "localPath", "logSyncStarted", "local", "logSyncSuccess", "transferredBytes", "logThread", "v", "w", "Companion", "rclone_debug"})
public final class RcloneLogger {
    @org.jetbrains.annotations.NotNull()
    private final kotlinx.coroutines.CoroutineDispatcher ioDispatcher = null;
    @org.jetbrains.annotations.NotNull()
    private static final java.lang.String TAG = "RcloneBackup";
    @org.jetbrains.annotations.NotNull()
    private static final java.lang.String PREFIX = "[Rclone]";
    @org.jetbrains.annotations.NotNull()
    public static final com.xayah.core.rclone.RcloneLogger.Companion Companion = null;
    
    @javax.inject.Inject()
    public RcloneLogger(@com.xayah.core.datastore.di.Dispatcher(dbDispatchers = com.xayah.core.datastore.di.DbDispatchers.IO)
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
    
    public final void logConfigInit(@org.jetbrains.annotations.NotNull()
    java.lang.String configPath) {
    }
    
    public final void logSyncStart(@org.jetbrains.annotations.NotNull()
    java.lang.String remotePath, @org.jetbrains.annotations.NotNull()
    java.lang.String localPath) {
    }
    
    public final void logSyncProgress(double percent, long files, long bytes) {
    }
    
    public final void logSyncSuccess(long transferredBytes) {
    }
    
    public final void logSyncFailed(@org.jetbrains.annotations.NotNull()
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
    
    public final void logConfigInitStarted() {
    }
    
    public final void logConfigInitSuccess() {
    }
    
    public final void logConfigInitFailed(@org.jetbrains.annotations.NotNull()
    java.lang.String error) {
    }
    
    public final void logSyncStarted(@org.jetbrains.annotations.NotNull()
    java.lang.String remote, @org.jetbrains.annotations.NotNull()
    java.lang.String local) {
    }
    
    public final void logRemoteListStarted(@org.jetbrains.annotations.NotNull()
    java.lang.String remote) {
    }
    
    public final void logRemoteListFailed(@org.jetbrains.annotations.NotNull()
    java.lang.Exception e) {
    }
    
    public final void logCommand(@org.jetbrains.annotations.NotNull()
    java.util.List<java.lang.String> command) {
    }
    
    public final void logCommandResult(int exitCode, @org.jetbrains.annotations.NotNull()
    java.lang.String output) {
    }
    
    public final void logResticServerStart(@org.jetbrains.annotations.NotNull()
    java.lang.String remote, @org.jetbrains.annotations.NotNull()
    java.lang.String addr) {
    }
    
    public final void logResticServerStarted(@org.jetbrains.annotations.NotNull()
    java.lang.String addr) {
    }
    
    public final void logResticServerStop() {
    }
    
    public final void logResticServerStopped() {
    }
    
    public final void logCommandFailed(@org.jetbrains.annotations.NotNull()
    java.lang.Exception e) {
    }
    
    private final java.lang.String formatMessage(java.lang.String message) {
        return null;
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u0014\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0002\b\u0086\u0003\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002R\u000e\u0010\u0003\u001a\u00020\u0004X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0005\u001a\u00020\u0004X\u0082T\u00a2\u0006\u0002\n\u0000\u00a8\u0006\u0006"}, d2 = {"Lcom/xayah/core/rclone/RcloneLogger$Companion;", "", "()V", "PREFIX", "", "TAG", "rclone_debug"})
    public static final class Companion {
        
        private Companion() {
            super();
        }
    }
}