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


internal object RuntimeComponentBindingsA {
  fun runtimeContext(inputRuntimeContext: RuntimeContext): RuntimeContext {
    val inputEnvironment = inputRuntimeContext.environment
    val resolvedEnvironment =
      if (inputEnvironment.userHome == EnvironmentContext.UnspecifiedUserHome) {
        inputEnvironment.copy(userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())
      } else {
        inputEnvironment
      }
    val environmentWithEnv =
      if (resolvedEnvironment.environment === EnvironmentContext.UnspecifiedEnvironment) {
        resolvedEnvironment.copy(environment = System.getenv())
      } else {
        resolvedEnvironment
      }
    val inputTransport = inputRuntimeContext.transport
    val resolvedTransport =
      if (inputTransport.requester === UnconfiguredHttpRequester) {
        inputTransport.copy(requester = JdkHttpRequester)
      } else {
        inputTransport
      }
    return inputRuntimeContext.copy(environment = environmentWithEnv, transport = resolvedTransport)
  }

  fun environmentContext(ctx: RuntimeContext): EnvironmentContext = ctx.environment

  fun transportContext(ctx: RuntimeContext): TransportContext = ctx.transport

  fun workflowOpsContext(ctx: RuntimeContext): WorkflowOpsContext = ctx.workflowOps

  fun optionalCallbacks(ctx: RuntimeContext): OptionalCallbacks = ctx.callbacks

  fun databaseSessionFactory(context: EnvironmentContext): DatabaseSessionFactory =
    SQLiteDatabaseSessionFactory(context)

  internal fun telemetryConfigStore(store: FileTelemetryConfigStore): TelemetryConfigStore = store

  internal fun externalAddonSourceConfigPort(
    store: FileExternalAddonSourceConfigStore,
  ): ExternalAddonSourceConfigPort = store

  internal fun externalAddonOverlayPort(adapter: FileSystemExternalAddonOverlay): ExternalAddonOverlayPort = adapter

  internal fun telemetrySettingsProvider(provider: DefaultTelemetrySettingsProvider): TelemetrySettingsProvider =
    provider

  internal fun telemetryClient(client: HttpTelemetryClient): TelemetryClient = client

  internal fun telemetryLevelMutator(service: TelemetryLevelMutationService): TelemetryLevelMutator = service

  internal fun installPlanningFactsPort(adapter: FileSystemInstallPlanningFacts): InstallPlanningFactsPort = adapter

  internal fun installPlatformSkillMaterializationPort(
    adapter: FileSystemInstallPlatformSkillMaterialization,
  ): InstallPlatformSkillMaterializationPort = adapter

  internal fun installStagingIntentPort(adapter: FileSystemInstallStagingIntent): InstallStagingIntentPort = adapter

  internal fun installApplyExecutionPort(adapter: FileSystemInstallApplyExecution): InstallApplyExecutionPort = adapter

  // SKILL-76 Subtask 2: reconcile-compute + baseline manifest persistence ports,
  // bound to their infra-fs adapters exactly like every other install adapter.
  internal fun installReconcilePort(adapter: FileSystemInstallReconcile): InstallReconcilePort = adapter

  internal fun installReconcileApplyPort(adapter: FileSystemInstallReconcileApply): InstallReconcileApplyPort = adapter

  internal fun baselineManifestPersistencePort(
    adapter: FileSystemBaselineManifestPersistence,
  ): BaselineManifestPersistencePort = adapter

  // SKILL-77 Subtask 4: read-only installed-workspace modified-vs-baseline status,
  // consumed by the desktop tree to flag locally edited skills.
  internal fun installedWorkspaceBaselineStatusPort(
    adapter: FileSystemInstalledWorkspaceBaselineStatus,
  ): InstalledWorkspaceBaselineStatusPort = adapter

  internal fun installSkillLinkPort(adapter: FileSystemInstallSkillLink): InstallSkillLinkPort = adapter

  internal fun installAgentTargetPort(adapter: FileSystemInstallAgentTargets): InstallAgentTargetPort = adapter

  internal fun installNativeAgentLinkPort(adapter: FileSystemInstallNativeAgentLinks): InstallNativeAgentLinkPort =
    adapter

  internal fun installMcpRegistrationPort(adapter: FileSystemInstallMcpRegistration): InstallMcpRegistrationPort =
    adapter

  internal fun agentRunLauncher(callbacks: OptionalCallbacks, adapter: FileSystemAgentRunLauncher): AgentRunLauncher =
    callbacks.agentRunLauncher ?: adapter

  fun executableLookup(callbacks: OptionalCallbacks): ExecutableLookup =
    callbacks.executableLookup ?: PathExecutableLookup()

  internal fun goalRunnerSubtaskLauncher(adapter: AgentRunGoalRunnerSubtaskLauncher): GoalRunnerSubtaskLauncher =
    adapter

