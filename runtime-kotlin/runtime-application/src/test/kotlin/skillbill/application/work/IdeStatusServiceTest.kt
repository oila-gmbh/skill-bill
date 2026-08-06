package skillbill.application.work

import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffFoundationValidator
import skillbill.application.featuretask.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeRunInvariantsStore
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.goalrunner.GoalRunnerStatusService
import skillbill.application.goalrunner.goalRepositoryIdentity
import skillbill.application.model.IdeStatusLifecycleState
import skillbill.application.model.IdeStatusProblemCode
import skillbill.application.model.IdeStatusRequest
import skillbill.application.model.IdeStatusWorkflowFamily
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.model.GoalRunnerSessionAccountingRecordRequest
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.EmptyGoalPlanningPreparationRepository
import skillbill.ports.persistence.EmptyGoalRunnerControlRepository
import skillbill.ports.persistence.GoalRunnerControlRepository
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.TelemetryReconciliationRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkListRepository
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureTaskExecutionIdentity
import skillbill.ports.persistence.model.FeatureTaskRouteScope
import skillbill.ports.persistence.model.FeatureTaskWorkflowCandidate
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkItem
import skillbill.ports.persistence.model.WorkItemKind
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.workflow.IdeStatusValidator
import skillbill.workflow.NoopIdeStatusValidator
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * SKILL-148 Subtask 1: application-layer coverage for read-only IDE status selection,
 * repository isolation, and typed problem outcomes.
 */
class IdeStatusServiceTest {
  private val observedAt: Instant = Instant.parse("2026-08-06T12:00:00Z")
  private val clock: Clock = Clock.fixed(observedAt, ZoneOffset.UTC)

  @Test
  fun `invalid repository root yields typed invalid_repository_input without writes`() {
    val database = TrackingDatabase(work = emptyList(), workflows = IdeStatusWorkflowStates())
    val service = service(database)
    val missing = Files.createTempDirectory("ide-status-missing").resolve("no-repo")

    val result = service.status(IdeStatusRequest(repoRoot = missing.toString(), observedAt = observedAt))

    assertEquals(1, result.exitCode)
    assertEquals(IdeStatusProblemCode.INVALID_REPOSITORY_INPUT, result.snapshot.problem?.code)
    assertEquals(IdeStatusLifecycleState.IDLE, result.snapshot.lifecycleState)
    assertEquals(0, database.writeCalls)
    assertEquals(0, database.readCalls)
  }

  @Test
  fun `absent database yields typed problem and never opens a session`() {
    val fixture = gitRepoFixture("ide-status-absent-db")
    val database = TrackingDatabase(work = emptyList(), workflows = IdeStatusWorkflowStates(), exists = false)
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(0, result.exitCode)
    assertEquals(IdeStatusProblemCode.ABSENT_DATABASE, result.snapshot.problem?.code)
    assertEquals(goalRepositoryIdentity(fixture), result.snapshot.repositoryIdentity)
    assertEquals(0, database.readCalls)
    assertEquals(0, database.writeCalls)
  }

  @Test
  fun `empty work list yields no_matching_work via database read only`() {
    val fixture = gitRepoFixture("ide-status-idle")
    val database = TrackingDatabase(work = emptyList(), workflows = IdeStatusWorkflowStates())
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(0, result.exitCode)
    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertEquals(1, database.readCalls)
    assertEquals(0, database.writeCalls)
  }

