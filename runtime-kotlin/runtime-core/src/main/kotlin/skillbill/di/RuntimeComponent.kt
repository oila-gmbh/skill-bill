package skillbill.di

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import skillbill.agentaddon.AgentAddonSelectionResolver
import skillbill.application.agentrun.AgentRunGoalRunnerSubtaskLauncher
import skillbill.application.agentrun.AgentRunService
import skillbill.application.config.ConfigResolutionService
import skillbill.application.featuretask.FeatureTaskContinuationLookupService
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver
import skillbill.application.featuretask.FeatureTaskRuntimeRunner
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.featuretask.FeatureTaskRuntimeWorkerCoordinator
import skillbill.application.goalrunner.DefaultGoalRunnerExecutionCoordinator
import skillbill.application.goalrunner.GoalLifecycleTelemetryEmitter
import skillbill.application.goalrunner.GoalOperatorDecisionService
import skillbill.application.goalrunner.planning.ChildAwareGoalPlanningRefreshLiveness
import skillbill.application.goalrunner.planning.DefaultGoalPlanningSweep
import skillbill.application.goalrunner.planning.DurableGoalPlanningAttemptRecorder
import skillbill.application.goalrunner.planning.DurableGoalPlanningRejectionRecorder
import skillbill.application.goalrunner.planning.GoalPlanningAttemptRecorder
import skillbill.application.goalrunner.planning.GoalPlanningLogService
import skillbill.application.goalrunner.planning.GoalPlanningRefreshLiveness
import skillbill.application.goalrunner.planning.GoalPlanningRejectionRecorder
import skillbill.application.goalrunner.planning.GoalPlanningStatusReasonCoherence
import skillbill.application.goalrunner.planning.GoalPlanningSweep
import skillbill.application.goalrunner.planning.LaunchAlignedGoalPlanningStatusReasonCoherence
import skillbill.application.goalrunner.GoalPreflightService
import skillbill.application.goalrunner.GoalRunner
import skillbill.application.goalrunner.GoalRunnerChildRepairStore
import skillbill.application.goalrunner.GoalRunnerExecutionCoordinator
import skillbill.application.goalrunner.GoalRunnerStatusService
import skillbill.application.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.application.goalrunner.WorkflowGoalRunnerManifestStore
import skillbill.application.goalrunner.WorkflowGoalRunnerOutcomeStore
import skillbill.application.install.ExternalAddonOverlayService
import skillbill.application.install.InstallService
import skillbill.application.learning.LearningService
import skillbill.application.review.ParallelCodeReviewRunner
import skillbill.application.review.ReviewService
import skillbill.application.review.ReviewSnapshotPruneService
import skillbill.application.scaffold.InstallAgentService
import skillbill.application.scaffold.McpRegistrationService
import skillbill.application.scaffold.NativeAgentInstallService
import skillbill.application.scaffold.RepoSourceDiscoveryService
import skillbill.application.scaffold.RepoValidationService
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.application.scaffold.ScaffoldService
import skillbill.application.scaffold.SkillRemoveService
import skillbill.application.scaffold.UnsupportedScaffoldService
import skillbill.application.system.SystemService
import skillbill.application.system.UninstallFileSystemService
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.application.telemetry.TelemetryLevelMutationService
import skillbill.application.telemetry.TelemetryService
import skillbill.application.work.IdeStatusService
import skillbill.application.work.WorkListService
import skillbill.application.workflow.GoalPlanningPreparationCheckpoint
import skillbill.application.workflow.WorkflowService
import skillbill.contracts.goalplanning.GoalPlanningDiscoveryExclusions
import skillbill.domain.skillremove.SkillRemoveFileSystem
import skillbill.goalplanning.FileSystemGoalPlanningBoundaryBodyResolver
import skillbill.goalplanning.FileSystemGoalPlanningContextDiscovery
import skillbill.infrastructure.fs.AgentRunReviewIsolationResolver
import skillbill.infrastructure.fs.ClasspathReviewSpecialistContractProvider
import skillbill.infrastructure.fs.DecompositionManifestValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeBuildReceiptValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeHandoffFoundationValidatorInfraAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeImplementationAttemptValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimePhaseOutputValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimePlanningProjectionValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeQuarantineValidatorAdapter
import skillbill.infrastructure.fs.FileExternalAddonSourceConfigStore
import skillbill.infrastructure.fs.FileExternalAgentAddonSourceConfigStore
import skillbill.infrastructure.fs.FileSystemBaselineManifestPersistence
import skillbill.infrastructure.fs.FileSystemCheckedOutBranchSource
import skillbill.infrastructure.fs.FileSystemDeclaredReviewSpecialists
import skillbill.infrastructure.fs.FileSystemDecompositionManifestFileStore
import skillbill.infrastructure.fs.FileSystemDiffResolver
import skillbill.infrastructure.fs.FileSystemExternalAddonOverlay
import skillbill.infrastructure.fs.FileSystemFeatureSpecPathResolver
import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeRunInvariantsSource
import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeSharedEvidenceStore
import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeSpecStatusWriter
import skillbill.infrastructure.fs.FileSystemInstallAgentTargets
import skillbill.infrastructure.fs.FileSystemInstallApplyExecution
import skillbill.infrastructure.fs.FileSystemInstallMcpRegistration
import skillbill.infrastructure.fs.FileSystemInstallNativeAgentLinks
import skillbill.infrastructure.fs.FileSystemInstallPlanningFacts
import skillbill.infrastructure.fs.FileSystemInstallPlatformSkillMaterialization
import skillbill.infrastructure.fs.FileSystemInstallReconcile
import skillbill.infrastructure.fs.FileSystemInstallReconcileApply
import skillbill.infrastructure.fs.FileSystemInstallSelectionPersistence
import skillbill.infrastructure.fs.FileSystemInstallSkillLink
import skillbill.infrastructure.fs.FileSystemInstallStagingIntent
import skillbill.infrastructure.fs.FileSystemInstalledPlatformPackCatalog
import skillbill.infrastructure.fs.FileSystemInstalledWorkspaceBaselineStatus
import skillbill.infrastructure.fs.FileSystemRepoLocalConfig
import skillbill.infrastructure.fs.FileSystemRepoSourceDiscoveryGateway
import skillbill.infrastructure.fs.FileSystemRepoValidationGateway
import skillbill.infrastructure.fs.FileSystemReviewAttribution
import skillbill.infrastructure.fs.FileSystemReviewEvidenceBrokerFactory
import skillbill.infrastructure.fs.FileSystemReviewInputSource
import skillbill.infrastructure.fs.FileSystemReviewLaunchAgentStaging
import skillbill.infrastructure.fs.FileSystemReviewNativeAgentPreflight
import skillbill.infrastructure.fs.FileSystemReviewRubricResolver
import skillbill.infrastructure.fs.FileSystemReviewSnapshotGateway
import skillbill.infrastructure.fs.FileSystemScaffoldCatalogGateway
import skillbill.infrastructure.fs.FileSystemScaffoldGateway
import skillbill.infrastructure.fs.FileSystemScaffoldGeneratedStaging
import skillbill.infrastructure.fs.FileSystemScaffoldInstallLink
import skillbill.infrastructure.fs.FileSystemScaffoldManifestPersistence
import skillbill.infrastructure.fs.FileSystemScaffoldRepoValidation
import skillbill.infrastructure.fs.FileSystemScaffoldSourceLoader
import skillbill.infrastructure.fs.FileSystemSkillRemoveFileSystem
import skillbill.infrastructure.fs.FileSystemSpecScratchStore
import skillbill.infrastructure.fs.FileSystemUninstallFileSystemGateway
import skillbill.infrastructure.fs.FileSystemUnsupportedScaffoldGateway
import skillbill.infrastructure.fs.FileTelemetryConfigStore
import skillbill.infrastructure.fs.GhGoalPullRequestPort
import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.infrastructure.fs.GoalObservabilityEventValidatorAdapter
import skillbill.infrastructure.fs.GoalPlanningPreparationEnvelopeValidatorAdapter
import skillbill.infrastructure.fs.GoalProgressEventValidatorAdapter
import skillbill.infrastructure.fs.IdeStatusValidatorAdapter
import skillbill.infrastructure.fs.InstallPlanWireValidatorAdapter
import skillbill.infrastructure.fs.JdkFeatureTaskRuntimeWorkerSupervisor
import skillbill.infrastructure.fs.JdkRuntimeDiagnostics
import skillbill.infrastructure.fs.JdkRuntimeTimingPort
import skillbill.infrastructure.fs.ProducerOutputEvidenceValidatorAdapter
import skillbill.infrastructure.fs.RejectedOutputDiagnosticMetadataValidatorAdapter
import skillbill.infrastructure.fs.ReviewContextEnvelopeValidatorAdapter
import skillbill.infrastructure.fs.WorkflowSnapshotValidatorInfraAdapter
import skillbill.infrastructure.fs.validation.FileSystemValidationGateRunner
import skillbill.infrastructure.http.HttpTelemetryClient
import skillbill.infrastructure.http.JdkHttpRequester
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.install.model.InstallPlanWireValidator
import skillbill.launcher.agentrun.FileSystemAgentRunLauncher
import skillbill.launcher.agentrun.PathExecutableLookup
import skillbill.launcher.review.UnixSocketGovernedReviewEvidenceEndpointBinder
import skillbill.model.EnvironmentContext
import skillbill.model.OptionalCallbacks
import skillbill.model.RuntimeContext
import skillbill.model.TransportContext
import skillbill.model.WorkflowOpsContext
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.goalrunner.planning.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.install.addon.ExternalAddonOverlayPort
import skillbill.ports.install.addon.ExternalAddonSourceConfigPort
import skillbill.ports.install.agent.InstallAgentTargetPort
import skillbill.ports.install.apply.InstallApplyExecutionPort
import skillbill.ports.install.baseline.BaselineManifestPersistencePort
import skillbill.ports.install.baseline.InstalledWorkspaceBaselineStatusPort
import skillbill.ports.install.link.InstallSkillLinkPort
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.install.plan.InstallPlanningFactsPort
import skillbill.ports.install.plan.InstallPlatformSkillMaterializationPort
import skillbill.ports.install.plan.InstallStagingIntentPort
import skillbill.ports.install.reconcile.InstallReconcileApplyPort
import skillbill.ports.install.reconcile.InstallReconcilePort
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.ProducerOutputEvidenceValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.review.DeclaredReviewSpecialistsPort
import skillbill.ports.review.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.ReviewAttributionPort
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.ReviewInputSource
import skillbill.ports.review.ReviewLaunchAgentStagingPort
import skillbill.ports.review.ReviewLaunchIsolationResolver
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.ReviewSnapshotGateway
import skillbill.ports.review.ReviewSpecialistContractProvider
import skillbill.ports.scaffold.InstalledPlatformPackCatalogPort
import skillbill.ports.scaffold.RepoSourceDiscoveryGateway
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.scaffold.UnsupportedScaffoldGateway
import skillbill.ports.scaffold.install.ScaffoldInstallLinkPort
import skillbill.ports.scaffold.manifest.ScaffoldManifestPersistencePort
import skillbill.ports.scaffold.repo.ScaffoldRepoValidationPort
import skillbill.ports.scaffold.source.ScaffoldSourceLoaderPort
import skillbill.ports.scaffold.staging.ScaffoldGeneratedStagingPort
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.system.UninstallFileSystemGateway
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSpecStatusWriter
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.telemetry.TelemetryClient
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetryLevelMutator
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.ports.telemetry.UnconfiguredHttpRequester
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.validation.RepoValidationGateway
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.specscratch.SpecScratchStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.telemetry.settings.DefaultTelemetrySettingsProvider
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeBuildReceiptValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeImplementationAttemptValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeQuarantineValidator
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.GoalPlanningPreparationEnvelopeValidator
import skillbill.workflow.goal.GoalProgressEventValidator
import skillbill.workflow.idestatus.IdeStatusValidator
import skillbill.workflow.engine.WorkflowSnapshotValidator
import java.nio.file.Path
import java.time.Clock