  internal fun goalPlanningSweep(sweep: DefaultGoalPlanningSweep): GoalPlanningSweep = sweep

  internal fun goalPlanningRefreshLiveness(
    adapter: ChildAwareGoalPlanningRefreshLiveness,
  ): GoalPlanningRefreshLiveness = adapter

  internal fun goalPlanningStatusReasonCoherence(
    adapter: LaunchAlignedGoalPlanningStatusReasonCoherence,
  ): GoalPlanningStatusReasonCoherence = adapter

  internal fun goalRunnerExecutionCoordinator(
    coordinator: DefaultGoalRunnerExecutionCoordinator,
  ): GoalRunnerExecutionCoordinator = coordinator

  internal fun goalPlanningAttemptRecorder(recorder: DurableGoalPlanningAttemptRecorder): GoalPlanningAttemptRecorder =
    recorder

  internal fun goalPlanningRejectionRecorder(
    recorder: DurableGoalPlanningRejectionRecorder,
  ): GoalPlanningRejectionRecorder = recorder

  internal fun goalPlanningContextDiscovery(
    adapter: FileSystemGoalPlanningContextDiscovery,
  ): GoalPlanningContextDiscovery = adapter

  internal fun goalPlanningBoundaryBodyResolver(
    adapter: FileSystemGoalPlanningBoundaryBodyResolver,
  ): GoalPlanningBoundaryBodyResolver {
    // SKILL-174: the exclusion contract is read through a lazy classpath singleton rather than an
    // injected port. Forcing it here turns "the contract is missing from a packaged artifact" into a
    // typed wiring failure instead of a durable planning block discovered halfway through a goal.
    GoalPlanningDiscoveryExclusions.excludedRoots
    return adapter
  }

  // SKILL-66 Subtask 3: GoalRunner reaches lifecycle-telemetry emission only
  // through the application-owned GoalLifecycleTelemetryEmitter seam (backed by
  // LifecycleTelemetryService) and times every payload off this injected clock.
  internal fun goalLifecycleTelemetryEmitter(service: LifecycleTelemetryService): GoalLifecycleTelemetryEmitter =
    service

  internal fun runtimeClock(): Clock = Clock.systemUTC()

  internal fun runtimeTimingPort(callbacks: OptionalCallbacks, adapter: JdkRuntimeTimingPort): RuntimeTimingPort =
    callbacks.runtimeTimingPort ?: adapter

  internal fun runtimeDiagnostics(adapter: JdkRuntimeDiagnostics): RuntimeDiagnostics = adapter

  internal fun reviewNativeAgentPreflightPort(
    callbacks: OptionalCallbacks,
    adapter: FileSystemReviewNativeAgentPreflight,
  ): ReviewNativeAgentPreflightPort = callbacks.reviewNativeAgentPreflight ?: adapter

  internal fun reviewLaunchAgentStagingPort(
    adapter: FileSystemReviewLaunchAgentStaging,
  ): ReviewLaunchAgentStagingPort = adapter

  internal fun declaredReviewSpecialistsPort(
    adapter: FileSystemDeclaredReviewSpecialists,
  ): DeclaredReviewSpecialistsPort = adapter

  internal fun installedPlatformPackCatalogPort(
    adapter: FileSystemInstalledPlatformPackCatalog,
  ): InstalledPlatformPackCatalogPort = adapter

  internal fun goalRunnerManifestStore(adapter: WorkflowGoalRunnerManifestStore): GoalRunnerManifestStore = adapter

  internal fun goalRunnerWorkflowOutcomeStore(
    adapter: WorkflowGoalRunnerOutcomeStore,
  ): GoalRunnerWorkflowOutcomeStore = adapter

  internal fun goalRunnerAttemptLedgerStore(adapter: WorkflowGoalRunnerOutcomeStore): GoalRunnerAttemptLedgerStore =
    adapter

  internal fun goalRunnerChildRepairStore(adapter: WorkflowGoalRunnerOutcomeStore): GoalRunnerChildRepairStore = adapter

  internal fun goalPullRequestPort(callbacks: OptionalCallbacks, adapter: GhGoalPullRequestPort): GoalPullRequestPort =
    callbacks.goalPullRequestPort ?: adapter

  internal fun installSelectionPersistencePort(
    adapter: FileSystemInstallSelectionPersistence,
  ): InstallSelectionPersistencePort = adapter

  internal fun repoLocalConfigPort(adapter: FileSystemRepoLocalConfig): RepoLocalConfigPort = adapter

  internal fun scaffoldGateway(gateway: FileSystemScaffoldGateway): ScaffoldGateway = gateway

  // SKILL-52.1 subtask 2: typed capability ports for the scaffold pipeline. These are wired
  // alongside the legacy `ScaffoldGateway` raw-map adapter so subtask 3 can migrate the
  // application-layer scaffold service over without further DI churn. The legacy
  // `ScaffoldGateway` binding above intentionally stays.
  }
