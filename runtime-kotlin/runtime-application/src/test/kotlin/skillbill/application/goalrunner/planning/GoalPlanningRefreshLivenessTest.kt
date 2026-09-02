package skillbill.application.goalrunner.planning

import org.junit.jupiter.api.Test
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffFoundationValidator
import skillbill.application.featuretask.featureTaskRuntimePhaseRecorder
import skillbill.application.manifest
import skillbill.application.testHarnessClock
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.featuretask.model.FeatureTaskExecutionIdentity
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.featuretask.model.FeatureTaskWorkflowCandidate
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.learning.LearningRepository
import skillbill.ports.review.ReviewRepository
import skillbill.ports.telemetry.LifecycleTelemetryRepository
import skillbill.ports.telemetry.TelemetryOutboxRepository
import skillbill.ports.telemetry.TelemetryReconciliationRepository
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.FeatureImplementSessionSummary
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.model.FeatureVerifySessionSummary
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.engine.WorkflowSnapshotValidator
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers ChildAwareGoalPlanningRefreshLiveness: parent-lease absence is IDLE for the owning
 * prepare(), and only the current child's RUNTIME lease can refuse refresh (AC-004).
 */
class GoalPlanningRefreshLivenessTest {
  private val now = Instant.parse("2026-08-11T00:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)

  @Test
  fun `no child workflow id is IDLE without consulting a parent lease`() {
    val harness = RefreshLivenessHarness(clock)
    val state = manifestState(childWorkflowId = null)

    assertEquals(ExecutionLiveness.IDLE, harness.liveness.resolve(state, null))
    assertEquals(
      ExecutionLiveness.IDLE,
      resolveChildExecutionLiveness(state.manifest.subtasks.first(), null, harness.recorder, clock),
    )
  }

  @Test
  fun `blank child workflow id is IDLE`() {
    val harness = RefreshLivenessHarness(clock)
    val state = manifestState(childWorkflowId = "  ")

    assertEquals(ExecutionLiveness.IDLE, harness.liveness.resolve(state, null))
  }

  @Test
  fun `current child with unexpired RUNTIME lease is LIVE`() {
    val harness = RefreshLivenessHarness(clock)
    harness.seedRuntimeChild("wfl-child", expiresAt = now.plusSeconds(60).toString())
    val state = manifestState(childWorkflowId = "wfl-child")

    assertEquals(ExecutionLiveness.LIVE, harness.liveness.resolve(state, null))
    assertEquals(
      "Goal 'SKILL-56' is live; refuse shared-preplan refresh while the current child run is active.",
      refuseRefreshReason("SKILL-56", ExecutionLiveness.LIVE),
    )
  }

  @Test
  fun `current child with expired RUNTIME lease is IDLE`() {
    val harness = RefreshLivenessHarness(clock)
    harness.seedRuntimeChild("wfl-child", expiresAt = now.minusSeconds(1).toString())
    val state = manifestState(childWorkflowId = "wfl-child")

    assertEquals(ExecutionLiveness.IDLE, harness.liveness.resolve(state, null))
    assertNull(refuseRefreshReason("SKILL-56", ExecutionLiveness.IDLE))
  }

  @Test
  fun `current child whose workflow mode is not RUNTIME is UNKNOWN`() {
    val harness = RefreshLivenessHarness(clock)
    // Row absent from RUNTIME storage → existingWorkflowMode returns null → UNKNOWN.
    val state = manifestState(childWorkflowId = "wfl-missing")

    assertEquals(ExecutionLiveness.UNKNOWN, harness.liveness.resolve(state, null))
    assertTrue(
      refuseRefreshReason("SKILL-56", ExecutionLiveness.UNKNOWN)!!
        .contains("unknown execution liveness"),
    )
  }

  @Test
  fun `intent naming a missing subtask falls through to IDLE`() {
    val harness = RefreshLivenessHarness(clock)
    val base = manifest(subtaskCount = 1)
    val state = GoalRunnerManifestState(
      parentWorkflowId = "wfl-parent",
      dbPath = "/tmp/refresh-liveness.db",
      manifest = base.copy(currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 9, action = "resume")),
    )

    assertEquals(ExecutionLiveness.IDLE, harness.liveness.resolve(state, null))
  }

