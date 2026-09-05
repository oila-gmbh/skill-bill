package skillbill.application

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.goalrunner.OutcomeStoreTestArtifactPorts
import skillbill.application.goalrunner.testWorkflowGoalRunnerOutcomeStore
import skillbill.application.phaseartifacts.phaseRecordsFrom
import skillbill.application.workflow.toSnapshot
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalAttemptLedgerEntry
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestRejectionReason
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowGoalRunnerOutcomeStoreTaskRuntimeTest {
  @Test
  fun `reads progress from task runtime workflows without probing prose mode`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(taskRuntimeWorkflowRecord("wftr-task-runtime"))
    val store = testWorkflowGoalRunnerOutcomeStore(
      FakeDatabaseSessionFactory(workflows),
      testWorkflowSnapshotValidator,
    )

    val progress = requireNotNull(store.progress("wftr-task-runtime"))

    assertEquals("wftr-task-runtime", progress.workflowId)
    assertEquals("running", progress.workflowStatus)
    assertEquals("implement", progress.currentStepId)
  }

  @Test
  fun `appends attempt ledger entries to task runtime workflows without probing prose mode`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(taskRuntimeWorkflowRecord("wftr-task-runtime"))
    val store = testWorkflowGoalRunnerOutcomeStore(
      FakeDatabaseSessionFactory(workflows),
      testWorkflowSnapshotValidator,
    )

    val recorded = store.recordAttemptLedgerEntry(
      GoalRunnerAttemptLedgerRecordRequest(
        workflowId = "wftr-task-runtime",
        entry = GoalAttemptLedgerEntry(
          action = GoalAttemptLedgerAction.FINAL_RECONCILED_OUTCOME,
          sequenceNumber = 1,
          timestamp = "2026-06-11T18:28:09Z",
          finalReconciledResult = "blocked",
        ),
      ),
    )

    assertTrue(recorded)
    assertNull(workflows.getFeatureImplementWorkflow("wftr-task-runtime"))
    val saved = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-task-runtime")).toSnapshot()
    val artifacts = decodeArtifacts(saved.artifactsJson)
    val ledger = artifacts["goal_attempt_ledger"] as List<*>
    val entry = ledger.single() as Map<*, *>
    assertEquals("final_reconciled_outcome", entry["action"])
    assertEquals("blocked", entry["final_reconciled_result"])
  }

  @Test
  fun `appends worker subtask request outcomes to task runtime workflows`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(taskRuntimeWorkflowRecord("wftr-task-runtime"))
    val store = testWorkflowGoalRunnerOutcomeStore(
      FakeDatabaseSessionFactory(workflows),
      testWorkflowSnapshotValidator,
    )

    val recorded = store.recordWorkerSubtaskRequestOutcomes(
      workflowId = "wftr-task-runtime",
      outcomes = listOf(
        GoalRunnerWorkerSubtaskRequestOutcome.Rejected(
          sourceStream = "stdout",
          reason = GoalRunnerWorkerSubtaskRequestRejectionReason.UNSAFE_PATH,
          message = "unsafe path",
        ),
      ),
      dbPathOverride = null,
    )

    assertTrue(recorded)
    assertNull(workflows.getFeatureImplementWorkflow("wftr-task-runtime"))
    val saved = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-task-runtime")).toSnapshot()
    val artifacts = decodeArtifacts(saved.artifactsJson)
    val outcomes = artifacts["goal_worker_subtask_request_outcomes"] as List<*>
    val rejected = outcomes.single() as Map<*, *>
    assertEquals("rejected", rejected["status"])
    assertEquals("unsafe_path", rejected["reason"])
  }

  @Test
  fun `raw review evidence must match a compact pass before it can be emitted or acknowledged`() {
    val workflows = InMemoryWorkflowStates()
    val state = GoalSubtaskReviewState.initial(
      reviewBaseSha = "a".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.AUTO,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = emptyList(),
    )
    workflows.saveFeatureTaskRuntimeWorkflow(
      goalReviewWorkflowRecord(
        workflowId = "wftr-goal-review",
        state = state,
        rawReviewResult = """
          {"verdict":"changes_requested","produced_outputs":{}}
        """.trimIndent(),
      ),
    )
    val store = testWorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      artifactPorts = OutcomeStoreTestArtifactPorts(
        phaseOutputValidator = AlwaysValidValidator,
      ),
    )

    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      store.unemittedGoalReviewPasses("wftr-goal-review")
    }
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      store.acknowledgeGoalReviewPass("wftr-goal-review", 1)
    }
  }

  @Test
  fun `prose raw review evidence with a matching empty compact pass can be emitted`() {
    val workflows = InMemoryWorkflowStates()
    val state = GoalSubtaskReviewState.initial(
      reviewBaseSha = "a".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.AUTO,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = emptyList(),
    )
    workflows.saveFeatureTaskRuntimeWorkflow(
      goalReviewWorkflowRecord(
        workflowId = "wftr-goal-review-prose",
        state = state,
        rawReviewResult = """
          [F-001] Major | path="runtime-kotlin/Example.kt" | line=10 | description=example finding in prose.
        """.trimIndent(),
      ),
    )
    val store = testWorkflowGoalRunnerOutcomeStore(
      FakeDatabaseSessionFactory(workflows),
      testWorkflowSnapshotValidator,
    )

    val passes = store.unemittedGoalReviewPasses("wftr-goal-review-prose")
    assertEquals(1, passes.size)
    assertEquals(FeatureTaskRuntimeVerdict.APPROVED, passes.single().verdict)
    assertTrue(store.acknowledgeGoalReviewPass("wftr-goal-review-prose", 1))
  }

  @Test
  fun `evidence-based reconcile keeps a running subtask with recent declared progress`() {
    // SKILL-87 (AC4): an empty active set with requireStalenessEvidence must NOT stale-block a child
    // that is plainly alive — a recent declared operation_heartbeat is positive liveness evidence.
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(
      runtimeCandidateRecord("wftr-alive", declaredProgressTimestamp = Instant.now()),
    )
    val store = testWorkflowGoalRunnerOutcomeStore(
      FakeDatabaseSessionFactory(workflows),
      testWorkflowSnapshotValidator,
    )

    val outcomes = store.reconcileAuthoritativeOutcomes(
      issueKey = "SKILL-87.1",
      activeWorkflowIds = emptySet(),
      gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
    )

    assertTrue(outcomes.isEmpty(), "a live subtask must not be reconciled into a terminal outcome")
    val alive = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-alive")).toSnapshot()
    assertEquals("running", alive.workflowStatus, "a live subtask must not be marked blocked")
  }

  @Test
  fun `evidence-based reconcile blocks a running subtask with no liveness past the staleness window`() {
    // SKILL-87 (AC4): the same empty-active-set reconcile DOES block a child whose only liveness signal
    // is stale beyond the window — positive evidence it is gone.
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(
      runtimeCandidateRecord(
        "wftr-stale",
        declaredProgressTimestamp = Instant.now().minus(2, ChronoUnit.HOURS),
      ),
    )
    val store = testWorkflowGoalRunnerOutcomeStore(
      FakeDatabaseSessionFactory(workflows),
      testWorkflowSnapshotValidator,
    )

    val outcomes = store.reconcileAuthoritativeOutcomes(
      issueKey = "SKILL-87.1",
      activeWorkflowIds = emptySet(),
      gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
    )

    val outcome = requireNotNull(outcomes[1])
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals("wftr-stale", outcome.workflowId)
    val stale = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-stale")).toSnapshot()
    assertEquals("blocked", stale.workflowStatus)
  }

  @Test
  fun `evidence-based reconcile blocks a running subtask whose only liveness is an old sqlite updatedAt`() {
    // SKILL-87 (F-001/F-002): a running child that emitted NO declared/observability event still
    // carries the row's own updated_at as an always-present backstop. SQLite stamps it as
    // "yyyy-MM-dd HH:mm:ss"; once parsed, an updated_at well past the 30-min window is positive
    // staleness evidence and the strand-forever path is closed.
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(
      runtimeCandidateRecordNoDeclaredEvent(
        "wftr-old-updatedat",
        updatedAt = outcomeStoreSqliteTimestamp(Instant.now().minus(2, ChronoUnit.HOURS)),
      ),
    )
    val store = testWorkflowGoalRunnerOutcomeStore(
      FakeDatabaseSessionFactory(workflows),
      testWorkflowSnapshotValidator,
    )

    val outcomes = store.reconcileAuthoritativeOutcomes(
      issueKey = "SKILL-87.1",
      activeWorkflowIds = emptySet(),
      gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
    )

    val outcome = requireNotNull(outcomes[1])
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals("wftr-old-updatedat", outcome.workflowId)
    val stale = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-old-updatedat")).toSnapshot()
    assertEquals("blocked", stale.workflowStatus)
  }

  @Test
  fun `evidence-based reconcile keeps a running subtask whose only liveness is a recent sqlite updatedAt`() {
    // SKILL-87 (F-001): the SQLite-format updated_at must parse and count as recent liveness when it
    // is within the window, so a quiet-but-alive child with no declared event is not false-killed.
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(
      runtimeCandidateRecordNoDeclaredEvent(
        "wftr-recent-updatedat",
        updatedAt = outcomeStoreSqliteTimestamp(Instant.now().minus(5, ChronoUnit.MINUTES)),
      ),
    )
    val store = testWorkflowGoalRunnerOutcomeStore(
      FakeDatabaseSessionFactory(workflows),
      testWorkflowSnapshotValidator,
    )

    val outcomes = store.reconcileAuthoritativeOutcomes(
      issueKey = "SKILL-87.1",
      activeWorkflowIds = emptySet(),
      gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
    )

    assertTrue(outcomes.isEmpty(), "a recent updated_at must keep the subtask out of a terminal outcome")
    val alive = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-recent-updatedat")).toSnapshot()
    assertEquals("running", alive.workflowStatus, "a recently-updated subtask must not be marked blocked")
  }

  @Test
  fun `evidence-based reconcile keeps a running subtask with genuinely empty liveness`() {
    // SKILL-87 (F-002): the no-evidence-at-all fallback — no declared/observability event AND no
    // parseable updated_at — biases to alive. This locks the defensive last-resort branch so a
    // regression that flips it to false-kill is caught.
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(
      runtimeCandidateRecordNoDeclaredEvent("wftr-empty-liveness", updatedAt = null),
    )
    val store = testWorkflowGoalRunnerOutcomeStore(
      FakeDatabaseSessionFactory(workflows),
      testWorkflowSnapshotValidator,
    )

    val outcomes = store.reconcileAuthoritativeOutcomes(
      issueKey = "SKILL-87.1",
      activeWorkflowIds = emptySet(),
      gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
    )

    assertTrue(outcomes.isEmpty(), "empty-liveness must bias to alive, not produce a terminal outcome")
    val alive = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-empty-liveness")).toSnapshot()
    assertEquals("running", alive.workflowStatus, "empty-liveness must not be marked blocked")
  }

  @Test
  fun `a crashed goal child with an expired lease and dead process reconciles to a resumable outcome`() {
    // AC-002: the goal-parent outcome store transitions a crashed child (running row, expired lease,
    // dead process) to a RECONCILABLE outcome so the parent keeps the subtask resumable instead of
    // emitting the terminal NO_TERMINAL_STORE_OUTCOME block.
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(crashedChildRecord("wftr-crashed-child"))
    workflows.seedWorkerOwnership(expiredLeaseOwnership("wftr-crashed-child"))
    val store = testWorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      workerSupervisor = DeadProcessSupervisor,
    )

    val outcome = store.recoverAndPersistTerminalOutcome(
      workflowId = "wftr-crashed-child",
      issueKey = "SKILL-87.1",
      subtaskId = 1,
      repoRoot = Path.of("."),
      dbPathOverride = null,
    )

    val reconciled = assertNotNull(outcome)
    assertEquals(GoalRunnerTerminalStatus.RECONCILABLE, reconciled.status)
    assertEquals("implement", reconciled.lastResumableStep)
    assertEquals(
      "pending",
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-crashed-child")).workflowStatus,
    )
    assertNull(workflows.getFeatureTaskRuntimeWorkerOwnership("wftr-crashed-child"))
  }

  @Test
  fun `operator resume reopens a running review phase left on a blocked child`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(tornBlockedReviewRecord("wftr-torn-review"))
    val store = testWorkflowGoalRunnerOutcomeStore(
      FakeDatabaseSessionFactory(workflows),
      testWorkflowSnapshotValidator,
    )

    assertTrue(
      store.reopenBlockedPhaseForOperatorResume(
        workflowId = "wftr-torn-review",
        preferredPhaseId = "review",
        reason = "Operator resumed the goal after a blocked stop at subtask 9.",
      ),
    )

    val updated = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-torn-review"))
    assertEquals("running", updated.workflowStatus)
    assertEquals("review", updated.currentStepId)
    val review = phaseRecordsFrom(decodeArtifacts(updated.artifactsJson))
      .getValue("review")
    assertEquals("pending", review.status)
  }

  @Test
  fun `a goal child with a live lease or live process is never reconciled and yields no outcome`() {
    // AC-003: a live lease (not expired) and a live process (expired lease but alive) both leave the
    // row untouched, returning no outcome so the existing terminal reasons stay intact.
    val liveLease = InMemoryWorkflowStates()
    liveLease.saveFeatureTaskRuntimeWorkflow(crashedChildRecord("wftr-live-lease"))
    liveLease.seedWorkerOwnership(expiredLeaseOwnership("wftr-live-lease", expiresAt = "2999-01-01T00:00:30Z"))
    val liveLeaseStore = testWorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(liveLease),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      workerSupervisor = DeadProcessSupervisor,
    )
    assertNull(
      liveLeaseStore.recoverAndPersistTerminalOutcome("wftr-live-lease", "SKILL-87.1", 1, Path.of("."), null),
    )
    assertEquals("running", requireNotNull(liveLease.getFeatureTaskRuntimeWorkflow("wftr-live-lease")).workflowStatus)
    assertNotNull(liveLease.getFeatureTaskRuntimeWorkerOwnership("wftr-live-lease"))

    val liveProcess = InMemoryWorkflowStates()
    liveProcess.saveFeatureTaskRuntimeWorkflow(crashedChildRecord("wftr-live-process"))
    liveProcess.seedWorkerOwnership(expiredLeaseOwnership("wftr-live-process"))
    val liveProcessStore = testWorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(liveProcess),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      workerSupervisor = LiveProcessSupervisor,
    )
    assertNull(
      liveProcessStore.recoverAndPersistTerminalOutcome("wftr-live-process", "SKILL-87.1", 1, Path.of("."), null),
    )
    assertEquals(
      "running",
      requireNotNull(liveProcess.getFeatureTaskRuntimeWorkflow("wftr-live-process")).workflowStatus,
    )
  }
}