  @Test
  fun `repository isolation ignores work bound to another repository identity`() {
    val fixture = gitRepoFixture("ide-status-isolation")
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(proseRecord("w-foreign", "2026-08-06T11:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(
      FeatureTaskExecutionIdentity(
        workflowId = "w-foreign",
        normalizedIssueKey = "SKILL-148",
        repositoryIdentity = "repo-root-realpath-v1:/other-repo",
        governedSpecPath = "spec.md",
        mode = FeatureTaskWorkflowMode.PROSE,
      ),
    )
    val database = TrackingDatabase(
      work = listOf(workItem("w-foreign", WorkItemKind.FEATURE_TASK_PROSE, "running", "2026-08-06T11:00:00Z")),
      workflows = workflows,
    )
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertNull(result.snapshot.workflowId)
  }

  @Test
  fun `active prose work outranks terminal competitor for the same repository`() {
    val fixture = gitRepoFixture("ide-status-precedence")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(proseRecord("w-active", "2026-08-06T10:00:00Z", currentStep = "implement"))
    workflows.saveFeatureImplementWorkflow(proseRecord("w-terminal", "2026-08-06T11:00:00Z", currentStep = "finish"))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-terminal", identity))
    val database = TrackingDatabase(
      work = listOf(
        workItem("w-terminal", WorkItemKind.FEATURE_TASK_PROSE, "completed", "2026-08-06T11:00:00Z"),
        workItem("w-active", WorkItemKind.FEATURE_TASK_PROSE, "running", "2026-08-06T10:00:00Z"),
      ),
      workflows = workflows,
    )
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(0, result.exitCode)
    assertNull(result.snapshot.problem)
    assertEquals("w-active", result.snapshot.workflowId)
    assertEquals(IdeStatusWorkflowFamily.FEATURE_TASK_PROSE, result.snapshot.workflowFamily)
    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
    assertEquals("implement", result.snapshot.currentStep.id)
    assertEquals(Instant.parse("2026-08-06T08:00:00Z"), result.snapshot.startedAt)
    assertEquals(0, database.writeCalls)
  }

  @Test
  fun `goal-child prose is suppressed when an authoritative feature-goal exists for the issue`() {
    val fixture = gitRepoFixture("ide-status-goal-child")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(proseRecord("w-child", "2026-08-06T11:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(
      identityFor("w-child", identity).copy(routeScope = FeatureTaskRouteScope.GOAL_CHILD),
    )
    val controls = object : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
      override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
        GoalRunnerControlState(repositoryIdentity = identity)
    }
    val database = TrackingDatabase(
      work = listOf(
        workItem("w-child", WorkItemKind.FEATURE_TASK_PROSE, "running", "2026-08-06T11:00:00Z"),
        workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T09:00:00Z"),
      ),
      workflows = workflows,
      controls = controls,
    )
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals("goal-1", result.snapshot.workflowId)
    assertEquals(IdeStatusWorkflowFamily.FEATURE_GOAL, result.snapshot.workflowFamily)
    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
  }

  @Test
  fun `started_at stays stable across two polls for the same durable prose work`() {
    val fixture = gitRepoFixture("ide-status-stable-start")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(proseRecord("w-stable", "2026-08-06T10:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-stable", identity))
    val database = TrackingDatabase(
      work = listOf(workItem("w-stable", WorkItemKind.FEATURE_TASK_PROSE, "running", "2026-08-06T10:00:00Z")),
      workflows = workflows,
    )
    val service = service(database)

    val first = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))
    val second = service.status(
      IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt.plusSeconds(60)),
    )

    assertEquals(first.snapshot.startedAt, second.snapshot.startedAt)
    assertEquals(Instant.parse("2026-08-06T08:00:00Z"), first.snapshot.startedAt)
  }

  @Test
  fun `unbound verify work is excluded without same-repo issue correlation`() {
    val fixture = gitRepoFixture("ide-status-verify-unbound")
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureVerifyWorkflow(verifyRecord("w-verify", "2026-08-06T11:00:00Z"))
    val database = TrackingDatabase(
      work = listOf(workItem("w-verify", WorkItemKind.FEATURE_VERIFY, "running", "2026-08-06T11:00:00Z")),
      workflows = workflows,
    )
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertNull(result.snapshot.workflowId)
  }

  @Test
  fun `verify work for another repo issue correlation is not selected`() {
    val fixture = gitRepoFixture("ide-status-verify-other")
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureVerifyWorkflow(verifyRecord("w-verify", "2026-08-06T11:00:00Z"))
    workflows.saveFeatureImplementWorkflow(proseRecord("w-foreign", "2026-08-06T10:00:00Z"))
    workflows.saveFeatureTaskExecutionIdentity(
      identityFor("w-foreign", "repo-root-realpath-v1:/other-repo"),
    )
    val database = TrackingDatabase(
      work = listOf(
        workItem("w-verify", WorkItemKind.FEATURE_VERIFY, "running", "2026-08-06T11:00:00Z"),
        workItem("w-foreign", WorkItemKind.FEATURE_TASK_PROSE, "running", "2026-08-06T10:00:00Z"),
      ),
      workflows = workflows,
    )
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertNull(result.snapshot.workflowId)
  }

  @Test
  fun `verify work is included when issue correlates to same-repo feature-task identity`() {
    val fixture = gitRepoFixture("ide-status-verify-correlated")
    val identity = goalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureVerifyWorkflow(verifyRecord("w-verify", "2026-08-06T11:00:00Z"))
    workflows.saveFeatureImplementWorkflow(proseRecord("w-prose", "2026-08-06T09:00:00Z", currentStep = "finish"))
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-prose", identity))
    val database = TrackingDatabase(
      work = listOf(
        workItem("w-verify", WorkItemKind.FEATURE_VERIFY, "running", "2026-08-06T11:00:00Z"),
        workItem("w-prose", WorkItemKind.FEATURE_TASK_PROSE, "completed", "2026-08-06T09:00:00Z"),
      ),
      workflows = workflows,
    )
    val service = service(database)

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals(0, result.exitCode)
    assertNull(result.snapshot.problem)
    assertEquals("w-verify", result.snapshot.workflowId)
    assertEquals(IdeStatusWorkflowFamily.FEATURE_VERIFY, result.snapshot.workflowFamily)
  }

  @Test
  fun `goal current_subtask started_at comes from durable launched child WorkItem`() {
    val fixture = gitRepoFixture("ide-status-goal-subtask-start")
    val identity = goalRepositoryIdentity(fixture)
    val childStarted = Instant.parse("2026-08-06T09:15:00Z")
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureImplementWorkflow(
      proseRecord("w-child", "2026-08-06T11:00:00Z").copy(startedAt = childStarted.toString()),
    )
    workflows.saveFeatureTaskExecutionIdentity(
      identityFor("w-child", identity).copy(routeScope = FeatureTaskRouteScope.GOAL_CHILD),
    )
    val controls = object : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
      override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
        GoalRunnerControlState(repositoryIdentity = identity)
    }
    val manifestState = GoalRunnerManifestState(
      parentWorkflowId = "goal-1",
      dbPath = "/fake/ide-status.db",
      manifest = DecompositionManifest(
        issueKey = "SKILL-148",
        featureName = "ide-status",
        parentSpecPath = ".feature-specs/SKILL-148/spec.md",
        baseBranch = "main",
        featureBranch = "feat/SKILL-148",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 2, action = "start"),
        subtasks = listOf(
          DecompositionSubtask(
            id = 1,
            name = "One",
            specPath = "spec_1.md",
            status = "complete",
            workflowId = "w-done",
          ),
          DecompositionSubtask(
            id = 2,
            name = "Two",
            specPath = "spec_2.md",
            status = "in_progress",
            workflowId = "w-child",
            lastResumableStep = "implement",
          ),
        ),
      ),
      controlState = GoalRunnerControlState(repositoryIdentity = identity),
      repoRoot = fixture,
    )
    val database = TrackingDatabase(
      work = listOf(
        workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T10:00:00Z"),
        workItem("w-child", WorkItemKind.FEATURE_TASK_PROSE, "running", "2026-08-06T11:00:00Z")
          .copy(startedAt = childStarted),
      ),
      workflows = workflows,
      controls = controls,
    )
    val service = service(database, manifestStore = StubGoalManifestStore(manifestState))

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = observedAt))

    assertEquals("goal-1", result.snapshot.workflowId)
    assertEquals(IdeStatusWorkflowFamily.FEATURE_GOAL, result.snapshot.workflowFamily)
    assertEquals("2", result.snapshot.currentSubtask?.id)
    assertEquals(childStarted, result.snapshot.currentSubtask?.startedAt)
    assertEquals(Instant.parse("2026-08-06T08:00:00Z"), result.snapshot.startedAt)
  }

  private fun service(
    database: TrackingDatabase,
    manifestStore: GoalRunnerManifestStore = EmptyManifestStore,
  ): IdeStatusService {
    val snapshotValidator = object : WorkflowSnapshotValidator {
      override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
    }
    val phaseRecorder = FeatureTaskRuntimePhaseRecorder(
      database,
      snapshotValidator,
      AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
      AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
    )
    val projector = IdeStatusProjector(
      workflowSnapshotValidator = snapshotValidator,
      goalRunnerStatusService = GoalRunnerStatusService(
        manifestStore = manifestStore,
        outcomeStore = EmptyOutcomeStore,
        phaseRecorder = phaseRecorder,
      ),
      featureTaskRuntimeStatusService = FeatureTaskRuntimeStatusService(
        recorder = phaseRecorder,
        runInvariantsStore = FeatureTaskRuntimeRunInvariantsStore(database, snapshotValidator),
        decomposeTerminalRecorder = FeatureTaskRuntimeDecomposeTerminalRecorder(database, snapshotValidator),
      ),
    )
    return IdeStatusService(
      database = database,
      projector = projector,
      ideStatusValidator = EmitShapeValidator,
      clock = clock,
    )
  }
}

