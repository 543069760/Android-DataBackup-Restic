package com.xayah.core.restic.di

import com.xayah.core.model.CloudType
import com.xayah.core.restic.CloudResticBackend
import com.xayah.core.restic.ResticRepositoryAwsS3
import com.xayah.core.restic.ResticRepositoryCos
import com.xayah.core.restic.ResticRepositoryFtp
import com.xayah.core.restic.ResticRepositorySftp
import com.xayah.core.restic.ResticRepositoryWebdav
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

/**
 * 把 restic 后端按 CloudType 注册进 Map<CloudType, CloudResticBackend>。
 * 注入点用 Map<CloudType, @JvmSuppressWildcards CloudResticBackend> 消费，
 * 以多态 registry[type] 替代散落的 when(CloudType) 分派。
 *
 * 说明：ResticRepositoryXxx 均已是 @Singleton @Inject constructor，
 * @Binds 直接绑定其构造注入实例即可，无需 @Provides。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CloudResticBackendModule {

    @Binds
    @IntoMap
    @CloudTypeKey(CloudType.S3)
    abstract fun bindCosBackend(impl: ResticRepositoryCos): CloudResticBackend

    @Binds
    @IntoMap
    @CloudTypeKey(CloudType.FTP)
    abstract fun bindFtpBackend(impl: ResticRepositoryFtp): CloudResticBackend

    @Binds
    @IntoMap
    @CloudTypeKey(CloudType.WEBDAV)
    abstract fun bindWebdavBackend(impl: ResticRepositoryWebdav): CloudResticBackend

    @Binds
    @IntoMap
    @CloudTypeKey(CloudType.SFTP)
    abstract fun bindSftpBackend(impl: ResticRepositorySftp): CloudResticBackend

    @Binds
    @IntoMap
    @CloudTypeKey(CloudType.AWSS3)
    abstract fun bindAwsS3Backend(impl: ResticRepositoryAwsS3): CloudResticBackend
}