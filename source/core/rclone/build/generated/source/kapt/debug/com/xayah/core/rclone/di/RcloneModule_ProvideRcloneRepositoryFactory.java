package com.xayah.core.rclone.di;

import android.content.Context;
import com.xayah.core.rclone.RcloneLogger;
import com.xayah.core.rclone.RcloneNative;
import com.xayah.core.rclone.RcloneRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
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
public final class RcloneModule_ProvideRcloneRepositoryFactory implements Factory<RcloneRepository> {
  private final Provider<Context> contextProvider;

  private final Provider<RcloneLogger> loggerProvider;

  private final Provider<RcloneNative> rcloneNativeProvider;

  public RcloneModule_ProvideRcloneRepositoryFactory(Provider<Context> contextProvider,
      Provider<RcloneLogger> loggerProvider, Provider<RcloneNative> rcloneNativeProvider) {
    this.contextProvider = contextProvider;
    this.loggerProvider = loggerProvider;
    this.rcloneNativeProvider = rcloneNativeProvider;
  }

  @Override
  public RcloneRepository get() {
    return provideRcloneRepository(contextProvider.get(), loggerProvider.get(), rcloneNativeProvider.get());
  }

  public static RcloneModule_ProvideRcloneRepositoryFactory create(
      Provider<Context> contextProvider, Provider<RcloneLogger> loggerProvider,
      Provider<RcloneNative> rcloneNativeProvider) {
    return new RcloneModule_ProvideRcloneRepositoryFactory(contextProvider, loggerProvider, rcloneNativeProvider);
  }

  public static RcloneRepository provideRcloneRepository(Context context, RcloneLogger logger,
      RcloneNative rcloneNative) {
    return Preconditions.checkNotNullFromProvides(RcloneModule.INSTANCE.provideRcloneRepository(context, logger, rcloneNative));
  }
}
