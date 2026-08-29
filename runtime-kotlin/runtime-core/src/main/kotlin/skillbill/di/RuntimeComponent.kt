package skillbill.di

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import skillbill.application.agentrun.AgentRunGoalRunnerSubtaskLauncher
import skillbill.application.agentrun.AgentRunService
import skillbill.application.config.ConfigResolutionService
import skillbill.application.featuretask.FeatureTaskContinuationLookupService
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeRunner
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.featuretask.FeatureTaskRuntimeWorkerCoordinator
import skillbill.application.goalrunner.DefaultGoalRunnerExecutionCoordinator
import skillbill.application.goalrunner.GoalOperatorDecisionService
import skillbill.application.goalrunner.GoalPreflightService
import skillbill.application.goalrunner.GoalRunner
import skillbill.application.goalrunner.GoalRunnerStatusService
import skillbill.application.goalrunner.WorkflowGoalRunnerManifestStore
import skillbill.application.goalrunner.WorkflowGoalRunnerOutcomeStore
import skillbill.application.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.application.goalrunner.planning.ChildAwareGoalPlanningRefreshLiveness
import skillbill.application.goalrunner.planning.DefaultGoalPlanningSweep
import skillbill.application.goalrunner.planning.DurableGoalPlanningAttemptRecorder
import skillbill.application.goalrunner.planning.DurableGoalPlanningRejectionRecorder
import skillbill.application.goalrunner.planning.GoalPlanningLogService
import skillbill.application.goalrunner.planning.LaunchAlignedGoalPlanningStatusReasonCoherence
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
import skillbill.infrastructure.fs.ReviewContextEnvelopeValidatorAdapter
import skillbill.infrastructure.fs.WorkflowSnapshotValidatorInfraAdapter
import skillbill.infrastructure.fs.validation.FileSystemValidationGateRunner
import skillbill.infrastructure.http.HttpTelemetryClient
import skillbill.launcher.agentrun.FileSystemAgentRunLauncher
import skillbill.launcher.review.UnixSocketGovernedReviewEvidenceEndpointBinder
import skillbill.model.EnvironmentContext
import skillbill.model.OptionalCallbacks
import skillbill.model.RuntimeContext
import skillbill.model.WorkflowOpsContext
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.install.baseline.InstalledWorkspaceBaselineStatusPort
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetryLevelMutator
import skillbill.telemetry.settings.DefaultTelemetrySettingsProvider

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
  @Provides @JvmSynthetic
  fun runtimeContext() = RuntimeComponentBindingsA1.runtimeContext(inputRuntimeContext)

  @Provides @JvmSynthetic
  fun environmentContext(ctx: RuntimeContext) = RuntimeComponentBindingsA1.environmentContext(ctx)

  @Provides @JvmSynthetic
  fun transportContext(ctx: RuntimeContext) = RuntimeComponentBindingsA1.transportContext(ctx)

  @Provides @JvmSynthetic
  fun workflowOpsContext(ctx: RuntimeContext) = RuntimeComponentBindingsA1.workflowOpsContext(ctx)

  @Provides @JvmSynthetic
  fun optionalCallbacks(ctx: RuntimeContext) = RuntimeComponentBindingsA1.optionalCallbacks(ctx)

  @Provides @JvmSynthetic
  fun databaseSessionFactory(context: EnvironmentContext) = RuntimeComponentBindingsA1.databaseSessionFactory(context)

  @Provides @JvmSynthetic
  internal fun telemetryConfigStore(store: FileTelemetryConfigStore) =
    RuntimeComponentBindingsA1.telemetryConfigStore(store)

  @Provides @JvmSynthetic
  internal fun externalAddonSourceConfigPort(store: FileExternalAddonSourceConfigStore) =
    RuntimeComponentBindingsA1.externalAddonSourceConfigPort(store)

  @Provides @JvmSynthetic
  internal fun externalAddonOverlayPort(adapter: FileSystemExternalAddonOverlay) =
    RuntimeComponentBindingsA2.externalAddonOverlayPort(adapter)

  @Provides @JvmSynthetic
  internal fun telemetrySettingsProvider(provider: DefaultTelemetrySettingsProvider) =
    RuntimeComponentBindingsA2.telemetrySettingsProvider(provider)

  @Provides @JvmSynthetic
  internal fun telemetryClient(client: HttpTelemetryClient) = RuntimeComponentBindingsA2.telemetryClient(client)

  @Provides @JvmSynthetic
  internal fun telemetryLevelMutator(service: TelemetryLevelMutationService) =
    RuntimeComponentBindingsA2.telemetryLevelMutator(service)

  @Provides @JvmSynthetic
  internal fun installPlanningFactsPort(adapter: FileSystemInstallPlanningFacts) =
    RuntimeComponentBindingsA2.installPlanningFactsPort(adapter)

  @Provides @JvmSynthetic
  internal fun installPlatformSkillMaterializationPort(adapter: FileSystemInstallPlatformSkillMaterialization) =
    RuntimeComponentBindingsA2.installPlatformSkillMaterializationPort(adapter)

  @Provides @JvmSynthetic
  internal fun installStagingIntentPort(adapter: FileSystemInstallStagingIntent) =
    RuntimeComponentBindingsA2.installStagingIntentPort(adapter)

  @Provides @JvmSynthetic
  internal fun installApplyExecutionPort(adapter: FileSystemInstallApplyExecution) =
    RuntimeComponentBindingsA2.installApplyExecutionPort(adapter)

  @Provides @JvmSynthetic
  internal fun installReconcilePort(adapter: FileSystemInstallReconcile) =
    RuntimeComponentBindingsA3.installReconcilePort(adapter)

  @Provides @JvmSynthetic
  internal fun installReconcileApplyPort(adapter: FileSystemInstallReconcileApply) =
    RuntimeComponentBindingsA3.installReconcileApplyPort(adapter)

  @Provides @JvmSynthetic
  internal fun baselineManifestPersistencePort(adapter: FileSystemBaselineManifestPersistence) =
    RuntimeComponentBindingsA3.baselineManifestPersistencePort(adapter)

  @Provides @JvmSynthetic
  internal fun installedWorkspaceBaselineStatusPort(adapter: FileSystemInstalledWorkspaceBaselineStatus) =
    RuntimeComponentBindingsA3.installedWorkspaceBaselineStatusPort(adapter)

  @Provides @JvmSynthetic
  internal fun installSkillLinkPort(adapter: FileSystemInstallSkillLink) =
    RuntimeComponentBindingsA3.installSkillLinkPort(adapter)

  @Provides @JvmSynthetic
  internal fun installAgentTargetPort(adapter: FileSystemInstallAgentTargets) =
    RuntimeComponentBindingsA3.installAgentTargetPort(adapter)

  @Provides @JvmSynthetic
  internal fun installNativeAgentLinkPort(adapter: FileSystemInstallNativeAgentLinks) =
    RuntimeComponentBindingsA3.installNativeAgentLinkPort(adapter)

  @Provides @JvmSynthetic
  internal fun installMcpRegistrationPort(adapter: FileSystemInstallMcpRegistration) =
    RuntimeComponentBindingsA3.installMcpRegistrationPort(adapter)

  @Provides @JvmSynthetic
  internal fun agentRunLauncher(callbacks: OptionalCallbacks, adapter: FileSystemAgentRunLauncher) =
    RuntimeComponentBindingsA4.agentRunLauncher(callbacks, adapter)

  @Provides @JvmSynthetic
  fun executableLookup(callbacks: OptionalCallbacks) = RuntimeComponentBindingsA4.executableLookup(callbacks)

  @Provides @JvmSynthetic
  internal fun goalRunnerSubtaskLauncher(adapter: AgentRunGoalRunnerSubtaskLauncher) =
    RuntimeComponentBindingsA4.goalRunnerSubtaskLauncher(adapter)

  @Provides @JvmSynthetic
  internal fun goalPlanningSweep(sweep: DefaultGoalPlanningSweep) = RuntimeComponentBindingsA4.goalPlanningSweep(sweep)

  @Provides @JvmSynthetic
  internal fun goalPlanningRefreshLiveness(adapter: ChildAwareGoalPlanningRefreshLiveness) =
    RuntimeComponentBindingsA4.goalPlanningRefreshLiveness(adapter)

  @Provides @JvmSynthetic
  internal fun goalPlanningStatusReasonCoherence(adapter: LaunchAlignedGoalPlanningStatusReasonCoherence) =
    RuntimeComponentBindingsA4.goalPlanningStatusReasonCoherence(adapter)

  @Provides @JvmSynthetic
  internal fun goalRunnerExecutionCoordinator(coordinator: DefaultGoalRunnerExecutionCoordinator) =
    RuntimeComponentBindingsA4.goalRunnerExecutionCoordinator(coordinator)

  @Provides @JvmSynthetic
  internal fun goalPlanningAttemptRecorder(recorder: DurableGoalPlanningAttemptRecorder) =
    RuntimeComponentBindingsA4.goalPlanningAttemptRecorder(recorder)

  @Provides @JvmSynthetic
  internal fun goalPlanningRejectionRecorder(recorder: DurableGoalPlanningRejectionRecorder) =
    RuntimeComponentBindingsA5.goalPlanningRejectionRecorder(recorder)

  @Provides @JvmSynthetic
  internal fun goalPlanningContextDiscovery(adapter: FileSystemGoalPlanningContextDiscovery) =
    RuntimeComponentBindingsA5.goalPlanningContextDiscovery(adapter)

  @Provides @JvmSynthetic
  internal fun goalPlanningBoundaryBodyResolver(adapter: FileSystemGoalPlanningBoundaryBodyResolver) =
    RuntimeComponentBindingsA5.goalPlanningBoundaryBodyResolver(adapter)

  @Provides @JvmSynthetic
  internal fun goalLifecycleTelemetryEmitter(service: LifecycleTelemetryService) =
    RuntimeComponentBindingsA5.goalLifecycleTelemetryEmitter(service)

  @Provides @JvmSynthetic
  internal fun runtimeClock() = RuntimeComponentBindingsA5.runtimeClock()

  @Provides @JvmSynthetic
  internal fun runtimeTimingPort(callbacks: OptionalCallbacks, adapter: JdkRuntimeTimingPort) =
    RuntimeComponentBindingsA5.runtimeTimingPort(callbacks, adapter)

  @Provides @JvmSynthetic
  internal fun runtimeDiagnostics(adapter: JdkRuntimeDiagnostics) =
    RuntimeComponentBindingsA5.runtimeDiagnostics(adapter)

  @Provides @JvmSynthetic
  internal fun reviewNativeAgentPreflightPort(
    callbacks: OptionalCallbacks,
    adapter: FileSystemReviewNativeAgentPreflight,
  ) = RuntimeComponentBindingsA5.reviewNativeAgentPreflightPort(callbacks, adapter)

  @Provides @JvmSynthetic
  internal fun reviewLaunchAgentStagingPort(adapter: FileSystemReviewLaunchAgentStaging) =
    RuntimeComponentBindingsA6.reviewLaunchAgentStagingPort(adapter)

  @Provides @JvmSynthetic
  internal fun declaredReviewSpecialistsPort(adapter: FileSystemDeclaredReviewSpecialists) =
    RuntimeComponentBindingsA6.declaredReviewSpecialistsPort(adapter)

  @Provides @JvmSynthetic
  internal fun installedPlatformPackCatalogPort(adapter: FileSystemInstalledPlatformPackCatalog) =
    RuntimeComponentBindingsA6.installedPlatformPackCatalogPort(adapter)

  @Provides @JvmSynthetic
  internal fun goalRunnerManifestStore(adapter: WorkflowGoalRunnerManifestStore) =
    RuntimeComponentBindingsA6.goalRunnerManifestStore(adapter)

  @Provides @JvmSynthetic
  internal fun goalRunnerWorkflowOutcomeStore(adapter: WorkflowGoalRunnerOutcomeStore) =
    RuntimeComponentBindingsA6.goalRunnerWorkflowOutcomeStore(adapter)

  @Provides @JvmSynthetic
  internal fun goalRunnerAttemptLedgerStore(adapter: WorkflowGoalRunnerOutcomeStore) =
    RuntimeComponentBindingsA6.goalRunnerAttemptLedgerStore(adapter)

  @Provides @JvmSynthetic
  internal fun goalRunnerChildRepairStore(adapter: WorkflowGoalRunnerOutcomeStore) =
    RuntimeComponentBindingsA6.goalRunnerChildRepairStore(adapter)

  @Provides @JvmSynthetic
  internal fun goalPullRequestPort(callbacks: OptionalCallbacks, adapter: GhGoalPullRequestPort) =
    RuntimeComponentBindingsA6.goalPullRequestPort(callbacks, adapter)

  @Provides @JvmSynthetic
  internal fun installSelectionPersistencePort(adapter: FileSystemInstallSelectionPersistence) =
    RuntimeComponentBindingsA7.installSelectionPersistencePort(adapter)

  @Provides @JvmSynthetic
  internal fun repoLocalConfigPort(adapter: FileSystemRepoLocalConfig) =
    RuntimeComponentBindingsA7.repoLocalConfigPort(adapter)

  @Provides @JvmSynthetic
  internal fun scaffoldGateway(gateway: FileSystemScaffoldGateway) = RuntimeComponentBindingsA7.scaffoldGateway(gateway)

  @Provides @JvmSynthetic
  internal fun scaffoldSourceLoaderPort(adapter: FileSystemScaffoldSourceLoader) =
    RuntimeComponentBindingsB1.scaffoldSourceLoaderPort(adapter)

  @Provides @JvmSynthetic
  internal fun scaffoldManifestPersistencePort(adapter: FileSystemScaffoldManifestPersistence) =
    RuntimeComponentBindingsB1.scaffoldManifestPersistencePort(adapter)

  @Provides @JvmSynthetic
  internal fun scaffoldGeneratedStagingPort(adapter: FileSystemScaffoldGeneratedStaging) =
    RuntimeComponentBindingsB1.scaffoldGeneratedStagingPort(adapter)

  @Provides @JvmSynthetic
  internal fun scaffoldInstallLinkPort(adapter: FileSystemScaffoldInstallLink) =
    RuntimeComponentBindingsB1.scaffoldInstallLinkPort(adapter)

  @Provides @JvmSynthetic
  internal fun scaffoldRepoValidationPort(adapter: FileSystemScaffoldRepoValidation) =
    RuntimeComponentBindingsB1.scaffoldRepoValidationPort(adapter)

  @Provides @JvmSynthetic
  internal fun unsupportedScaffoldGateway(gateway: FileSystemUnsupportedScaffoldGateway) =
    RuntimeComponentBindingsB1.unsupportedScaffoldGateway(gateway)

  @Provides @JvmSynthetic
  internal fun scaffoldCatalogGateway(gateway: FileSystemScaffoldCatalogGateway) =
    RuntimeComponentBindingsB1.scaffoldCatalogGateway(gateway)

  @Provides @JvmSynthetic
  internal fun diffResolverPort(adapter: FileSystemDiffResolver) = RuntimeComponentBindingsB1.diffResolverPort(adapter)

  @Provides @JvmSynthetic
  internal fun sharedEvidenceResolverPort(adapter: FileSystemFeatureTaskRuntimeSharedEvidenceStore) =
    RuntimeComponentBindingsB2.sharedEvidenceResolverPort(adapter)

  @Provides @JvmSynthetic
  internal fun sharedEvidenceLocatorReadPort(adapter: FileSystemFeatureTaskRuntimeSharedEvidenceStore) =
    RuntimeComponentBindingsB2.sharedEvidenceLocatorReadPort(adapter)

  @Provides @JvmSynthetic
  internal fun repoSourceDiscoveryGateway(gateway: FileSystemRepoSourceDiscoveryGateway) =
    RuntimeComponentBindingsB2.repoSourceDiscoveryGateway(gateway)

  @Provides @JvmSynthetic
  internal fun repoValidationGateway(gateway: FileSystemRepoValidationGateway) =
    RuntimeComponentBindingsB2.repoValidationGateway(gateway)

  @Provides @JvmSynthetic
  internal fun validationGateRunner(runner: FileSystemValidationGateRunner) =
    RuntimeComponentBindingsB2.validationGateRunner(runner)

  @Provides @JvmSynthetic
  internal fun uninstallFileSystemGateway(gateway: FileSystemUninstallFileSystemGateway) =
    RuntimeComponentBindingsB2.uninstallFileSystemGateway(gateway)

  @Provides @JvmSynthetic
  internal fun reviewSnapshotGateway(gateway: FileSystemReviewSnapshotGateway) =
    RuntimeComponentBindingsB2.reviewSnapshotGateway(gateway)

  @Provides @JvmSynthetic
  internal fun reviewInputSource(source: FileSystemReviewInputSource) =
    RuntimeComponentBindingsB2.reviewInputSource(source)

  @Provides @JvmSynthetic
  internal fun reviewAttributionPort(adapter: FileSystemReviewAttribution) =
    RuntimeComponentBindingsB3.reviewAttributionPort(adapter)

  @Provides @JvmSynthetic
  internal fun reviewRubricResolver(adapter: FileSystemReviewRubricResolver) =
    RuntimeComponentBindingsB3.reviewRubricResolver(adapter)

  @Provides @JvmSynthetic
  internal fun reviewSpecialistContractProvider(adapter: ClasspathReviewSpecialistContractProvider) =
    RuntimeComponentBindingsB3.reviewSpecialistContractProvider(adapter)

  @Provides @JvmSynthetic
  internal fun featureTaskRuntimeRunInvariantsSource(adapter: FileSystemFeatureTaskRuntimeRunInvariantsSource) =
    RuntimeComponentBindingsB3.featureTaskRuntimeRunInvariantsSource(adapter)

  @Provides @JvmSynthetic
  fun agentAddonSelectionPort() = RuntimeComponentBindingsB3.agentAddonSelectionPort()

  @Provides @JvmSynthetic
  internal fun externalAgentAddonSourceConfigPort(store: FileExternalAgentAddonSourceConfigStore) =
    RuntimeComponentBindingsB3.externalAgentAddonSourceConfigPort(store)

  @Provides @JvmSynthetic
  internal fun featureTaskRuntimeWorkerSupervisor(adapter: JdkFeatureTaskRuntimeWorkerSupervisor) =
    RuntimeComponentBindingsB3.featureTaskRuntimeWorkerSupervisor(adapter)

  @Provides @JvmSynthetic
  internal fun featureTaskRuntimeSpecStatusWriter(adapter: FileSystemFeatureTaskRuntimeSpecStatusWriter) =
    RuntimeComponentBindingsB3.featureTaskRuntimeSpecStatusWriter(adapter)

  @Provides @JvmSynthetic
  internal fun skillRemoveFileSystem(fileSystem: FileSystemSkillRemoveFileSystem) =
    RuntimeComponentBindingsB4.skillRemoveFileSystem(fileSystem)

  @Provides @JvmSynthetic
  internal fun workflowGitOperations(workflowOps: WorkflowOpsContext, git: GitWorkflowGitOperations) =
    RuntimeComponentBindingsB4.workflowGitOperations(workflowOps, git)

  @Provides @JvmSynthetic
  internal fun decompositionManifestFileStore(store: FileSystemDecompositionManifestFileStore) =
    RuntimeComponentBindingsB4.decompositionManifestFileStore(store)

  @Provides @JvmSynthetic
  internal fun specScratchStore(store: FileSystemSpecScratchStore) = RuntimeComponentBindingsB4.specScratchStore(store)

  @Provides @JvmSynthetic
  internal fun installPlanWireValidator(adapter: InstallPlanWireValidatorAdapter) =
    RuntimeComponentBindingsB4.installPlanWireValidator(adapter)

  @Provides @JvmSynthetic
  internal fun decompositionManifestValidator(adapter: DecompositionManifestValidatorAdapter) =
    RuntimeComponentBindingsB4.decompositionManifestValidator(adapter)

  @Provides @JvmSynthetic
  internal fun workflowSnapshotValidator(adapter: WorkflowSnapshotValidatorInfraAdapter) =
    RuntimeComponentBindingsB4.workflowSnapshotValidator(adapter)

  @Provides @JvmSynthetic
  internal fun featureTaskRuntimePhaseOutputValidator(adapter: FeatureTaskRuntimePhaseOutputValidatorAdapter) =
    RuntimeComponentBindingsB4.featureTaskRuntimePhaseOutputValidator(adapter)

  @Provides @JvmSynthetic
  internal fun featureTaskRuntimePlanningProjectionValidator(
    adapter: FeatureTaskRuntimePlanningProjectionValidatorAdapter,
  ) = RuntimeComponentBindingsB5.featureTaskRuntimePlanningProjectionValidator(adapter)

  @Provides @JvmSynthetic
  internal fun featureTaskRuntimeBuildReceiptValidator(adapter: FeatureTaskRuntimeBuildReceiptValidatorAdapter) =
    RuntimeComponentBindingsB5.featureTaskRuntimeBuildReceiptValidator(adapter)

  @Provides @JvmSynthetic
  internal fun featureTaskRuntimeHandoffEnvelopeValidator(
    adapter: FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter,
  ) = RuntimeComponentBindingsB5.featureTaskRuntimeHandoffEnvelopeValidator(adapter)

  @Provides @JvmSynthetic
  internal fun featureTaskRuntimeHandoffFoundationValidator(
    adapter: FeatureTaskRuntimeHandoffFoundationValidatorInfraAdapter,
  ) = RuntimeComponentBindingsB5.featureTaskRuntimeHandoffFoundationValidator(adapter)

  @Provides @JvmSynthetic
  internal fun featureTaskRuntimeQuarantineValidator(adapter: FeatureTaskRuntimeQuarantineValidatorAdapter) =
    RuntimeComponentBindingsB5.featureTaskRuntimeQuarantineValidator(adapter)

  @Provides @JvmSynthetic
  internal fun featureTaskRuntimeImplementationAttemptValidator(
    adapter: FeatureTaskRuntimeImplementationAttemptValidatorAdapter,
  ) = RuntimeComponentBindingsB5.featureTaskRuntimeImplementationAttemptValidator(adapter)

  @Provides @JvmSynthetic
  fun rejectedOutputDiagnosticMetadataValidator() =
    RuntimeComponentBindingsB5.rejectedOutputDiagnosticMetadataValidator()

  @Provides @JvmSynthetic
  fun producerOutputEvidenceValidator() = RuntimeComponentBindingsB5.producerOutputEvidenceValidator()

  @Provides @JvmSynthetic
  internal fun goalPlanningPreparationEnvelopeValidator(adapter: GoalPlanningPreparationEnvelopeValidatorAdapter) =
    RuntimeComponentBindingsB6.goalPlanningPreparationEnvelopeValidator(adapter)

  @Provides @JvmSynthetic
  internal fun reviewContextEnvelopeValidator(adapter: ReviewContextEnvelopeValidatorAdapter) =
    RuntimeComponentBindingsB6.reviewContextEnvelopeValidator(adapter)

  @Provides @JvmSynthetic
  internal fun reviewEvidenceBrokerFactory(adapter: FileSystemReviewEvidenceBrokerFactory) =
    RuntimeComponentBindingsB6.reviewEvidenceBrokerFactory(adapter)

  @Provides @JvmSynthetic
  internal fun governedReviewEvidenceEndpointBinder(adapter: UnixSocketGovernedReviewEvidenceEndpointBinder) =
    RuntimeComponentBindingsB6.governedReviewEvidenceEndpointBinder(adapter)

  @Provides @JvmSynthetic
  internal fun reviewLaunchIsolationResolver(adapter: AgentRunReviewIsolationResolver) =
    RuntimeComponentBindingsB6.reviewLaunchIsolationResolver(adapter)

  @Provides @JvmSynthetic
  internal fun featureSpecPathResolverPort(adapter: FileSystemFeatureSpecPathResolver) =
    RuntimeComponentBindingsB6.featureSpecPathResolverPort(adapter)

  @Provides @JvmSynthetic
  internal fun goalObservabilityEventValidator(adapter: GoalObservabilityEventValidatorAdapter) =
    RuntimeComponentBindingsB6.goalObservabilityEventValidator(adapter)

  @Provides @JvmSynthetic
  internal fun goalProgressEventValidator(adapter: GoalProgressEventValidatorAdapter) =
    RuntimeComponentBindingsB6.goalProgressEventValidator(adapter)

  @Provides @JvmSynthetic
  internal fun ideStatusValidator(adapter: IdeStatusValidatorAdapter) =
    RuntimeComponentBindingsB7.ideStatusValidator(adapter)

  @Provides @JvmSynthetic
  internal fun checkedOutBranchSource(source: FileSystemCheckedOutBranchSource) =
    RuntimeComponentBindingsB7.checkedOutBranchSource(source)

  @Provides @JvmSynthetic
  internal fun featureTaskRuntimeReviewDriver(runner: ParallelCodeReviewRunner) =
    RuntimeComponentBindingsB7.featureTaskRuntimeReviewDriver(runner)

  @Provides @JvmSynthetic
  fun featureTaskPhaseSettlementRepository() =
    RuntimeComponentBindingsB7.featureTaskPhaseSettlementRepository()
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
