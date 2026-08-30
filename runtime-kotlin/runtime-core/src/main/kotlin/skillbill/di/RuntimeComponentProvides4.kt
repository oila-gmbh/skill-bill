package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.goalrunner.WorkflowGoalRunnerManifestStore
import skillbill.application.goalrunner.WorkflowGoalRunnerOutcomeStore
import skillbill.infrastructure.fs.FileSystemDeclaredReviewSpecialists
import skillbill.infrastructure.fs.FileSystemInstalledPlatformPackCatalog
import skillbill.infrastructure.fs.FileSystemReviewLaunchAgentStaging
import skillbill.infrastructure.fs.FileSystemReviewNativeAgentPreflight
import skillbill.infrastructure.fs.JdkRuntimeDiagnostics
import skillbill.infrastructure.fs.JdkRuntimeTimingPort
import skillbill.model.OptionalCallbacks

internal interface RuntimeComponentProvides4 {
  @Provides @JvmSynthetic
  fun runtimeTimingPort(callbacks: OptionalCallbacks, adapter: JdkRuntimeTimingPort) =
    RuntimeComponentBindingsA5.runtimeTimingPort(callbacks, adapter)

  @Provides @JvmSynthetic
  fun runtimeDiagnostics(adapter: JdkRuntimeDiagnostics) = RuntimeComponentBindingsA5.runtimeDiagnostics(adapter)

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

  @Provides @JvmSynthetic
  fun goalRunnerManifestStore(adapter: WorkflowGoalRunnerManifestStore) =
    RuntimeComponentBindingsA6.goalRunnerManifestStore(adapter)

  @Provides @JvmSynthetic
  fun goalRunnerWorkflowOutcomeStore(adapter: WorkflowGoalRunnerOutcomeStore) =
    RuntimeComponentBindingsA6.goalRunnerWorkflowOutcomeStore(adapter)

  @Provides @JvmSynthetic
  fun goalRunnerAttemptLedgerStore(adapter: WorkflowGoalRunnerOutcomeStore) =
    RuntimeComponentBindingsA6.goalRunnerAttemptLedgerStore(adapter)

  @Provides @JvmSynthetic
  fun goalRunnerChildRepairStore(adapter: WorkflowGoalRunnerOutcomeStore) =
    RuntimeComponentBindingsA6.goalRunnerChildRepairStore(adapter)
}
