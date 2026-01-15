package com.xayah.core.rclone.di;

import com.xayah.core.rclone.RcloneLogger;
import com.xayah.core.rclone.RcloneNative;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava"
})
public final class RcloneModule_ProvideRcloneNativeFactory implements Factory<RcloneNative> {
  private final Provider<RcloneLogger> loggerProvider;

  public RcloneModule_ProvideRcloneNativeFactory(Provider<RcloneLogger> loggerProvider) {
    this.loggerProvider = loggerProvider;
  }

  @Override
  public RcloneNative get() {
    return provideRcloneNative(loggerProvider.get());
  }

  public static RcloneModule_ProvideRcloneNativeFactory create(
      Provider<RcloneLogger> loggerProvider) {
    return new RcloneModule_ProvideRcloneNativeFactory(loggerProvider);
  }

  public static RcloneNative provideRcloneNative(RcloneLogger logger) {
    return Preconditions.checkNotNullFromProvides(RcloneModule.INSTANCE.provideRcloneNative(logger));
  }
}
