package com.xayah.core.rclone.di

import android.content.Context
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.datastore.di.DbDispatchers.IO
import com.xayah.core.rclone.RcloneLogger
import com.xayah.core.rclone.RcloneNative
import com.xayah.core.rclone.RcloneRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RcloneModule {
    @Provides
    @Singleton
    fun provideRcloneRepository(
        @ApplicationContext context: Context,
        logger: RcloneLogger,
        rcloneNative: RcloneNative
    ): RcloneRepository = RcloneRepository(context, logger, rcloneNative)

    @Provides
    @Singleton
    fun provideRcloneLogger(
        @Dispatcher(IO) ioDispatcher: CoroutineDispatcher
    ): RcloneLogger = RcloneLogger(ioDispatcher)

    @Provides
    @Singleton
    fun provideRcloneNative(logger: RcloneLogger): RcloneNative = RcloneNative(logger)
}