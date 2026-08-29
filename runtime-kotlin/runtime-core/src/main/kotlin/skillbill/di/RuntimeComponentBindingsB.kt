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
import skillbill.infrastructure.sqlite.SqliteFeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository


internal object RuntimeComponentBindingsB {
internal fun scaffoldSourceLoaderPort(adapter: FileSystemScaffoldSourceLoader): ScaffoldSourceLoaderPort = adapter

  internal fun scaffoldManifestPersistencePort(
    adapter: FileSystemScaffoldManifestPersistence,
  ): ScaffoldManifestPersistencePort = adapter

  internal fun scaffoldGeneratedStagingPort(
    adapter: FileSystemScaffoldGeneratedStaging,
  ): ScaffoldGeneratedStagingPort = adapter

  internal fun scaffoldInstallLinkPort(adapter: FileSystemScaffoldInstallLink): ScaffoldInstallLinkPort = adapter

  internal fun scaffoldRepoValidationPort(adapter: FileSystemScaffoldRepoValidation): ScaffoldRepoValidationPort =
    adapter

  internal fun unsupportedScaffoldGateway(gateway: FileSystemUnsupportedScaffoldGateway): UnsupportedScaffoldGateway =
    gateway

  internal fun scaffoldCatalogGateway(gateway: FileSystemScaffoldCatalogGateway): ScaffoldCatalogGateway = gateway

  internal fun diffResolverPort(adapter: FileSystemDiffResolver): DiffResolverPort = adapter

  internal fun sharedEvidenceResolverPort(
    adapter: FileSystemFeatureTaskRuntimeSharedEvidenceStore,
  ): FeatureTaskRuntimeSharedEvidenceResolverPort = adapter

  internal fun sharedEvidenceLocatorReadPort(
    adapter: FileSystemFeatureTaskRuntimeSharedEvidenceStore,
  ): FeatureTaskRuntimeSharedEvidenceLocatorReadPort = adapter

  internal fun repoSourceDiscoveryGateway(gateway: FileSystemRepoSourceDiscoveryGateway): RepoSourceDiscoveryGateway =
    gateway

  internal fun repoValidationGateway(gateway: FileSystemRepoValidationGateway): RepoValidationGateway = gateway

  internal fun validationGateRunner(runner: FileSystemValidationGateRunner): ValidationGateRunner = runner

  internal fun uninstallFileSystemGateway(gateway: FileSystemUninstallFileSystemGateway): UninstallFileSystemGateway =
    gateway

  internal fun reviewSnapshotGateway(gateway: FileSystemReviewSnapshotGateway): ReviewSnapshotGateway = gateway

  internal fun reviewInputSource(source: FileSystemReviewInputSource): ReviewInputSource = source

  internal fun reviewAttributionPort(adapter: FileSystemReviewAttribution): ReviewAttributionPort = adapter

  internal fun reviewRubricResolver(adapter: FileSystemReviewRubricResolver): ReviewRubricResolver = adapter

  internal fun reviewSpecialistContractProvider(
    adapter: ClasspathReviewSpecialistContractProvider,
  ): ReviewSpecialistContractProvider = adapter

  internal fun featureTaskRuntimeRunInvariantsSource(
    adapter: FileSystemFeatureTaskRuntimeRunInvariantsSource,
  ): FeatureTaskRuntimeRunInvariantsSource = adapter

  fun agentAddonSelectionPort(): AgentAddonSelectionPort = AgentAddonSelectionResolver()

  internal fun externalAgentAddonSourceConfigPort(
    store: FileExternalAgentAddonSourceConfigStore,
  ): ExternalAgentAddonSourceConfigPort = store

  internal fun featureTaskRuntimeWorkerSupervisor(
    adapter: JdkFeatureTaskRuntimeWorkerSupervisor,
  ): FeatureTaskRuntimeWorkerSupervisor = adapter

  internal fun featureTaskRuntimeSpecStatusWriter(
    adapter: FileSystemFeatureTaskRuntimeSpecStatusWriter,
  ): FeatureTaskRuntimeSpecStatusWriter = adapter

  internal fun skillRemoveFileSystem(fileSystem: FileSystemSkillRemoveFileSystem): SkillRemoveFileSystem = fileSystem

  internal fun workflowGitOperations(
    workflowOps: WorkflowOpsContext,
    git: GitWorkflowGitOperations,
  ): WorkflowGitOperations =
    if (workflowOps.workflowGitOperations === NoopWorkflowGitOperations) git else workflowOps.workflowGitOperations

  internal fun decompositionManifestFileStore(
    store: FileSystemDecompositionManifestFileStore,
  ): DecompositionManifestFileStore = store

  internal fun specScratchStore(store: FileSystemSpecScratchStore): SpecScratchStore = store

  // SKILL-52.3 Subtask 1: validator ports now bind to infra-fs adapters
  // (the module that owns the concrete networknt + Jackson schema
  // validators). `runtime-domain` install policy and the application
  // decomposition + workflow seams reach the validators only through
  // these ports, wired exactly like every other infra adapter above.
  internal fun installPlanWireValidator(adapter: InstallPlanWireValidatorAdapter): InstallPlanWireValidator = adapter

