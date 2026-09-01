package skillbill.application.goalrunner

import skillbill.application.InMemoryRuntimeWorkflowRepository
import skillbill.application.RuntimeFakeDatabaseSessionFactory
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffFoundationValidator
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
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.application.testDecompositionManifestValidator
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.NoopGoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.verification.model.GoalVerificationBoundaryDiscovery
import skillbill.ports.learning.LearningRepository
import skillbill.ports.review.ReviewRepository
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.telemetry.LifecycleTelemetryRepository
import skillbill.ports.telemetry.TelemetryOutboxRepository
import skillbill.ports.telemetry.TelemetryReconciliationRepository
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestFileStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.specscratch.UnavailableSpecScratchStore
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.goal.NoopGoalObservabilityEventValidator
import skillbill.workflow.goal.NoopGoalProgressEventValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeImplementationAttemptValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeQuarantineValidator
import java.nio.file.Path
import java.time.Clock

internal fun testActivityStampWriter(
  database: DatabaseSessionFactory = TestGoalActivityStampDatabase,
): AgentActivityStampWriter = AgentActivityStampWriter(database)

internal fun testGoalRunner(
  deps: GoalRunnerDeps,
  activityStampWriter: AgentActivityStampWriter = testActivityStampWriter(),
): GoalRunner = GoalRunner(deps, activityStampWriter)

private object TestGoalActivityStampDatabase : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/goal-activity-stamp.db")

  override fun resolveDbPath(dbOverride: String?): Path = dbPath

  override fun databaseExists(dbOverride: String?): Boolean = true

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T = transaction(dbOverride, block)

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = this@TestGoalActivityStampDatabase.dbPath
    override val reviews: ReviewRepository get() = error("unused by goal activity stamp wiring")
    override val learnings: LearningRepository get() = error("unused by goal activity stamp wiring")
    override val lifecycleTelemetry: LifecycleTelemetryRepository
      get() = error("unused by goal activity stamp wiring")
    override val telemetryReconciliation: TelemetryReconciliationRepository
      get() = error("unused by goal activity stamp wiring")
    override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused by goal activity stamp wiring")
    override val workflowStates: WorkflowStateRepository get() = error("unused by goal activity stamp wiring")
    override val workList = EmptyWorkListRepository
    override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
  }
}

internal fun goalRunnerDeps(
  manifestStore: GoalRunnerManifestStore,
  subtaskLauncher: GoalRunnerSubtaskLauncher,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  pullRequestPort: GoalPullRequestPort,
  phaseRecorder: FeatureTaskRuntimePhaseRecorder = goalRunnerDefaultPhaseRecorder(),
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
  phaseRecorder = phaseRecorder,
)

internal fun goalRunnerDefaultPhaseRecorder(): FeatureTaskRuntimePhaseRecorder = testPhaseRecorder(
  RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository()),
  testWorkflowSnapshotValidator,
  AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
  AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
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
