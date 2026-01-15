package com.xayah.core.rclone;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class RcloneNative_Factory implements Factory<RcloneNative> {
  private final Provider<RcloneLogger> loggerProvider;

  public RcloneNative_Factory(Provider<RcloneLogger> loggerProvider) {
    this.loggerProvider = loggerProvider;
  }

  @Override
  public RcloneNative get() {
    return newInstance(loggerProvider.get());
  }

  public static RcloneNative_Factory create(Provider<RcloneLogger> loggerProvider) {
    return new RcloneNative_Factory(loggerProvider);
  }

  public static RcloneNative newInstance(RcloneLogger logger) {
    return new RcloneNative(logger);
  }
}
