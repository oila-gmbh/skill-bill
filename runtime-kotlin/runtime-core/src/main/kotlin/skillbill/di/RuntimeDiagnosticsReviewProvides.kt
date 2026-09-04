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

internal interface RuntimeDiagnosticsReviewProvides {
  @Provides @JvmSynthetic
  fun runtimeTimingPort(callbacks: OptionalCallbacks, adapter: JdkRuntimeTimingPort) =
    RuntimeGoalRunnerDiagnosticsBindings.runtimeTimingPort(callbacks, adapter)

  @Provides @JvmSynthetic
  fun runtimeDiagnostics(adapter: JdkRuntimeDiagnostics) =
    RuntimeGoalRunnerDiagnosticsBindings.runtimeDiagnostics(adapter)

  @Provides @JvmSynthetic
  fun shutdownHookPort(adapter: JdkShutdownHookPort) = RuntimeGoalRunnerDiagnosticsBindings.shutdownHookPort(adapter)

  @Provides @JvmSynthetic
  fun daemonThreadPort(adapter: JdkDaemonThreadPort) = RuntimeGoalRunnerDiagnosticsBindings.daemonThreadPort(adapter)

  @Provides @JvmSynthetic
  fun identifierGeneratorPort(adapter: JdkIdentifierGeneratorPort) =
    RuntimeGoalRunnerDiagnosticsBindings.identifierGeneratorPort(adapter)

  @Provides @JvmSynthetic
  fun repositoryRoot(context: EnvironmentContext): RepositoryRoot = RuntimeBootstrapBindings.repositoryRoot(context)

  @Provides @JvmSynthetic
  fun reviewNativeAgentPreflightPort(callbacks: OptionalCallbacks, adapter: FileSystemReviewNativeAgentPreflight) =
    RuntimeGoalRunnerDiagnosticsBindings.reviewNativeAgentPreflightPort(callbacks, adapter)

  @Provides @JvmSynthetic
  fun reviewLaunchAgentStagingPort(adapter: FileSystemReviewLaunchAgentStaging) =
    RuntimeGoalRunnerPersistenceReviewBindings.reviewLaunchAgentStagingPort(adapter)

  @Provides @JvmSynthetic
  fun declaredReviewSpecialistsPort(adapter: FileSystemDeclaredReviewSpecialists) =
    RuntimeGoalRunnerPersistenceReviewBindings.declaredReviewSpecialistsPort(adapter)

  @Provides @JvmSynthetic
  fun installedPlatformPackCatalogPort(adapter: FileSystemInstalledPlatformPackCatalog) =
    RuntimeGoalRunnerPersistenceReviewBindings.installedPlatformPackCatalogPort(adapter)
}