  private fun manifestState(childWorkflowId: String?): GoalRunnerManifestState {
    val base = manifest(subtaskCount = 1)
    val subtask = base.subtasks.single().let { row ->
      if (childWorkflowId == null) row else row.copy(workflowId = childWorkflowId)
    }
    return GoalRunnerManifestState(
      parentWorkflowId = "wfl-parent",
      dbPath = "/tmp/refresh-liveness.db",
      manifest = base.copy(
        status = "in_progress",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
        subtasks = listOf(subtask),
      ),
    )
  }
}

private class RefreshLivenessHarness(clock: Clock) {
  private val repository = SeedableRefreshLivenessWorkflowStates()
  private val database = SeedableRefreshLivenessDatabase(repository)
  val recorder = featureTaskRuntimePhaseRecorder(
    database,
    NoopRefreshLivenessSnapshotValidator,
    AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
    AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
    testHarnessClock,
  )
  val liveness = ChildAwareGoalPlanningRefreshLiveness(recorder, clock)

  fun seedRuntimeChild(workflowId: String, expiresAt: String) {
    repository.saveFeatureTaskRuntimeWorkflow(
      WorkflowStateRecord(
        workflowId = workflowId,
        sessionId = "session",
        workflowName = "bill-feature-task",
        contractVersion = "0.1",
        workflowStatus = "running",
        currentStepId = "implement",
        stepsJson = "[]",
        artifactsJson = "{}",
        startedAt = null,
        updatedAt = null,
        finishedAt = null,
        mode = FeatureTaskWorkflowMode.RUNTIME,
      ),
    )
    repository.seedOwnership(workflowId, expiresAt)
  }
}

private object NoopRefreshLivenessSnapshotValidator : WorkflowSnapshotValidator {
  override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
}

private class SeedableRefreshLivenessDatabase(
  private val repository: SeedableRefreshLivenessWorkflowStates,
) : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/goal-planning-refresh-liveness.db")

  override fun resolveDbPath(dbOverride: String?): Path = dbPath

  override fun databaseExists(dbOverride: String?): Boolean = true

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T = transaction(dbOverride, block)

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = this@SeedableRefreshLivenessDatabase.dbPath
    override val reviews: ReviewRepository get() = error("unused by refresh liveness tests")
    override val learnings: LearningRepository get() = error("unused by refresh liveness tests")
    override val lifecycleTelemetry: LifecycleTelemetryRepository get() = error("unused by refresh liveness tests")
    override val telemetryReconciliation: TelemetryReconciliationRepository
      get() = error("unused by refresh liveness tests")
    override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused by refresh liveness tests")
    override val workflowStates: WorkflowStateRepository = repository
    override val workList = EmptyWorkListRepository
    override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
  }
}

private class SeedableRefreshLivenessWorkflowStates : WorkflowStateRepository {
  private val taskRuntimeRows = linkedMapOf<String, WorkflowStateRecord>()
  private val ownershipRows = linkedMapOf<String, FeatureTaskRuntimeWorkerOwnership>()

  fun seedOwnership(workflowId: String, expiresAt: String) {
    ownershipRows[workflowId] = FeatureTaskRuntimeWorkerOwnership(
      workflowId = workflowId,
      ownerToken = "owner-token-123456",
      generation = 1,
      hostIdentity = "host",
      bootIdentity = "boot",
      pid = 1234,
      processBirthToken = "birth-1234",
      leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
      phaseId = "implement",
      phaseAttempt = 1,
      heartbeatAt = "2026-08-11T00:00:00Z",
      expiresAt = expiresAt,
    )
  }

  override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) = Unit

  override fun findStandaloneFeatureTaskCandidates(normalizedIssueKey: String, repositoryIdentity: String) =
    emptyList<FeatureTaskWorkflowCandidate>()

  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) = Unit

  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? = null

  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()

  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? = null

  override fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary? = null

  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) = Unit

  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? = null

  override fun listFeatureVerifyWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()

  override fun latestFeatureVerifyWorkflow(): WorkflowStateRecord? = null

  override fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary? = null

  override fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord) {
    taskRuntimeRows[row.workflowId] = row
  }

  override fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord? = taskRuntimeRows[workflowId]

  override fun listFeatureTaskRuntimeWorkflows(limit: Int): List<WorkflowStateRecord> =
    taskRuntimeRows.values.toList().asReversed().take(limit)

  override fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord? =
    listFeatureTaskRuntimeWorkflows(1).firstOrNull()

  override fun getFeatureTaskRuntimeWorkerOwnership(workflowId: String): FeatureTaskRuntimeWorkerOwnership? =
    ownershipRows[workflowId]
}
