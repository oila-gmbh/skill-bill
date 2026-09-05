package skillbill.application.goalrunner

import skillbill.application.InMemoryRuntimeWorkflowRepository
import skillbill.application.RuntimeFakeDatabaseSessionFactory
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffFoundationValidator
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.application.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.application.goalrunner.model.DefaultGoalRunnerFinalizationBoundariesPort
import skillbill.application.goalrunner.model.DefaultGoalRunnerRunBoundariesPort
import skillbill.application.goalrunner.model.DefaultGoalRunnerSubtaskLaunchBoundariesPort
import skillbill.application.goalrunner.model.GoalRunnerDeps
import skillbill.application.goalrunner.model.GoalRunnerFinalizationBoundariesPort
import skillbill.application.goalrunner.model.GoalRunnerRunBoundariesPort
import skillbill.application.goalrunner.model.GoalRunnerSubtaskLaunchBoundariesPort
import skillbill.application.goalrunner.planning.DefaultGoalPlanningSweep
import skillbill.application.goalrunner.planning.GoalChildPlanningHydratorPortAdapter
import skillbill.application.goalrunner.planning.GoalPlanningAttemptRecorder
import skillbill.application.goalrunner.planning.GoalPlanningRefreshLiveness
import skillbill.application.goalrunner.planning.GoalPlanningRejectionRecorder
import skillbill.application.goalrunner.planning.GoalPlanningStatusReasonCoherence
import skillbill.application.goalrunner.planning.GoalPlanningSweep
import skillbill.application.goalrunner.planning.model.DefaultGoalPlanningSweepCheckpointPort
import skillbill.application.goalrunner.planning.model.DefaultGoalPlanningSweepLaunchPort
import skillbill.application.goalrunner.planning.model.GoalPlanningBurstSchedule
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepCheckpointPort
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepLaunchPort
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.application.realFeatureTaskRuntimePhaseOutputValidator
import skillbill.application.realPlanningProjectionValidator
import skillbill.application.telemetry.GoalLifecycleTelemetryEmitter
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.testHarnessClock
import skillbill.application.testRepositoryRoot
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.ports.concurrency.SequentialBoundedWorkFanOutPort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairStore
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.NoopGoalRunnerAttemptLedgerStore
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.review.ReviewRepository
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.telemetry.LifecycleTelemetryRepository
import skillbill.ports.telemetry.TelemetryOutboxRepository
import skillbill.ports.telemetry.TelemetryReconciliationRepository
import skillbill.ports.time.NoopRuntimeTimingPort
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.specscratch.SpecScratchStore
import skillbill.ports.workflow.specscratch.UnavailableSpecScratchStore
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeImplementationAttemptValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeQuarantineValidator
import java.nio.file.Path
import java.time.Clock

internal fun testActivityStampWriter(
  database: DatabaseSessionFactory = TestGoalActivityStampDatabase,
): AgentActivityStampWriter = AgentActivityStampWriter(database, Clock.systemUTC())

internal data class GoalRunnerTestWiring(
  val runBoundaries: GoalRunnerRunBoundariesPort,
  val launchBoundaries: GoalRunnerSubtaskLaunchBoundariesPort,
  val finalizationBoundaries: GoalRunnerFinalizationBoundariesPort,
)

internal data class GoalRunnerTestWiringParams(
  val manifestStore: GoalRunnerManifestStore,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val pullRequestPort: GoalPullRequestPort,
  val phaseRecorder: FeatureTaskRuntimePhaseRecorder = goalRunnerDefaultPhaseRecorder(),
  val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService? = null,
)

internal fun testGoalRunnerWiring(params: GoalRunnerTestWiringParams): GoalRunnerTestWiring {
  val clock = Clock.systemUTC()
  val diagnostics = NoopRuntimeDiagnostics
  val runBoundaries = DefaultGoalRunnerRunBoundariesPort(
    manifestStore = params.manifestStore,
    outcomeStore = params.outcomeStore,
    goalPlanningSweep = GoalPlanningSweep.NONE,
    telemetry = GoalLifecycleTelemetryEmitter.NONE,
    clock = clock,
    diagnostics = diagnostics,
    executionCoordinator = GoalRunnerExecutionCoordinator.NONE,
    phaseRecorder = params.phaseRecorder,
    unaddressedFindingsLedgerService = params.unaddressedFindingsLedgerService,
  )
  val launchBoundaries = DefaultGoalRunnerSubtaskLaunchBoundariesPort(
    manifestStore = params.manifestStore,
    outcomeStore = params.outcomeStore,
    subtaskLauncher = params.subtaskLauncher,
    gitOperations = NoopWorkflowGitOperations,
  )
  val finalizationBoundaries = DefaultGoalRunnerFinalizationBoundariesPort(
    manifestStore = params.manifestStore,
    outcomeStore = params.outcomeStore,
    pullRequestPort = params.pullRequestPort,
    specScratchStore = UnavailableSpecScratchStore,
    gitOperations = NoopWorkflowGitOperations,
    diagnostics = diagnostics,
    unaddressedFindingsLedgerService = params.unaddressedFindingsLedgerService,
  )
  return GoalRunnerTestWiring(runBoundaries, launchBoundaries, finalizationBoundaries)
}

