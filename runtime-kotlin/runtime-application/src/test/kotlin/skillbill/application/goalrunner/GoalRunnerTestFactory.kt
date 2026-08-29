package skillbill.application.goalrunner

import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseRecorderDeps
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseRecorderValidators
import skillbill.application.goalrunner.model.GoalRunnerDeps
import skillbill.application.goalrunner.model.GoalRunnerStatusServiceDeps
import skillbill.application.goalrunner.model.WorkflowGoalRunnerManifestStoreDeps
import skillbill.application.goalrunner.model.WorkflowGoalRunnerOutcomeStoreDeps
import skillbill.application.goalrunner.planning.DefaultGoalPlanningSweep
import skillbill.application.goalrunner.planning.GoalPlanningStatusReasonCoherence
import skillbill.application.goalrunner.planning.GoalPlanningSweep
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepDeps
import skillbill.application.testDecompositionManifestValidator
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.NoopGoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.verification.model.GoalVerificationBoundaryDiscovery
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestFileStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.specscratch.UnavailableSpecScratchStore
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.goal.NoopGoalObservabilityEventValidator
import skillbill.workflow.goal.NoopGoalProgressEventValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeImplementationAttemptValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeQuarantineValidator
import java.nio.file.Path
import java.time.Clock

internal fun testGoalRunner(deps: GoalRunnerDeps): GoalRunner = GoalRunner(deps)

internal fun goalRunnerDeps(
  manifestStore: GoalRunnerManifestStore,
  subtaskLauncher: GoalRunnerSubtaskLauncher,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  pullRequestPort: GoalPullRequestPort,
): GoalRunnerDeps = GoalRunnerDeps(
  manifestStore = manifestStore,
  subtaskLauncher = subtaskLauncher,
  outcomeStore = outcomeStore,
  pullRequestPort = pullRequestPort,
  goalPlanningSweep = GoalPlanningSweep.NONE,
  specScratchStore = UnavailableSpecScratchStore,
  gitOperations = NoopWorkflowGitOperations,
  telemetry = GoalLifecycleTelemetryEmitter.NONE,
  clock = Clock.systemUTC(),
  diagnostics = NoopRuntimeDiagnostics,
  unaddressedFindingsLedgerService = null,
  executionCoordinator = GoalRunnerExecutionCoordinator.NONE,
)

internal fun testGoalRunnerStatusService(deps: GoalRunnerStatusServiceDeps): GoalRunnerStatusService =
  GoalRunnerStatusService(deps)

internal fun goalRunnerStatusServiceDeps(
  manifestStore: GoalRunnerManifestStore,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  phaseRecorder: FeatureTaskRuntimePhaseRecorder,
): GoalRunnerStatusServiceDeps = GoalRunnerStatusServiceDeps(
  manifestStore = manifestStore,
  outcomeStore = outcomeStore,
  phaseRecorder = phaseRecorder,
  gitOperations = NoopWorkflowGitOperations,
  attemptLedgerStore = NoopGoalRunnerAttemptLedgerStore,
  clock = Clock.systemUTC(),
  workerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  childRepairStore = NoopGoalRunnerChildRepairStore,
  planningStatusReasonCoherence = GoalPlanningStatusReasonCoherence.NONE,
  diagnostics = NoopRuntimeDiagnostics,
  runtimeStatusService = null,
)

internal fun testPhaseRecorder(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  handoffEnvelopeValidator: FeatureTaskRuntimeHandoffEnvelopeValidator,
  handoffFoundationValidator: FeatureTaskRuntimeHandoffFoundationValidator,
  diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
): FeatureTaskRuntimePhaseRecorder = FeatureTaskRuntimePhaseRecorder(
  FeatureTaskRuntimePhaseRecorderDeps(
    database = database,
    workflowSnapshotValidator = workflowSnapshotValidator,
    validators = FeatureTaskRuntimePhaseRecorderValidators(
      handoffEnvelopeValidator = handoffEnvelopeValidator,
      handoffFoundationValidator = handoffFoundationValidator,
      quarantineValidator = NoopFeatureTaskRuntimeQuarantineValidator,
      implementationAttemptValidator = NoopFeatureTaskRuntimeImplementationAttemptValidator,
    ),
    diagnostics = diagnostics,
  ),
)

internal fun testWorkflowGoalRunnerOutcomeStore(
  deps: WorkflowGoalRunnerOutcomeStoreDeps,
): WorkflowGoalRunnerOutcomeStore = WorkflowGoalRunnerOutcomeStore(deps)

internal fun outcomeStoreDeps(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
): WorkflowGoalRunnerOutcomeStoreDeps = WorkflowGoalRunnerOutcomeStoreDeps(
  database = database,
  workflowSnapshotValidator = workflowSnapshotValidator,
  goalObservabilityEventValidator = NoopGoalObservabilityEventValidator,
  goalProgressEventValidator = NoopGoalProgressEventValidator,
  gitOperations = NoopWorkflowGitOperations,
  phaseOutputValidator = ReviewRawOutputFallbackValidator,
  workerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  decompositionManifestValidator = testDecompositionManifestValidator,
  decompositionManifestFileStore = UnavailableDecompositionManifestFileStore,
)

internal fun testWorkflowGoalRunnerManifestStore(
  deps: WorkflowGoalRunnerManifestStoreDeps,
): WorkflowGoalRunnerManifestStore = WorkflowGoalRunnerManifestStore(deps)

internal fun testDefaultGoalPlanningSweep(deps: GoalPlanningSweepDeps): DefaultGoalPlanningSweep =
  DefaultGoalPlanningSweep(deps)

internal val testDefaultContextDiscovery = object : GoalPlanningContextDiscovery {
  override fun discover(repoRoot: Path): GoalPlanningContext = GoalPlanningContext(
    boundaryCatalog = emptyList(),
    boundaryCatalogTruncated = false,
    validationGuidance = "",
  )

  override fun discoverForFindingPaths(repoRoot: Path, findingPaths: List<String>, loudFailOnCapExceeded: Boolean) =
    GoalVerificationBoundaryDiscovery(
      boundaryCatalog = emptyList(),
      boundaryCatalogTruncated = false,
      boundaryContextUnavailable = findingPaths.isEmpty(),
    )
}
