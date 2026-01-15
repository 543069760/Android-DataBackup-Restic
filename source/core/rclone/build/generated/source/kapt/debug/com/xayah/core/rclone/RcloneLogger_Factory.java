package com.xayah.core.rclone;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;
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
    "KotlinInternalInJava"
})
public final class RcloneLogger_Factory implements Factory<RcloneLogger> {
  private final Provider<CoroutineDispatcher> ioDispatcherProvider;

  public RcloneLogger_Factory(Provider<CoroutineDispatcher> ioDispatcherProvider) {
    this.ioDispatcherProvider = ioDispatcherProvider;
  }

  @Override
  public RcloneLogger get() {
    return newInstance(ioDispatcherProvider.get());
  }

  public static RcloneLogger_Factory create(Provider<CoroutineDispatcher> ioDispatcherProvider) {
    return new RcloneLogger_Factory(ioDispatcherProvider);
  }

  public static RcloneLogger newInstance(CoroutineDispatcher ioDispatcher) {
    return new RcloneLogger(ioDispatcher);
  }
}