internal data class GoalRunnerDepsCompat(
  val manifestStore: GoalRunnerManifestStore,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val pullRequestPort: GoalPullRequestPort,
  val goalPlanningSweep: GoalPlanningSweep = GoalPlanningSweep.NONE,
  val specScratchStore: SpecScratchStore = UnavailableSpecScratchStore,
  val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  val telemetry: GoalLifecycleTelemetryEmitter = GoalLifecycleTelemetryEmitter.NONE,
  val clock: Clock = Clock.systemUTC(),
  val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService? = null,
  val executionCoordinator: GoalRunnerExecutionCoordinator = GoalRunnerExecutionCoordinator.NONE,
  val phaseRecorder: FeatureTaskRuntimePhaseRecorder = goalRunnerDefaultPhaseRecorder(),
) {
  fun toWiring(): GoalRunnerTestWiring = GoalRunnerTestWiring(
    runBoundaries = DefaultGoalRunnerRunBoundariesPort(
      manifestStore = manifestStore,
      outcomeStore = outcomeStore,
      goalPlanningSweep = goalPlanningSweep,
      telemetry = telemetry,
      clock = clock,
      diagnostics = NoopRuntimeDiagnostics,
      executionCoordinator = executionCoordinator,
      phaseRecorder = phaseRecorder,
      unaddressedFindingsLedgerService = unaddressedFindingsLedgerService,
    ),
    launchBoundaries = DefaultGoalRunnerSubtaskLaunchBoundariesPort(
      manifestStore = manifestStore,
      outcomeStore = outcomeStore,
      subtaskLauncher = subtaskLauncher,
      gitOperations = gitOperations,
    ),
    finalizationBoundaries = DefaultGoalRunnerFinalizationBoundariesPort(
      manifestStore = manifestStore,
      outcomeStore = outcomeStore,
      pullRequestPort = pullRequestPort,
      specScratchStore = specScratchStore,
      gitOperations = gitOperations,
      diagnostics = NoopRuntimeDiagnostics,
      unaddressedFindingsLedgerService = unaddressedFindingsLedgerService,
    ),
  )
}

internal fun goalRunnerDeps(
  manifestStore: GoalRunnerManifestStore,
  subtaskLauncher: GoalRunnerSubtaskLauncher,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  pullRequestPort: GoalPullRequestPort,
): GoalRunnerDepsCompat = GoalRunnerDepsCompat(
  manifestStore = manifestStore,
  subtaskLauncher = subtaskLauncher,
  outcomeStore = outcomeStore,
  pullRequestPort = pullRequestPort,
)

internal fun testGoalRunner(deps: GoalRunnerDepsCompat): GoalRunner = testGoalRunner(deps.toWiring())

internal fun testGoalRunner(wiring: GoalRunnerTestWiring): GoalRunner {
  val progressReader = GoalRunnerProgressReader(wiring.runBoundaries.outcomeStore)
  val finalization = GoalRunnerFinalization(wiring.finalizationBoundaries, progressReader)
  return GoalRunner(
    deps = GoalRunnerDeps(
      runBoundaries = wiring.runBoundaries,
      launchBoundaries = wiring.launchBoundaries,
      workerRequestHandler = GoalRunnerWorkerRequestHandler(
        wiring.runBoundaries.manifestStore,
        wiring.runBoundaries.outcomeStore,
      ),
      reconciler = GoalRunnerLaunchReconciler(
        wiring.runBoundaries.manifestStore,
        wiring.runBoundaries.outcomeStore,
        progressReader,
        testActivityStampWriter(),
        wiring.runBoundaries.clock,
        wiring.runBoundaries.diagnostics,
      ),
      progressReader = progressReader,
      pauseBoundary = GoalRunnerPauseBoundary(wiring.runBoundaries.manifestStore),
      runPreparation = GoalRunnerRunPreparation(wiring.runBoundaries.manifestStore, TestRepositoryEnclosingRoot),
      launchPrepare = GoalRunnerSubtaskLaunchPrepare(wiring.launchBoundaries, TestRepositoryEnclosingRoot),
      finalization = finalization,
    ),
  )
}