private object EmitShapeValidator : IdeStatusValidator by NoopIdeStatusValidator {
  override fun validate(snapshot: Map<String, Any?>, sourceLabel: String) {
    require(snapshot["contract_version"] == "0.1")
    require(snapshot["repository_identity"] is String)
    require(snapshot["lifecycle_state"] is String)
  }
}

private fun gitRepoFixture(prefix: String): Path {
  val root = Files.createTempDirectory(prefix)
  Files.createDirectory(root.resolve(".git"))
  return root.toRealPath()
}

private fun workItem(
  workflowId: String,
  kind: WorkItemKind,
  state: String,
  updatedAt: String,
): WorkItem = WorkItem(
  issueKey = "SKILL-148",
  workflowKind = kind,
  workflowId = workflowId,
  startedAt = Instant.parse("2026-08-06T08:00:00Z"),
  currentState = state,
  stateEnteredAt = Instant.parse(updatedAt),
  stateEnteredAtEstimated = false,
)

private fun identityFor(
  workflowId: String,
  repositoryIdentity: String,
): FeatureTaskExecutionIdentity = FeatureTaskExecutionIdentity(
  workflowId = workflowId,
  normalizedIssueKey = "SKILL-148",
  repositoryIdentity = repositoryIdentity,
  governedSpecPath = "spec.md",
  mode = FeatureTaskWorkflowMode.PROSE,
)