@Component
@Suppress("TooManyFunctions")
abstract class RuntimeComponent(
  private val inputRuntimeContext: RuntimeContext,
) {
  abstract val featureTaskContinuationLookupService: FeatureTaskContinuationLookupService
  abstract val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService

  /*
   * Composition-root exception: RuntimeComponent is the only runtime-core surface allowed to
   * know concrete filesystem, HTTP, and SQLite adapters. Downstream adapters consume the
   * application services and ports exposed below instead of importing these implementations
   * through runtime-core as an umbrella module.
   */
  @Provides @JvmSynthetic fun runtimeContext() = RuntimeComponentBindingsA.runtimeContext(inputRuntimeContext)
  @Provides @JvmSynthetic fun environmentContext(ctx: RuntimeContext) = RuntimeComponentBindingsA.environmentContext(ctx)
  @Provides @JvmSynthetic fun transportContext(ctx: RuntimeContext) = RuntimeComponentBindingsA.transportContext(ctx)
  @Provides @JvmSynthetic fun workflowOpsContext(ctx: RuntimeContext) = RuntimeComponentBindingsA.workflowOpsContext(ctx)
  @Provides @JvmSynthetic fun optionalCallbacks(ctx: RuntimeContext) = RuntimeComponentBindingsA.optionalCallbacks(ctx)
  @Provides @JvmSynthetic fun databaseSessionFactory(context: EnvironmentContext) = RuntimeComponentBindingsA.databaseSessionFactory(context)
  @Provides @JvmSynthetic internal fun telemetryConfigStore(store: FileTelemetryConfigStore) = RuntimeComponentBindingsA.telemetryConfigStore(store)
  @Provides @JvmSynthetic internal fun externalAddonSourceConfigPort(store: FileExternalAddonSourceConfigStore,) = RuntimeComponentBindingsA.externalAddonSourceConfigPort(store)
  @Provides @JvmSynthetic internal fun externalAddonOverlayPort(adapter: FileSystemExternalAddonOverlay) = RuntimeComponentBindingsA.externalAddonOverlayPort(adapter)
  @Provides @JvmSynthetic internal fun telemetrySettingsProvider(provider: DefaultTelemetrySettingsProvider) = RuntimeComponentBindingsA.telemetrySettingsProvider(provider)
  @Provides @JvmSynthetic internal fun telemetryClient(client: HttpTelemetryClient) = RuntimeComponentBindingsA.telemetryClient(client)
  @Provides @JvmSynthetic internal fun telemetryLevelMutator(service: TelemetryLevelMutationService) = RuntimeComponentBindingsA.telemetryLevelMutator(service)
  @Provides @JvmSynthetic internal fun installPlanningFactsPort(adapter: FileSystemInstallPlanningFacts) = RuntimeComponentBindingsA.installPlanningFactsPort(adapter)
  @Provides @JvmSynthetic internal fun installPlatformSkillMaterializationPort(adapter: FileSystemInstallPlatformSkillMaterialization,) = RuntimeComponentBindingsA.installPlatformSkillMaterializationPort(adapter)
  @Provides @JvmSynthetic internal fun installStagingIntentPort(adapter: FileSystemInstallStagingIntent) = RuntimeComponentBindingsA.installStagingIntentPort(adapter)
  @Provides @JvmSynthetic internal fun installApplyExecutionPort(adapter: FileSystemInstallApplyExecution) = RuntimeComponentBindingsA.installApplyExecutionPort(adapter)
  @Provides @JvmSynthetic internal fun installReconcilePort(adapter: FileSystemInstallReconcile) = RuntimeComponentBindingsA.installReconcilePort(adapter)
  @Provides @JvmSynthetic internal fun installReconcileApplyPort(adapter: FileSystemInstallReconcileApply) = RuntimeComponentBindingsA.installReconcileApplyPort(adapter)
  @Provides @JvmSynthetic internal fun baselineManifestPersistencePort(adapter: FileSystemBaselineManifestPersistence,) = RuntimeComponentBindingsA.baselineManifestPersistencePort(adapter)
  @Provides @JvmSynthetic internal fun installedWorkspaceBaselineStatusPort(adapter: FileSystemInstalledWorkspaceBaselineStatus,) = RuntimeComponentBindingsA.installedWorkspaceBaselineStatusPort(adapter)
  @Provides @JvmSynthetic internal fun installSkillLinkPort(adapter: FileSystemInstallSkillLink) = RuntimeComponentBindingsA.installSkillLinkPort(adapter)
  @Provides @JvmSynthetic internal fun installAgentTargetPort(adapter: FileSystemInstallAgentTargets) = RuntimeComponentBindingsA.installAgentTargetPort(adapter)
  @Provides @JvmSynthetic internal fun installNativeAgentLinkPort(adapter: FileSystemInstallNativeAgentLinks) = RuntimeComponentBindingsA.installNativeAgentLinkPort(adapter)
  @Provides @JvmSynthetic internal fun installMcpRegistrationPort(adapter: FileSystemInstallMcpRegistration) = RuntimeComponentBindingsA.installMcpRegistrationPort(adapter)
  @Provides @JvmSynthetic internal fun agentRunLauncher(callbacks: OptionalCallbacks, adapter: FileSystemAgentRunLauncher) = RuntimeComponentBindingsA.agentRunLauncher(callbacks, adapter)
  @Provides @JvmSynthetic fun executableLookup(callbacks: OptionalCallbacks) = RuntimeComponentBindingsA.executableLookup(callbacks)
  @Provides @JvmSynthetic internal fun goalRunnerSubtaskLauncher(adapter: AgentRunGoalRunnerSubtaskLauncher) = RuntimeComponentBindingsA.goalRunnerSubtaskLauncher(adapter)
  @Provides @JvmSynthetic internal fun goalPlanningSweep(sweep: DefaultGoalPlanningSweep) = RuntimeComponentBindingsA.goalPlanningSweep(sweep)
  @Provides @JvmSynthetic internal fun goalPlanningRefreshLiveness(adapter: ChildAwareGoalPlanningRefreshLiveness,) = RuntimeComponentBindingsA.goalPlanningRefreshLiveness(adapter)
  @Provides @JvmSynthetic internal fun goalPlanningStatusReasonCoherence(adapter: LaunchAlignedGoalPlanningStatusReasonCoherence,) = RuntimeComponentBindingsA.goalPlanningStatusReasonCoherence(adapter)
  @Provides @JvmSynthetic internal fun goalRunnerExecutionCoordinator(coordinator: DefaultGoalRunnerExecutionCoordinator,) = RuntimeComponentBindingsA.goalRunnerExecutionCoordinator(coordinator)
  @Provides @JvmSynthetic internal fun goalPlanningAttemptRecorder(recorder: DurableGoalPlanningAttemptRecorder) = RuntimeComponentBindingsA.goalPlanningAttemptRecorder(recorder)
  @Provides @JvmSynthetic internal fun goalPlanningRejectionRecorder(recorder: DurableGoalPlanningRejectionRecorder,) = RuntimeComponentBindingsA.goalPlanningRejectionRecorder(recorder)
  @Provides @JvmSynthetic internal fun goalPlanningContextDiscovery(adapter: FileSystemGoalPlanningContextDiscovery,) = RuntimeComponentBindingsA.goalPlanningContextDiscovery(adapter)
  @Provides @JvmSynthetic internal fun goalPlanningBoundaryBodyResolver(adapter: FileSystemGoalPlanningBoundaryBodyResolver,) = RuntimeComponentBindingsA.goalPlanningBoundaryBodyResolver(adapter)
  @Provides @JvmSynthetic internal fun goalLifecycleTelemetryEmitter(service: LifecycleTelemetryService) = RuntimeComponentBindingsA.goalLifecycleTelemetryEmitter(service)
  @Provides @JvmSynthetic internal fun runtimeClock() = RuntimeComponentBindingsA.runtimeClock()
  @Provides @JvmSynthetic internal fun runtimeTimingPort(callbacks: OptionalCallbacks, adapter: JdkRuntimeTimingPort) = RuntimeComponentBindingsA.runtimeTimingPort(callbacks, adapter)
  @Provides @JvmSynthetic internal fun runtimeDiagnostics(adapter: JdkRuntimeDiagnostics) = RuntimeComponentBindingsA.runtimeDiagnostics(adapter)
  @Provides @JvmSynthetic internal fun reviewNativeAgentPreflightPort(callbacks: OptionalCallbacks,
    adapter: FileSystemReviewNativeAgentPreflight,) = RuntimeComponentBindingsA.reviewNativeAgentPreflightPort(callbacks, adapter)
  @Provides @JvmSynthetic internal fun reviewLaunchAgentStagingPort(adapter: FileSystemReviewLaunchAgentStaging,) = RuntimeComponentBindingsA.reviewLaunchAgentStagingPort(adapter)
  @Provides @JvmSynthetic internal fun declaredReviewSpecialistsPort(adapter: FileSystemDeclaredReviewSpecialists,) = RuntimeComponentBindingsA.declaredReviewSpecialistsPort(adapter)
  @Provides @JvmSynthetic internal fun installedPlatformPackCatalogPort(adapter: FileSystemInstalledPlatformPackCatalog,) = RuntimeComponentBindingsA.installedPlatformPackCatalogPort(adapter)
  @Provides @JvmSynthetic internal fun goalRunnerManifestStore(adapter: WorkflowGoalRunnerManifestStore) = RuntimeComponentBindingsA.goalRunnerManifestStore(adapter)
  @Provides @JvmSynthetic internal fun goalRunnerWorkflowOutcomeStore(adapter: WorkflowGoalRunnerOutcomeStore,) = RuntimeComponentBindingsA.goalRunnerWorkflowOutcomeStore(adapter)
  @Provides @JvmSynthetic internal fun goalRunnerAttemptLedgerStore(adapter: WorkflowGoalRunnerOutcomeStore) = RuntimeComponentBindingsA.goalRunnerAttemptLedgerStore(adapter)
  @Provides @JvmSynthetic internal fun goalRunnerChildRepairStore(adapter: WorkflowGoalRunnerOutcomeStore) = RuntimeComponentBindingsA.goalRunnerChildRepairStore(adapter)
  @Provides @JvmSynthetic internal fun goalPullRequestPort(callbacks: OptionalCallbacks, adapter: GhGoalPullRequestPort) = RuntimeComponentBindingsA.goalPullRequestPort(callbacks, adapter)
  @Provides @JvmSynthetic internal fun installSelectionPersistencePort(adapter: FileSystemInstallSelectionPersistence,) = RuntimeComponentBindingsA.installSelectionPersistencePort(adapter)
  @Provides @JvmSynthetic internal fun repoLocalConfigPort(adapter: FileSystemRepoLocalConfig) = RuntimeComponentBindingsA.repoLocalConfigPort(adapter)
  @Provides @JvmSynthetic internal fun scaffoldGateway(gateway: FileSystemScaffoldGateway) = RuntimeComponentBindingsA.scaffoldGateway(gateway)
  @Provides @JvmSynthetic internal fun scaffoldSourceLoaderPort(adapter: FileSystemScaffoldSourceLoader) = RuntimeComponentBindingsB.scaffoldSourceLoaderPort(adapter)
  @Provides @JvmSynthetic internal fun scaffoldManifestPersistencePort(adapter: FileSystemScaffoldManifestPersistence,) = RuntimeComponentBindingsB.scaffoldManifestPersistencePort(adapter)
  @Provides @JvmSynthetic internal fun scaffoldGeneratedStagingPort(adapter: FileSystemScaffoldGeneratedStaging,) = RuntimeComponentBindingsB.scaffoldGeneratedStagingPort(adapter)
  @Provides @JvmSynthetic internal fun scaffoldInstallLinkPort(adapter: FileSystemScaffoldInstallLink) = RuntimeComponentBindingsB.scaffoldInstallLinkPort(adapter)
  @Provides @JvmSynthetic internal fun scaffoldRepoValidationPort(adapter: FileSystemScaffoldRepoValidation) = RuntimeComponentBindingsB.scaffoldRepoValidationPort(adapter)
  @Provides @JvmSynthetic internal fun unsupportedScaffoldGateway(gateway: FileSystemUnsupportedScaffoldGateway) = RuntimeComponentBindingsB.unsupportedScaffoldGateway(gateway)
  @Provides @JvmSynthetic internal fun scaffoldCatalogGateway(gateway: FileSystemScaffoldCatalogGateway) = RuntimeComponentBindingsB.scaffoldCatalogGateway(gateway)
  @Provides @JvmSynthetic internal fun diffResolverPort(adapter: FileSystemDiffResolver) = RuntimeComponentBindingsB.diffResolverPort(adapter)
  @Provides @JvmSynthetic internal fun sharedEvidenceResolverPort(adapter: FileSystemFeatureTaskRuntimeSharedEvidenceStore,) = RuntimeComponentBindingsB.sharedEvidenceResolverPort(adapter)
  @Provides @JvmSynthetic internal fun sharedEvidenceLocatorReadPort(adapter: FileSystemFeatureTaskRuntimeSharedEvidenceStore,) = RuntimeComponentBindingsB.sharedEvidenceLocatorReadPort(adapter)
  @Provides @JvmSynthetic internal fun repoSourceDiscoveryGateway(gateway: FileSystemRepoSourceDiscoveryGateway) = RuntimeComponentBindingsB.repoSourceDiscoveryGateway(gateway)
  @Provides @JvmSynthetic internal fun repoValidationGateway(gateway: FileSystemRepoValidationGateway) = RuntimeComponentBindingsB.repoValidationGateway(gateway)
  @Provides @JvmSynthetic internal fun validationGateRunner(runner: FileSystemValidationGateRunner) = RuntimeComponentBindingsB.validationGateRunner(runner)
  @Provides @JvmSynthetic internal fun uninstallFileSystemGateway(gateway: FileSystemUninstallFileSystemGateway) = RuntimeComponentBindingsB.uninstallFileSystemGateway(gateway)
  @Provides @JvmSynthetic internal fun reviewSnapshotGateway(gateway: FileSystemReviewSnapshotGateway) = RuntimeComponentBindingsB.reviewSnapshotGateway(gateway)
  @Provides @JvmSynthetic internal fun reviewInputSource(source: FileSystemReviewInputSource) = RuntimeComponentBindingsB.reviewInputSource(source)
  @Provides @JvmSynthetic internal fun reviewAttributionPort(adapter: FileSystemReviewAttribution) = RuntimeComponentBindingsB.reviewAttributionPort(adapter)
  @Provides @JvmSynthetic internal fun reviewRubricResolver(adapter: FileSystemReviewRubricResolver) = RuntimeComponentBindingsB.reviewRubricResolver(adapter)
  @Provides @JvmSynthetic internal fun reviewSpecialistContractProvider(adapter: ClasspathReviewSpecialistContractProvider,) = RuntimeComponentBindingsB.reviewSpecialistContractProvider(adapter)
  @Provides @JvmSynthetic internal fun featureTaskRuntimeRunInvariantsSource(adapter: FileSystemFeatureTaskRuntimeRunInvariantsSource,) = RuntimeComponentBindingsB.featureTaskRuntimeRunInvariantsSource(adapter)
  @Provides @JvmSynthetic fun agentAddonSelectionPort() = RuntimeComponentBindingsB.agentAddonSelectionPort()
  @Provides @JvmSynthetic internal fun externalAgentAddonSourceConfigPort(store: FileExternalAgentAddonSourceConfigStore,) = RuntimeComponentBindingsB.externalAgentAddonSourceConfigPort(store)
  @Provides @JvmSynthetic internal fun featureTaskRuntimeWorkerSupervisor(adapter: JdkFeatureTaskRuntimeWorkerSupervisor,) = RuntimeComponentBindingsB.featureTaskRuntimeWorkerSupervisor(adapter)
  @Provides @JvmSynthetic internal fun featureTaskRuntimeSpecStatusWriter(adapter: FileSystemFeatureTaskRuntimeSpecStatusWriter,) = RuntimeComponentBindingsB.featureTaskRuntimeSpecStatusWriter(adapter)
  @Provides @JvmSynthetic internal fun skillRemoveFileSystem(fileSystem: FileSystemSkillRemoveFileSystem) = RuntimeComponentBindingsB.skillRemoveFileSystem(fileSystem)
  @Provides @JvmSynthetic internal fun workflowGitOperations(workflowOps: WorkflowOpsContext,
    git: GitWorkflowGitOperations,) = RuntimeComponentBindingsB.workflowGitOperations(workflowOps, git)
  @Provides @JvmSynthetic internal fun decompositionManifestFileStore(store: FileSystemDecompositionManifestFileStore,) = RuntimeComponentBindingsB.decompositionManifestFileStore(store)
  @Provides @JvmSynthetic internal fun specScratchStore(store: FileSystemSpecScratchStore) = RuntimeComponentBindingsB.specScratchStore(store)
  @Provides @JvmSynthetic internal fun installPlanWireValidator(adapter: InstallPlanWireValidatorAdapter) = RuntimeComponentBindingsB.installPlanWireValidator(adapter)
  @Provides @JvmSynthetic internal fun decompositionManifestValidator(adapter: DecompositionManifestValidatorAdapter,) = RuntimeComponentBindingsB.decompositionManifestValidator(adapter)
  @Provides @JvmSynthetic internal fun workflowSnapshotValidator(adapter: WorkflowSnapshotValidatorInfraAdapter) = RuntimeComponentBindingsB.workflowSnapshotValidator(adapter)
  @Provides @JvmSynthetic internal fun featureTaskRuntimePhaseOutputValidator(adapter: FeatureTaskRuntimePhaseOutputValidatorAdapter,) = RuntimeComponentBindingsB.featureTaskRuntimePhaseOutputValidator(adapter)
  @Provides @JvmSynthetic internal fun featureTaskRuntimePlanningProjectionValidator(adapter: FeatureTaskRuntimePlanningProjectionValidatorAdapter,) = RuntimeComponentBindingsB.featureTaskRuntimePlanningProjectionValidator(adapter)
  @Provides @JvmSynthetic internal fun featureTaskRuntimeBuildReceiptValidator(adapter: FeatureTaskRuntimeBuildReceiptValidatorAdapter,) = RuntimeComponentBindingsB.featureTaskRuntimeBuildReceiptValidator(adapter)
  @Provides @JvmSynthetic internal fun featureTaskRuntimeHandoffEnvelopeValidator(adapter: FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter,) = RuntimeComponentBindingsB.featureTaskRuntimeHandoffEnvelopeValidator(adapter)
  @Provides @JvmSynthetic internal fun featureTaskRuntimeHandoffFoundationValidator(adapter: FeatureTaskRuntimeHandoffFoundationValidatorInfraAdapter,) = RuntimeComponentBindingsB.featureTaskRuntimeHandoffFoundationValidator(adapter)
  @Provides @JvmSynthetic internal fun featureTaskRuntimeQuarantineValidator(adapter: FeatureTaskRuntimeQuarantineValidatorAdapter,) = RuntimeComponentBindingsB.featureTaskRuntimeQuarantineValidator(adapter)
  @Provides @JvmSynthetic internal fun featureTaskRuntimeImplementationAttemptValidator(adapter: FeatureTaskRuntimeImplementationAttemptValidatorAdapter,) = RuntimeComponentBindingsB.featureTaskRuntimeImplementationAttemptValidator(adapter)
  @Provides @JvmSynthetic fun rejectedOutputDiagnosticMetadataValidator() = RuntimeComponentBindingsB.rejectedOutputDiagnosticMetadataValidator()
  @Provides @JvmSynthetic fun producerOutputEvidenceValidator() = RuntimeComponentBindingsB.producerOutputEvidenceValidator()
  @Provides @JvmSynthetic internal fun goalPlanningPreparationEnvelopeValidator(adapter: GoalPlanningPreparationEnvelopeValidatorAdapter,) = RuntimeComponentBindingsB.goalPlanningPreparationEnvelopeValidator(adapter)
  @Provides @JvmSynthetic internal fun reviewContextEnvelopeValidator(adapter: ReviewContextEnvelopeValidatorAdapter,) = RuntimeComponentBindingsB.reviewContextEnvelopeValidator(adapter)
  @Provides @JvmSynthetic internal fun reviewEvidenceBrokerFactory(adapter: FileSystemReviewEvidenceBrokerFactory,) = RuntimeComponentBindingsB.reviewEvidenceBrokerFactory(adapter)
  @Provides @JvmSynthetic internal fun governedReviewEvidenceEndpointBinder(adapter: UnixSocketGovernedReviewEvidenceEndpointBinder,) = RuntimeComponentBindingsB.governedReviewEvidenceEndpointBinder(adapter)
  @Provides @JvmSynthetic internal fun reviewLaunchIsolationResolver(adapter: AgentRunReviewIsolationResolver) = RuntimeComponentBindingsB.reviewLaunchIsolationResolver(adapter)
  @Provides @JvmSynthetic internal fun featureSpecPathResolverPort(adapter: FileSystemFeatureSpecPathResolver) = RuntimeComponentBindingsB.featureSpecPathResolverPort(adapter)
  @Provides @JvmSynthetic internal fun goalObservabilityEventValidator(adapter: GoalObservabilityEventValidatorAdapter,) = RuntimeComponentBindingsB.goalObservabilityEventValidator(adapter)
  @Provides @JvmSynthetic internal fun goalProgressEventValidator(adapter: GoalProgressEventValidatorAdapter) = RuntimeComponentBindingsB.goalProgressEventValidator(adapter)
  @Provides @JvmSynthetic internal fun ideStatusValidator(adapter: IdeStatusValidatorAdapter) = RuntimeComponentBindingsB.ideStatusValidator(adapter)
  @Provides @JvmSynthetic internal fun checkedOutBranchSource(source: FileSystemCheckedOutBranchSource) = RuntimeComponentBindingsB.checkedOutBranchSource(source)
  @Provides @JvmSynthetic internal fun featureTaskRuntimeReviewDriver(runner: ParallelCodeReviewRunner) = RuntimeComponentBindingsB.featureTaskRuntimeReviewDriver(runner)
  @Provides @JvmSynthetic fun featureTaskPhaseSettlementRepository() = RuntimeComponentBindingsB.featureTaskPhaseSettlementRepository()
  abstract val parallelCodeReviewRunner: ParallelCodeReviewRunner

  // Exposed as a pre-built object so the CLI consumer need not resolve the infra-fs
  // RepoLocalConfigPort adapter type, which is not on the CLI module's compile classpath.
  abstract val configResolutionService: ConfigResolutionService
  abstract val externalAgentAddonSourceConfigPort: ExternalAgentAddonSourceConfigPort
  abstract val installService: InstallService
  abstract val externalAddonOverlayService: ExternalAddonOverlayService
  abstract val agentRunService: AgentRunService
  abstract val featureTaskRuntimePhaseRecorder: FeatureTaskRuntimePhaseRecorder
  abstract val featureTaskRuntimeRunner: FeatureTaskRuntimeRunner
  abstract val featureTaskRuntimeStatusService: FeatureTaskRuntimeStatusService
  abstract val featureTaskRuntimeWorkerCoordinator: FeatureTaskRuntimeWorkerCoordinator
  abstract val goalPlanningPreparationCheckpoint: GoalPlanningPreparationCheckpoint

  // Exposed as a pre-built object so the CLI consumer need not resolve the infra-fs adapter type,
  // which is not on the CLI module's compile classpath.
  abstract val featureTaskRuntimeRunInvariantsSource: FeatureTaskRuntimeRunInvariantsSource
  abstract val featureSpecPathResolverPort: FeatureSpecPathResolverPort
  abstract val goalRunner: GoalRunner
  abstract val goalPreflightService: GoalPreflightService
  abstract val goalRunnerStatusService: GoalRunnerStatusService
  abstract val goalPlanningLogService: GoalPlanningLogService
  abstract val goalOperatorDecisionService: GoalOperatorDecisionService
  abstract val installAgentService: InstallAgentService
  abstract val installSelectionPersistencePort: InstallSelectionPersistencePort
  abstract val installedWorkspaceBaselineStatusPort: InstalledWorkspaceBaselineStatusPort
  abstract val learningService: LearningService
  abstract val lifecycleTelemetryService: LifecycleTelemetryService
  abstract val mcpRegistrationService: McpRegistrationService
  abstract val nativeAgentInstallService: NativeAgentInstallService
  abstract val repoValidationService: RepoValidationService
  abstract val repoSourceDiscoveryService: RepoSourceDiscoveryService
  abstract val reviewService: ReviewService
  abstract val reviewSnapshotPruneService: ReviewSnapshotPruneService
  abstract val scaffoldCatalogService: ScaffoldCatalogService
  abstract val scaffoldService: ScaffoldService
  abstract val skillRemoveService: SkillRemoveService
  abstract val systemService: SystemService
  abstract val telemetryConfigStorePort: TelemetryConfigStore
  abstract val telemetryLevelMutator: TelemetryLevelMutator
  abstract val telemetryService: TelemetryService
  abstract val uninstallFileSystemService: UninstallFileSystemService
  abstract val unsupportedScaffoldService: UnsupportedScaffoldService
  abstract val workflowService: WorkflowService
  abstract val workListService: WorkListService
  abstract val ideStatusService: IdeStatusService
}
