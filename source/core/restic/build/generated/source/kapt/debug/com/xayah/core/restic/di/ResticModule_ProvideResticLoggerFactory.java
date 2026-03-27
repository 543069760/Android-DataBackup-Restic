package com.xayah.core.restic.di;

import com.xayah.core.restic.ResticLogger;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
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
public final class ResticModule_ProvideResticLoggerFactory implements Factory<ResticLogger> {
  private final Provider<CoroutineDispatcher> ioDispatcherProvider;

  public ResticModule_ProvideResticLoggerFactory(
      Provider<CoroutineDispatcher> ioDispatcherProvider) {
    this.ioDispatcherProvider = ioDispatcherProvider;
  }

  @Override
  public ResticLogger get() {
    return provideResticLogger(ioDispatcherProvider.get());
  }

  public static ResticModule_ProvideResticLoggerFactory create(
      javax.inject.Provider<CoroutineDispatcher> ioDispatcherProvider) {
    return new ResticModule_ProvideResticLoggerFactory(Providers.asDaggerProvider(ioDispatcherProvider));
  }

  public static ResticModule_ProvideResticLoggerFactory create(
      Provider<CoroutineDispatcher> ioDispatcherProvider) {
    return new ResticModule_ProvideResticLoggerFactory(ioDispatcherProvider);
  }

  public static ResticLogger provideResticLogger(CoroutineDispatcher ioDispatcher) {
    return Preconditions.checkNotNullFromProvides(ResticModule.INSTANCE.provideResticLogger(ioDispatcher));
  }
}
