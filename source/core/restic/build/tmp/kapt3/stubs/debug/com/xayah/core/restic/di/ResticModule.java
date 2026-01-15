package com.xayah.core.restic.di;

import com.xayah.core.datastore.di.Dispatcher;
import com.xayah.core.restic.ResticLogger;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.Dispatchers;
import javax.inject.Singleton;

@dagger.Module()
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u0018\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\b\u00c7\u0002\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002J\u0012\u0010\u0003\u001a\u00020\u00042\b\b\u0001\u0010\u0005\u001a\u00020\u0006H\u0007\u00a8\u0006\u0007"}, d2 = {"Lcom/xayah/core/restic/di/ResticModule;", "", "()V", "provideResticLogger", "Lcom/xayah/core/restic/ResticLogger;", "ioDispatcher", "Lkotlinx/coroutines/CoroutineDispatcher;", "restic_debug"})
@dagger.hilt.InstallIn(value = {dagger.hilt.components.SingletonComponent.class})
public final class ResticModule {
    @org.jetbrains.annotations.NotNull()
    public static final com.xayah.core.restic.di.ResticModule INSTANCE = null;
    
    private ResticModule() {
        super();
    }
    
    @dagger.Provides()
    @javax.inject.Singleton()
    @org.jetbrains.annotations.NotNull()
    public final com.xayah.core.restic.ResticLogger provideResticLogger(@com.xayah.core.datastore.di.Dispatcher(dbDispatchers = com.xayah.core.datastore.di.DbDispatchers.IO)
    @org.jetbrains.annotations.NotNull()
    kotlinx.coroutines.CoroutineDispatcher ioDispatcher) {
        return null;
    }
}