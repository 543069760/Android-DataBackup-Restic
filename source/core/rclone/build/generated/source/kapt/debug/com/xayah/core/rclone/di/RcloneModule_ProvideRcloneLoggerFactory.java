package com.xayah.core.rclone.di;

import com.xayah.core.rclone.RcloneLogger;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
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
public final class RcloneModule_ProvideRcloneLoggerFactory implements Factory<RcloneLogger> {
  private final Provider<CoroutineDispatcher> ioDispatcherProvider;

  public RcloneModule_ProvideRcloneLoggerFactory(
      Provider<CoroutineDispatcher> ioDispatcherProvider) {
    this.ioDispatcherProvider = ioDispatcherProvider;
  }

  @Override
  public RcloneLogger get() {
    return provideRcloneLogger(ioDispatcherProvider.get());
  }

  public static RcloneModule_ProvideRcloneLoggerFactory create(
      Provider<CoroutineDispatcher> ioDispatcherProvider) {
    return new RcloneModule_ProvideRcloneLoggerFactory(ioDispatcherProvider);
  }

  public static RcloneLogger provideRcloneLogger(CoroutineDispatcher ioDispatcher) {
    return Preconditions.checkNotNullFromProvides(RcloneModule.INSTANCE.provideRcloneLogger(ioDispatcher));
  }
}
