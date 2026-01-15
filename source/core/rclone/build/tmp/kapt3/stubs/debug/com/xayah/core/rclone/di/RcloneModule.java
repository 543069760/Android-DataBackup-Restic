package com.xayah.core.rclone.di;

import android.content.Context;
import com.xayah.core.datastore.di.Dispatcher;
import com.xayah.core.rclone.RcloneLogger;
import com.xayah.core.rclone.RcloneNative;
import com.xayah.core.rclone.RcloneRepository;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import kotlinx.coroutines.CoroutineDispatcher;
import javax.inject.Singleton;

@dagger.Module()
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000.\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\b\u00c7\u0002\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002J\u0012\u0010\u0003\u001a\u00020\u00042\b\b\u0001\u0010\u0005\u001a\u00020\u0006H\u0007J\u0010\u0010\u0007\u001a\u00020\b2\u0006\u0010\t\u001a\u00020\u0004H\u0007J\"\u0010\n\u001a\u00020\u000b2\b\b\u0001\u0010\f\u001a\u00020\r2\u0006\u0010\t\u001a\u00020\u00042\u0006\u0010\u000e\u001a\u00020\bH\u0007\u00a8\u0006\u000f"}, d2 = {"Lcom/xayah/core/rclone/di/RcloneModule;", "", "()V", "provideRcloneLogger", "Lcom/xayah/core/rclone/RcloneLogger;", "ioDispatcher", "Lkotlinx/coroutines/CoroutineDispatcher;", "provideRcloneNative", "Lcom/xayah/core/rclone/RcloneNative;", "logger", "provideRcloneRepository", "Lcom/xayah/core/rclone/RcloneRepository;", "context", "Landroid/content/Context;", "rcloneNative", "rclone_debug"})
@dagger.hilt.InstallIn(value = {dagger.hilt.components.SingletonComponent.class})
public final class RcloneModule {
    @org.jetbrains.annotations.NotNull()
    public static final com.xayah.core.rclone.di.RcloneModule INSTANCE = null;
    
    private RcloneModule() {
        super();
    }
    
    @dagger.Provides()
    @javax.inject.Singleton()
    @org.jetbrains.annotations.NotNull()
    public final com.xayah.core.rclone.RcloneRepository provideRcloneRepository(@dagger.hilt.android.qualifiers.ApplicationContext()
    @org.jetbrains.annotations.NotNull()
    android.content.Context context, @org.jetbrains.annotations.NotNull()
    com.xayah.core.rclone.RcloneLogger logger, @org.jetbrains.annotations.NotNull()
    com.xayah.core.rclone.RcloneNative rcloneNative) {
        return null;
    }
    
    @dagger.Provides()
    @javax.inject.Singleton()
    @org.jetbrains.annotations.NotNull()
    public final com.xayah.core.rclone.RcloneLogger provideRcloneLogger(@com.xayah.core.datastore.di.Dispatcher(dbDispatchers = com.xayah.core.datastore.di.DbDispatchers.IO)
    @org.jetbrains.annotations.NotNull()
    kotlinx.coroutines.CoroutineDispatcher ioDispatcher) {
        return null;
    }
    
    @dagger.Provides()
    @javax.inject.Singleton()
    @org.jetbrains.annotations.NotNull()
    public final com.xayah.core.rclone.RcloneNative provideRcloneNative(@org.jetbrains.annotations.NotNull()
    com.xayah.core.rclone.RcloneLogger logger) {
        return null;
    }
}