private fun proseRecord(
  workflowId: String,
  updatedAt: String,
  currentStep: String = "implement",
): WorkflowStateRecord = WorkflowStateRecord(
  workflowId = workflowId,
  sessionId = "session-$workflowId",
  workflowName = "bill-feature-task",
  contractVersion = "0.1",
  workflowStatus = "running",
  currentStepId = currentStep,
  stepsJson =
    """[{"step_id":"preplan","status":"completed"},{"step_id":"$currentStep","status":"running"}]""",
  artifactsJson = "{}",
  startedAt = "2026-08-06T08:00:00Z",
  updatedAt = updatedAt,
  finishedAt = null,
  issueKey = "SKILL-148",
  mode = FeatureTaskWorkflowMode.PROSE,
)

private fun verifyRecord(
  workflowId: String,
  updatedAt: String,
): WorkflowStateRecord = WorkflowStateRecord(
  workflowId = workflowId,
  sessionId = "session-$workflowId",
  workflowName = "bill-feature-verify",
  contractVersion = "0.1",
  workflowStatus = "running",
  currentStepId = "verify",
  stepsJson = """[{"step_id":"verify","status":"running"}]""",
  artifactsJson = "{}",
  startedAt = "2026-08-06T08:00:00Z",
  updatedAt = updatedAt,
  finishedAt = null,
  issueKey = "SKILL-148",
)

private class TrackingDatabase(
  private val work: List<WorkItem>,
  private val workflows: WorkflowStateRepository,
  private val exists: Boolean = true,
  private val controls: GoalRunnerControlRepository = EmptyGoalRunnerControlRepository,
) : DatabaseSessionFactory {
  var readCalls: Int = 0
    private set
  var writeCalls: Int = 0
    private set

  override fun resolveDbPath(dbOverride: String?): Path = Path.of("/fake/ide-status.db")

  override fun databaseExists(dbOverride: String?): Boolean = exists

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T {
    readCalls += 1
    return block(unitOfWork())
  }

  override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T {
    writeCalls += 1
    return block(unitOfWork())
  }

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T {
    writeCalls += 1
    return block(unitOfWork())
  }

  private fun unitOfWork(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = Path.of("/fake/ide-status.db")
    override val workflowStates = workflows
    override val workList: WorkListRepository = object : WorkListRepository {
      override fun list(limit: Int?): List<WorkItem> = limit?.let(work::take) ?: work
    }
    override val goalRunnerControls = controls
    override val learnings: LearningRepository
      get() = error("Not exercised by IdeStatusServiceTest.")
    override val reviews: ReviewRepository
      get() = error("Not exercised by IdeStatusServiceTest.")
    override val lifecycleTelemetry: LifecycleTelemetryRepository
      get() = error("Not exercised by IdeStatusServiceTest.")
    override val telemetryReconciliation: TelemetryReconciliationRepository
      get() = error("Not exercised by IdeStatusServiceTest.")
    override val telemetryOutbox: TelemetryOutboxRepository
      get() = error("Not exercised by IdeStatusServiceTest.")
    override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
  }
}

