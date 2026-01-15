package com.xayah.core.rclone;

import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class RcloneRepository_Factory implements Factory<RcloneRepository> {
  private final Provider<Context> contextProvider;

  private final Provider<RcloneLogger> loggerProvider;

  private final Provider<RcloneNative> rcloneNativeProvider;

  public RcloneRepository_Factory(Provider<Context> contextProvider,
      Provider<RcloneLogger> loggerProvider, Provider<RcloneNative> rcloneNativeProvider) {
    this.contextProvider = contextProvider;
    this.loggerProvider = loggerProvider;
    this.rcloneNativeProvider = rcloneNativeProvider;
  }

  @Override
  public RcloneRepository get() {
    return newInstance(contextProvider.get(), loggerProvider.get(), rcloneNativeProvider.get());
  }

  public static RcloneRepository_Factory create(Provider<Context> contextProvider,
      Provider<RcloneLogger> loggerProvider, Provider<RcloneNative> rcloneNativeProvider) {
    return new RcloneRepository_Factory(contextProvider, loggerProvider, rcloneNativeProvider);
  }

  public static RcloneRepository newInstance(Context context, RcloneLogger logger,
      RcloneNative rcloneNative) {
    return new RcloneRepository(context, logger, rcloneNative);
  }
}
