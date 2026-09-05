package skillbill.di

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import skillbill.application.agentrun.AgentRunService
import skillbill.application.config.ConfigResolutionService
import skillbill.application.featuretask.FeatureTaskContinuationLookupService
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeRunner
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.featuretask.FeatureTaskRuntimeWorkerCoordinator
import skillbill.application.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.application.goalrunner.GoalOperatorDecisionService
import skillbill.application.goalrunner.GoalPreflightService
import skillbill.application.goalrunner.GoalRunner
import skillbill.application.goalrunner.GoalRunnerStatusService
import skillbill.application.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.application.goalrunner.planning.GoalPlanningLogService
import skillbill.application.install.ExternalAddonOverlayService
import skillbill.application.install.InstallService
import skillbill.application.learning.LearningService
import skillbill.application.review.ParallelCodeReviewRunner
import skillbill.application.review.ReviewService
import skillbill.application.review.ReviewSnapshotPruneService
import skillbill.application.runtime.RuntimeSingleton
import skillbill.application.scaffold.InstallAgentService
import skillbill.application.scaffold.SkillRemoveService
import skillbill.application.system.SystemService
import skillbill.application.system.UninstallFileSystemService
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.application.telemetry.TelemetryService
import skillbill.application.work.IdeStatusService
import skillbill.application.work.WorkListService
import skillbill.application.workflow.WorkflowService
import skillbill.model.EnvironmentContext
import skillbill.model.OptionalCallbacks
import skillbill.model.RuntimeContext
import skillbill.model.TransportContext
import skillbill.model.WorkflowOpsContext
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.install.baseline.InstalledWorkspaceBaselineStatusPort
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.scaffold.UnsupportedScaffoldGateway
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetryLevelMutator
import skillbill.ports.validation.RepoValidationGateway

@RuntimeSingleton
@Component
abstract class RuntimeComponent(
  private val inputRuntimeContext: RuntimeContext,
) :
  RuntimeTelemetryInstallProvides,
  RuntimeInstallLauncherProvides,
  RuntimeGoalRunnerPlanningProvides,
  RuntimeDiagnosticsReviewProvides,
  RuntimeGoalRunnerScaffoldProvides,
  RuntimeScaffoldWorkflowProvides,
  RuntimeReviewWorkflowProvides,
  RuntimePortableReviewBaselineProvides,
  RuntimeWorkflowValidatorProvides,
  RuntimeFeatureTaskGoalValidatorProvides,
  RuntimeCompositionMiscProvides,
  RuntimeGoalRunnerWorkflowProvides,
  RuntimeGoalRunnerBoundaryProvides,
  RuntimeReviewFeatureTaskGateProvides {
  @Provides @JvmSynthetic
  fun runtimeContext(): RuntimeContext = RuntimeBootstrapBindings.runtimeContext(inputRuntimeContext)

  @Provides @JvmSynthetic
  fun environmentContext(ctx: RuntimeContext): EnvironmentContext = RuntimeBootstrapBindings.environmentContext(ctx)

  @Provides @JvmSynthetic
  fun transportContext(ctx: RuntimeContext): TransportContext = RuntimeBootstrapBindings.transportContext(ctx)

  @Provides @JvmSynthetic
  fun workflowOpsContext(ctx: RuntimeContext): WorkflowOpsContext = RuntimeBootstrapBindings.workflowOpsContext(ctx)

  @Provides @JvmSynthetic
  fun optionalCallbacks(ctx: RuntimeContext): OptionalCallbacks = RuntimeBootstrapBindings.optionalCallbacks(ctx)

  @Provides @RuntimeSingleton @JvmSynthetic
  fun databaseSessionFactory(context: EnvironmentContext): DatabaseSessionFactory =
    RuntimeBootstrapBindings.databaseSessionFactory(context)

  abstract val resolvedEnvironmentContext: EnvironmentContext

  abstract val repositoryEnclosingRootPort: RepositoryEnclosingRootPort

  abstract val featureTaskContinuationLookupService: FeatureTaskContinuationLookupService
  abstract val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService

  abstract val parallelCodeReviewRunner: ParallelCodeReviewRunner
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
  abstract val featureTaskRuntimeRunInvariantsSource: FeatureTaskRuntimeRunInvariantsSource
  abstract val featureSpecPathResolverPort: FeatureSpecPathResolverPort
  abstract val goalRunner: GoalRunner
  abstract val goalPreflightService: GoalPreflightService
  abstract val goalRunnerStatusService: GoalRunnerStatusService
  abstract val goalPlanningLogService: GoalPlanningLogService
  abstract val goalOperatorDecisionService: GoalOperatorDecisionService
  abstract val installAgentService: InstallAgentService
  abstract val installMcpRegistrationPort: InstallMcpRegistrationPort
  abstract val installNativeAgentLinkPort: InstallNativeAgentLinkPort
  abstract val installSelectionPersistencePort: InstallSelectionPersistencePort
  abstract val installedWorkspaceBaselineStatusPort: InstalledWorkspaceBaselineStatusPort
  abstract val learningService: LearningService
  abstract val lifecycleTelemetryService: LifecycleTelemetryService
  abstract val repoValidationGateway: RepoValidationGateway
  abstract val reviewService: ReviewService
  abstract val reviewSnapshotPruneService: ReviewSnapshotPruneService
  abstract val runtimeDiagnostics: RuntimeDiagnostics
  abstract val scaffoldCatalogGateway: ScaffoldCatalogGateway
  abstract val scaffoldGateway: ScaffoldGateway
  abstract val skillRemoveService: SkillRemoveService
  abstract val systemService: SystemService
  abstract val telemetryConfigStorePort: TelemetryConfigStore
  abstract val telemetryLevelMutator: TelemetryLevelMutator
  abstract val telemetryService: TelemetryService
  abstract val uninstallFileSystemService: UninstallFileSystemService
  abstract val unsupportedScaffoldGateway: UnsupportedScaffoldGateway
  abstract val workflowService: WorkflowService
  abstract val workListService: WorkListService
  abstract val ideStatusService: IdeStatusService
}
