package com.xayah.core.restic;

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
public final class ResticNative_Factory implements Factory<ResticNative> {
  private final Provider<ResticLogger> loggerProvider;

  public ResticNative_Factory(Provider<ResticLogger> loggerProvider) {
    this.loggerProvider = loggerProvider;
  }

  @Override
  public ResticNative get() {
    return newInstance(loggerProvider.get());
  }

  public static ResticNative_Factory create(Provider<ResticLogger> loggerProvider) {
    return new ResticNative_Factory(loggerProvider);
  }

  public static ResticNative newInstance(ResticLogger logger) {
    return new ResticNative(logger);
  }
}