private class IdeStatusWorkflowStates : WorkflowStateRepository {
  private val implement = mutableMapOf<String, WorkflowStateRecord>()
  private val verify = mutableMapOf<String, WorkflowStateRecord>()
  private val identities = mutableMapOf<String, FeatureTaskExecutionIdentity>()

  override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) {
    identities[identity.workflowId] = identity
  }

  override fun getFeatureTaskExecutionIdentity(workflowId: String): FeatureTaskExecutionIdentity? =
    identities[workflowId]

  override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = emptyList()

  override fun findGoalChildFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = emptyList()

  override fun countGoalChildIdentities(normalizedIssueKey: String): Int =
    identities.values.count {
      it.normalizedIssueKey == normalizedIssueKey && it.routeScope == FeatureTaskRouteScope.GOAL_CHILD
    }

  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) {
    implement[row.workflowId] = row
  }

  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? = implement[workflowId]

  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> =
    implement.values.toList().take(limit)

  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? = implement.values.lastOrNull()

  override fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary? = null

  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) {
    verify[row.workflowId] = row
  }

  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? = verify[workflowId]

  override fun listFeatureVerifyWorkflows(limit: Int): List<WorkflowStateRecord> =
    verify.values.toList().take(limit)

  override fun latestFeatureVerifyWorkflow(): WorkflowStateRecord? = verify.values.lastOrNull()

  override fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary? = null

  override fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord) = Unit

  override fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord? = null

  override fun listFeatureTaskRuntimeWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()

  override fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord? = null
}

private class StubGoalManifestStore(
  private val state: GoalRunnerManifestState,
) : GoalRunnerManifestStore {
  override fun loadByIssueKey(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path?,
  ): GoalRunnerManifestState? =
    state.takeIf { it.manifest.issueKey.equals(issueKey, ignoreCase = true) }

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState = state

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    dbPathOverride: String?,
  ): Boolean = false

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String?,
  ): Boolean = false

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    dbPathOverride: String?,
  ): Boolean = false
}

private object EmptyManifestStore : GoalRunnerManifestStore {
  override fun loadByIssueKey(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = null

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState = state

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    dbPathOverride: String?,
  ): Boolean = false

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String?,
  ): Boolean = false

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    dbPathOverride: String?,
  ): Boolean = false
}

private object EmptyOutcomeStore : GoalRunnerWorkflowOutcomeStore {
  override fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = null

  override fun recoverAndPersistTerminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = null

  override fun recoverMissingResultPrefixOutput(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    output: Map<String, Any?>,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = null

  override fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
    dbPathOverride: String?,
  ): Map<Int, GoalRunnerStoredOutcome> = emptyMap()

  override fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent?,
    dbPathOverride: String?,
  ): String? = null

  override fun progress(workflowId: String, dbPathOverride: String?): GoalRunnerWorkflowProgress? = null

  override fun recordObservabilityEvent(
    request: GoalRunnerObservabilityRecordRequest,
    dbPathOverride: String?,
  ): Boolean = false

  override fun recordProgressEvent(
    request: GoalRunnerProgressEventRecordRequest,
    dbPathOverride: String?,
  ): Boolean = false

  override fun recordSessionAccounting(
    request: GoalRunnerSessionAccountingRecordRequest,
    dbPathOverride: String?,
  ): Boolean = false

  override fun recordAttemptLedgerEntry(
    request: GoalRunnerAttemptLedgerRecordRequest,
    dbPathOverride: String?,
  ): Boolean = false

  override fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome>,
    dbPathOverride: String?,
  ): Boolean = false

  override fun ledgerSequenceWatermarks(
    issueKey: String,
    dbPathOverride: String?,
  ): skillbill.ports.goalrunner.model.GoalRunnerLedgerSequenceWatermarks =
    skillbill.ports.goalrunner.model.GoalRunnerLedgerSequenceWatermarks()

  override fun reopenBlockedPhaseForOperatorResume(
    workflowId: String,
    preferredPhaseId: String,
    reason: String,
    dbPathOverride: String?,
  ): Boolean = false
}
