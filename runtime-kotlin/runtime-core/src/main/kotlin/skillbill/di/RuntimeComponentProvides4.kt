package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.fs.FileSystemDeclaredReviewSpecialists
import skillbill.infrastructure.fs.FileSystemInstalledPlatformPackCatalog
import skillbill.infrastructure.fs.FileSystemReviewLaunchAgentStaging
import skillbill.infrastructure.fs.FileSystemReviewNativeAgentPreflight
import skillbill.infrastructure.fs.JdkDaemonThreadPort
import skillbill.infrastructure.fs.JdkIdentifierGeneratorPort
import skillbill.infrastructure.fs.JdkRuntimeDiagnostics
import skillbill.infrastructure.fs.JdkRuntimeTimingPort
import skillbill.infrastructure.fs.JdkShutdownHookPort
import skillbill.model.EnvironmentContext
import skillbill.model.OptionalCallbacks
import skillbill.model.RepositoryRoot

internal interface RuntimeComponentProvides4 {
  @Provides @JvmSynthetic
  fun runtimeTimingPort(callbacks: OptionalCallbacks, adapter: JdkRuntimeTimingPort) =
    RuntimeComponentBindingsA5.runtimeTimingPort(callbacks, adapter)

  @Provides @JvmSynthetic
  fun runtimeDiagnostics(adapter: JdkRuntimeDiagnostics) = RuntimeComponentBindingsA5.runtimeDiagnostics(adapter)

  @Provides @JvmSynthetic
  fun shutdownHookPort(adapter: JdkShutdownHookPort) = RuntimeComponentBindingsA5.shutdownHookPort(adapter)

  @Provides @JvmSynthetic
  fun daemonThreadPort(adapter: JdkDaemonThreadPort) = RuntimeComponentBindingsA5.daemonThreadPort(adapter)

  @Provides @JvmSynthetic
  fun identifierGeneratorPort(adapter: JdkIdentifierGeneratorPort) =
    RuntimeComponentBindingsA5.identifierGeneratorPort(adapter)

  @Provides @JvmSynthetic
  fun repositoryRoot(context: EnvironmentContext): RepositoryRoot = RuntimeComponentBindingsA1.repositoryRoot(context)

  @Provides @JvmSynthetic
  fun reviewNativeAgentPreflightPort(callbacks: OptionalCallbacks, adapter: FileSystemReviewNativeAgentPreflight) =
    RuntimeComponentBindingsA5.reviewNativeAgentPreflightPort(callbacks, adapter)

  @Provides @JvmSynthetic
  fun reviewLaunchAgentStagingPort(adapter: FileSystemReviewLaunchAgentStaging) =
    RuntimeComponentBindingsA6.reviewLaunchAgentStagingPort(adapter)

  @Provides @JvmSynthetic
  fun declaredReviewSpecialistsPort(adapter: FileSystemDeclaredReviewSpecialists) =
    RuntimeComponentBindingsA6.declaredReviewSpecialistsPort(adapter)

  @Provides @JvmSynthetic
  fun installedPlatformPackCatalogPort(adapter: FileSystemInstalledPlatformPackCatalog) =
    RuntimeComponentBindingsA6.installedPlatformPackCatalogPort(adapter)
}
