package com.xayah.core.restic;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.Providers;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import kotlinx.coroutines.CoroutineDispatcher;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("com.xayah.core.datastore.di.Dispatcher")
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
public final class ResticLogger_Factory implements Factory<ResticLogger> {
  private final Provider<CoroutineDispatcher> ioDispatcherProvider;

  public ResticLogger_Factory(Provider<CoroutineDispatcher> ioDispatcherProvider) {
    this.ioDispatcherProvider = ioDispatcherProvider;
  }

  @Override
  public ResticLogger get() {
    return newInstance(ioDispatcherProvider.get());
  }

  public static ResticLogger_Factory create(
      javax.inject.Provider<CoroutineDispatcher> ioDispatcherProvider) {
    return new ResticLogger_Factory(Providers.asDaggerProvider(ioDispatcherProvider));
  }

  public static ResticLogger_Factory create(Provider<CoroutineDispatcher> ioDispatcherProvider) {
    return new ResticLogger_Factory(ioDispatcherProvider);
  }

  public static ResticLogger newInstance(CoroutineDispatcher ioDispatcher) {
    return new ResticLogger(ioDispatcher);
  }
}