  internal fun decompositionManifestValidator(
    adapter: DecompositionManifestValidatorAdapter,
  ): DecompositionManifestValidator = adapter

  internal fun workflowSnapshotValidator(adapter: WorkflowSnapshotValidatorInfraAdapter): WorkflowSnapshotValidator =
    adapter

  internal fun featureTaskRuntimePhaseOutputValidator(
    adapter: FeatureTaskRuntimePhaseOutputValidatorAdapter,
  ): FeatureTaskRuntimePhaseOutputValidator = adapter

  // SKILL-137: the canonical planning-projections schema gate. The domain parse seam calls this port
  // before building a typed projection, so the schema is enforced at runtime, not just authored.
  internal fun featureTaskRuntimePlanningProjectionValidator(
    adapter: FeatureTaskRuntimePlanningProjectionValidatorAdapter,
  ): FeatureTaskRuntimePlanningProjectionValidator = adapter

  internal fun featureTaskRuntimeBuildReceiptValidator(
    adapter: FeatureTaskRuntimeBuildReceiptValidatorAdapter,
  ): FeatureTaskRuntimeBuildReceiptValidator = adapter

  internal fun featureTaskRuntimeHandoffEnvelopeValidator(
    adapter: FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter,
  ): FeatureTaskRuntimeHandoffEnvelopeValidator = adapter

  internal fun featureTaskRuntimeHandoffFoundationValidator(
    adapter: FeatureTaskRuntimeHandoffFoundationValidatorInfraAdapter,
  ): FeatureTaskRuntimeHandoffFoundationValidator = adapter

  // SKILL-140: the canonical quarantine schema gate. The recorder's append and read seams call this
  // port so a malformed private-evidence store fails loudly rather than round-tripping silently.
  internal fun featureTaskRuntimeQuarantineValidator(
    adapter: FeatureTaskRuntimeQuarantineValidatorAdapter,
  ): FeatureTaskRuntimeQuarantineValidator = adapter

  // SKILL-150: the canonical implementation-attempt schema gate. The recorder validates every
  // appended attempt through this port inside the advancing transaction, so a malformed receipt
  // never reaches the durable store the continuation projection is reconstructed from.
  internal fun featureTaskRuntimeImplementationAttemptValidator(
    adapter: FeatureTaskRuntimeImplementationAttemptValidatorAdapter,
  ): FeatureTaskRuntimeImplementationAttemptValidator = adapter

  fun rejectedOutputDiagnosticMetadataValidator(): RejectedOutputDiagnosticMetadataValidator =
    RejectedOutputDiagnosticMetadataValidatorAdapter()

  fun producerOutputEvidenceValidator(): ProducerOutputEvidenceValidator = ProducerOutputEvidenceValidatorAdapter()

  internal fun goalPlanningPreparationEnvelopeValidator(
    adapter: GoalPlanningPreparationEnvelopeValidatorAdapter,
  ): GoalPlanningPreparationEnvelopeValidator = adapter

  internal fun reviewContextEnvelopeValidator(
    adapter: ReviewContextEnvelopeValidatorAdapter,
  ): ReviewContextEnvelopeValidator = adapter

  internal fun reviewEvidenceBrokerFactory(
    adapter: FileSystemReviewEvidenceBrokerFactory,
  ): ReviewEvidenceBrokerFactory = adapter

  internal fun governedReviewEvidenceEndpointBinder(
    adapter: UnixSocketGovernedReviewEvidenceEndpointBinder,
  ): GovernedReviewEvidenceEndpointBinder = adapter

  internal fun reviewLaunchIsolationResolver(adapter: AgentRunReviewIsolationResolver): ReviewLaunchIsolationResolver =
    adapter

  internal fun featureSpecPathResolverPort(adapter: FileSystemFeatureSpecPathResolver): FeatureSpecPathResolverPort =
    adapter

  internal fun goalObservabilityEventValidator(
    adapter: GoalObservabilityEventValidatorAdapter,
  ): GoalObservabilityEventValidator = adapter

  // SKILL-64 Subtask 3: declared goal-progress event schema validator port,
  // bound to the infra-fs adapter that owns the networknt JSON-Schema check.
  // The goal-runner outcome store calls this port at the durable
  // declared-progress write seam, mirroring goalObservabilityEventValidator.
  internal fun goalProgressEventValidator(adapter: GoalProgressEventValidatorAdapter): GoalProgressEventValidator =
    adapter

  internal fun ideStatusValidator(adapter: IdeStatusValidatorAdapter): IdeStatusValidator = adapter

  internal fun checkedOutBranchSource(source: FileSystemCheckedOutBranchSource): CheckedOutBranchSource = source

  internal fun featureTaskRuntimeReviewDriver(runner: ParallelCodeReviewRunner): FeatureTaskRuntimeReviewDriver =
    FeatureTaskRuntimeReviewDriver(runner::run)

  fun featureTaskPhaseSettlementRepository(): FeatureTaskPhaseSettlementRepository =
    SqliteFeatureTaskPhaseSettlementRepository()
}
