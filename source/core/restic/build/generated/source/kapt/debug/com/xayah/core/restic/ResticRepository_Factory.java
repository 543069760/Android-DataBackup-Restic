package com.xayah.core.restic;

import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.Providers;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

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
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class ResticRepository_Factory implements Factory<ResticRepository> {
  private final Provider<Context> contextProvider;

  private final Provider<ResticLogger> loggerProvider;

  private final Provider<ResticNative> resticNativeProvider;

  public ResticRepository_Factory(Provider<Context> contextProvider,
      Provider<ResticLogger> loggerProvider, Provider<ResticNative> resticNativeProvider) {
    this.contextProvider = contextProvider;
    this.loggerProvider = loggerProvider;
    this.resticNativeProvider = resticNativeProvider;
  }

  @Override
  public ResticRepository get() {
    return newInstance(contextProvider.get(), loggerProvider.get(), resticNativeProvider.get());
  }

  public static ResticRepository_Factory create(javax.inject.Provider<Context> contextProvider,
      javax.inject.Provider<ResticLogger> loggerProvider,
      javax.inject.Provider<ResticNative> resticNativeProvider) {
    return new ResticRepository_Factory(Providers.asDaggerProvider(contextProvider), Providers.asDaggerProvider(loggerProvider), Providers.asDaggerProvider(resticNativeProvider));
  }

  public static ResticRepository_Factory create(Provider<Context> contextProvider,
      Provider<ResticLogger> loggerProvider, Provider<ResticNative> resticNativeProvider) {
    return new ResticRepository_Factory(contextProvider, loggerProvider, resticNativeProvider);
  }

  public static ResticRepository newInstance(Context context, ResticLogger logger,
      ResticNative resticNative) {
    return new ResticRepository(context, logger, resticNative);
  }
}