internal fun testGoalRunner(
  manifestStore: GoalRunnerManifestStore,
  subtaskLauncher: GoalRunnerSubtaskLauncher,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  pullRequestPort: GoalPullRequestPort,
  phaseRecorder: FeatureTaskRuntimePhaseRecorder = goalRunnerDefaultPhaseRecorder(),
): GoalRunner = testGoalRunner(
  testGoalRunnerWiring(
    GoalRunnerTestWiringParams(
      manifestStore = manifestStore,
      subtaskLauncher = subtaskLauncher,
      outcomeStore = outcomeStore,
      pullRequestPort = pullRequestPort,
      phaseRecorder = phaseRecorder,
    ),
  ),
)

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
    override val goalRunnerControls = EmptyGoalRunnerControlRepository
  }
}

internal fun goalRunnerDefaultPhaseRecorder(): FeatureTaskRuntimePhaseRecorder = testPhaseRecorder(
  RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository()),
  testWorkflowSnapshotValidator,
)

internal data class GoalRunnerStatusTestPorts(
  val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  val childRepairStore: GoalRunnerChildRepairStore = NoopGoalRunnerChildRepairStore,
  val runtimeStatusService: FeatureTaskRuntimeStatusService? = null,
)

internal fun testGoalRunnerStatusService(
  manifestStore: GoalRunnerManifestStore,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  phaseRecorder: FeatureTaskRuntimePhaseRecorder = goalRunnerDefaultPhaseRecorder(),
  clock: Clock = testHarnessClock,
  ports: GoalRunnerStatusTestPorts = GoalRunnerStatusTestPorts(),
): GoalRunnerStatusService {
  val projectionAssembler = GoalRunnerStatusProjectionAssembler(
    manifestStore = manifestStore,
    outcomeStore = outcomeStore,
    phaseRecorder = phaseRecorder,
    gitOperations = ports.gitOperations,
    attemptLedgerStore = NoopGoalRunnerAttemptLedgerStore,
    clock = clock,
    workerSupervisor = ports.workerSupervisor,
    planningStatusReasonCoherence = GoalPlanningStatusReasonCoherence.NONE,
    diagnostics = NoopRuntimeDiagnostics,
    runtimeStatusService = ports.runtimeStatusService,
    repositoryRoot = testRepositoryRoot,
  )
  return GoalRunnerStatusService(
    manifestStore = manifestStore,
    outcomeStore = outcomeStore,
    phaseRecorder = phaseRecorder,
    gitOperations = ports.gitOperations,
    clock = clock,
    workerSupervisor = ports.workerSupervisor,
    childRepairStore = ports.childRepairStore,
    repositoryRoot = testRepositoryRoot,
    repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
    projectionAssembler = projectionAssembler,
    resetReplanCoordinator = GoalRunnerResetReplanCoordinator(
      manifestStore = manifestStore,
      outcomeStore = outcomeStore,
      gitOperations = ports.gitOperations,
      diagnostics = NoopRuntimeDiagnostics,
      projectionAssembler = projectionAssembler,
      repositoryRoot = testRepositoryRoot,
      repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
    ),
  )
}

internal val testGoalChildPlanningHydratorPort = GoalChildPlanningHydratorPortAdapter(
  realFeatureTaskRuntimePhaseOutputValidator,
  realPlanningProjectionValidator,
  testHarnessClock,
)

internal fun testGoalRunnerChildRepairExecutor(
  gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
): GoalRunnerChildRepairOperations = GoalRunnerChildRepairOperations(
  testWorkflowSnapshotValidator,
  gitOperations,
  testDecompositionManifestValidator,
  testHarnessClock,
)

internal fun testWorkflowGoalRunnerManifestStore(
  database: DatabaseSessionFactory,
  decompositionManifestStore: DecompositionManifestStore,
  clock: Clock,
  decompositionManifestValidator: DecompositionManifestValidator = testDecompositionManifestValidator,
): GoalRunnerManifestStore = sqliteWorkflowGoalRunnerManifestStore(
  database = database,
  workflowSnapshotValidator = testWorkflowSnapshotValidator,
  decompositionManifestValidator = decompositionManifestValidator,
  decompositionManifestStore = decompositionManifestStore,
  clock = clock,
  decompositionManifestWriter = DecompositionManifestWriter(),
  repositoryRoot = testRepositoryRoot,
  planningHydrator = testGoalChildPlanningHydratorPort,
)

internal fun testWorkflowGoalRunnerOutcomeStore(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator = testWorkflowSnapshotValidator,
  gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  workerSupervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  artifactPorts: OutcomeStoreTestArtifactPorts = OutcomeStoreTestArtifactPorts(),
) = sqliteWorkflowGoalRunnerOutcomeStore(
  database = database,
  workflowSnapshotValidator = workflowSnapshotValidator,
  gitOperations = gitOperations,
  workerSupervisor = workerSupervisor,
  clock = testHarnessClock,
  artifactPorts = artifactPorts,
  decompositionManifestValidator = testDecompositionManifestValidator,
  decompositionManifestWriter = testDecompositionManifestWriter,
  childRepairExecutor = testGoalRunnerChildRepairExecutor(gitOperations),
)

