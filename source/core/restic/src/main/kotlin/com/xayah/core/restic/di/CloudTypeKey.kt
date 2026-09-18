package com.xayah.core.restic.di

import com.xayah.core.model.CloudType
import dagger.MapKey
import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.FUNCTION

/**
 * Dagger/Hilt 多绑定用的 MapKey：以 CloudType 作为 Map 的键。
 * 配合 @IntoMap 把每个 CloudResticBackend 实现按其云类型注册进
 * Map<CloudType, CloudResticBackend>，用于消灭散落的 when(CloudType) 分派。
 */
@MapKey
@Target(FUNCTION)
@Retention(RUNTIME)
annotation class CloudTypeKey(val value: CloudType)