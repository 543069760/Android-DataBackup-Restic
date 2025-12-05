package com.xayah.core.restic.di

import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.datastore.di.DbDispatchers.IO
import com.xayah.core.restic.ResticLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ResticModule {
    @Provides
    @Singleton
    fun provideResticLogger(
        @Dispatcher(IO) ioDispatcher: CoroutineDispatcher  // 添加参数
    ): ResticLogger = ResticLogger(ioDispatcher)
}