internal fun testDefaultGoalPlanningSweep(
  checkpointPort: GoalPlanningSweepCheckpointPort,
  launchPort: GoalPlanningSweepLaunchPort,
): DefaultGoalPlanningSweep = DefaultGoalPlanningSweep(checkpointPort, launchPort, TestRepositoryEnclosingRoot)

internal data class GoalPlanningSweepPortsParams(
  val checkpoint: GoalPlanningPreparationCheckpoint,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  val manifestFileStore: DecompositionManifestStore,
  val contextDiscovery: GoalPlanningContextDiscovery,
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator = realPlanningProjectionValidator,
  val planningAttemptRecorder: GoalPlanningAttemptRecorder = GoalPlanningAttemptRecorder.NONE,
  val manifestStore: GoalRunnerManifestStore = TestNoopGoalPlanningManifestStore,
  val planningRejectionRecorder: GoalPlanningRejectionRecorder = GoalPlanningRejectionRecorder.NONE,
  val timingPort: RuntimeTimingPort = NoopRuntimeTimingPort,
  val fanOutPort: BoundedWorkFanOutPort = SequentialBoundedWorkFanOutPort,
  val burstSchedule: GoalPlanningBurstSchedule = GoalPlanningBurstSchedule(
    planFanOutCap = GoalPlanningBurstSchedule.DEFAULT_PLAN_FAN_OUT_CAP,
    emptyTurnBackoffBase = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_BASE,
    emptyTurnBackoffFactor = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_FACTOR,
    waitSlice = GoalPlanningBurstSchedule.DEFAULT_WAIT_SLICE,
  ),
  val refreshLiveness: GoalPlanningRefreshLiveness = GoalPlanningRefreshLiveness.IDLE,
)

internal fun testGoalPlanningSweepPorts(params: GoalPlanningSweepPortsParams): DefaultGoalPlanningSweep =
  testDefaultGoalPlanningSweep(
    DefaultGoalPlanningSweepCheckpointPort(
      checkpoint = params.checkpoint,
      outputValidator = params.outputValidator,
      invariantsSource = params.invariantsSource,
      manifestFileStore = params.manifestFileStore,
      contextDiscovery = params.contextDiscovery,
      planningProjectionValidator = params.planningProjectionValidator,
    ),
    DefaultGoalPlanningSweepLaunchPort(
      subtaskLauncher = params.subtaskLauncher,
      manifestStore = params.manifestStore,
      planningAttemptRecorder = params.planningAttemptRecorder,
      planningRejectionRecorder = params.planningRejectionRecorder,
      timingPort = params.timingPort,
      fanOutPort = params.fanOutPort,
      burstSchedule = params.burstSchedule,
      refreshLiveness = params.refreshLiveness,
    ),
  )

internal fun testPhaseRecorder(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  handoffEnvelopeValidator: FeatureTaskRuntimeHandoffEnvelopeValidator =
    AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
  handoffFoundationValidator: FeatureTaskRuntimeHandoffFoundationValidator =
    AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
  diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
): FeatureTaskRuntimePhaseRecorder = FeatureTaskRuntimePhaseRecorder(
  database = database,
  workflowSnapshotValidator = workflowSnapshotValidator,
  handoffEnvelopeValidator = handoffEnvelopeValidator,
  handoffFoundationValidator = handoffFoundationValidator,
  quarantineValidator = NoopFeatureTaskRuntimeQuarantineValidator,
  implementationAttemptValidator = NoopFeatureTaskRuntimeImplementationAttemptValidator,
  rejectedOutputDiagnosticMetadataValidator = { },
  producerOutputEvidenceValidator = { },
  diagnostics = diagnostics,
  clock = testHarnessClock,
)

internal fun testGoalPlanningContextDiscovery(
  context: GoalPlanningContext = GoalPlanningContext(
    boundaryCatalog = emptyList(),
    boundaryCatalogTruncated = false,
    validationGuidance = "",
  ),
): GoalPlanningContextDiscovery = if (context == GoalPlanningContext(
    boundaryCatalog = emptyList(),
    boundaryCatalogTruncated = false,
    validationGuidance = "",
  )
) {
  GoalPlanningContextDiscovery.NONE
} else {
  object : GoalPlanningContextDiscovery {
    override fun discover(repoRoot: Path): GoalPlanningContext = context

    override fun discoverForFindingPaths(repoRoot: Path, findingPaths: List<String>, loudFailOnCapExceeded: Boolean) =
      GoalPlanningContextDiscovery.NONE.discoverForFindingPaths(
        repoRoot,
        findingPaths,
        loudFailOnCapExceeded,
      )
  }
}
