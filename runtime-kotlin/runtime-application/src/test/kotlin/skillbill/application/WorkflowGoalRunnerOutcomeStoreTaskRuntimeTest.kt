package skillbill.application

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.goalrunner.WorkflowGoalRunnerOutcomeStore
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.toRecord
import skillbill.application.workflow.toSnapshot
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalAttemptLedgerEntry
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestRejectionReason
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import WorkflowStateRecord
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.goal.model.GoalProgressEvent
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.application.featuretask.phaseRecordsFrom

@Suppress("LargeClass") // outcome-store task-runtime scenarios; each test is an independent fixture
class WorkflowGoalRunnerOutcomeStoreTaskRuntimeTest {
  @Test
  fun `reads progress from task runtime workflows without probing prose mode`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(taskRuntimeWorkflowRecord("wftr-task-runtime"))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
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
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
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
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
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
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      phaseOutputValidator = AlwaysValidValidator,
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
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
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
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
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
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
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
        updatedAt = sqliteTimestamp(Instant.now().minus(2, ChronoUnit.HOURS)),
      ),
    )
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
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
        updatedAt = sqliteTimestamp(Instant.now().minus(5, ChronoUnit.MINUTES)),
      ),
    )
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
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
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
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
    val store = WorkflowGoalRunnerOutcomeStore(
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
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
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
    val liveLeaseStore = WorkflowGoalRunnerOutcomeStore(
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
    val liveProcessStore = WorkflowGoalRunnerOutcomeStore(
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

  @Test
  fun `stored blocked outcome with standing durable cause is returned with reason text byte-identical`() {
    // Bug this catches: a fix that always releases stored blocked outcomes would regress standing
    // causes (AC-003). Corroboration must keep a still-valid blocked artifact authoritative.
    val reason = "Review requested changes that remain unresolved."
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(
      blockedContinuationRecord(
        workflowId = "wftr-standing-block",
        workflowStatus = "blocked",
        stepStatus = "blocked",
        blockedReasonArtifact = reason,
        storedBlockedReason = reason,
      ),
    )
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val outcome = requireNotNull(store.terminalOutcome("wftr-standing-block", "SKILL-176.4", 4))

    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals(reason, outcome.blockedReason)
  }

  @Test
  fun `standing blocked outcome with only goal_continuation_outcome reason stays authoritative`() {
    // Bug this catches (F-001): blockedReasonFrom read only top-level artifacts["blocked_reason"],
    // so the normal persistGoalContinuationOutcome shape (reason nested only) failed corroboration
    // and displaced a still-blocked child on transactional resume (AC-003).
    val reason = "Review requested changes that remain unresolved."
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(
      blockedContinuationRecord(
        workflowId = "wftr-standing-nested-reason",
        workflowStatus = "blocked",
        stepStatus = "blocked",
        blockedReasonArtifact = null,
        storedBlockedReason = reason,
      ),
    )
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val readOnly = requireNotNull(store.terminalOutcome("wftr-standing-nested-reason", "SKILL-176.4", 4))
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, readOnly.status)
    assertEquals(reason, readOnly.blockedReason)

    val recovered = requireNotNull(
      store.recoverAndPersistTerminalOutcome(
        workflowId = "wftr-standing-nested-reason",
        issueKey = "SKILL-176.4",
        subtaskId = 4,
        repoRoot = Path.of("."),
        dbPathOverride = null,
      ),
    )
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, recovered.status)
    assertEquals(reason, recovered.blockedReason)
    val artifacts = decodeArtifacts(
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-standing-nested-reason")).artifactsJson,
    )
    assertNull(artifacts["goal_continuation_outcome_displacement"])
    assertEquals(reason, (artifacts["goal_continuation_outcome"] as Map<*, *>)["blocked_reason"])
  }

  @Test
  fun `stored blocked outcome whose cause is gone falls through instead of replaying the stale reason`() {
    // Bug this catches: terminalOutcomeFor short-circuited on any stored blocked outcome, so a resume
    // replayed remediation from a deleted code path (SKILL-15 wedge). The seeded reason string is
    // grep-absent from current runtime sources.
    val staleReason =
      "Owned paths already staged outside this workflow; run git restore --staged and retry."
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(
      blockedContinuationRecord(
        workflowId = "wftr-20260808-175505-c5po",
        workflowStatus = "running",
        stepStatus = "running",
        blockedReasonArtifact = null,
        storedBlockedReason = staleReason,
      ),
    )
    workflows.seedWorkerOwnership(expiredLeaseOwnership("wftr-20260808-175505-c5po"))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      workerSupervisor = DeadProcessSupervisor,
    )

    val readOnly = store.terminalOutcome("wftr-20260808-175505-c5po", "SKILL-176.4", 4)
    assertTrue(
      readOnly == null || readOnly.blockedReason != staleReason,
      "read path must not replay the stale blocked reason; got $readOnly",
    )

    val recovered = requireNotNull(
      store.recoverAndPersistTerminalOutcome(
        workflowId = "wftr-20260808-175505-c5po",
        issueKey = "SKILL-176.4",
        subtaskId = 4,
        repoRoot = Path.of("."),
        dbPathOverride = null,
      ),
    )
    assertEquals(GoalRunnerTerminalStatus.RECONCILABLE, recovered.status)
    assertTrue(recovered.blockedReason != staleReason)

    val artifacts = decodeArtifacts(
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-20260808-175505-c5po")).artifactsJson,
    )
    val displacement = artifacts["goal_continuation_outcome_displacement"] as Map<*, *>
    assertEquals(staleReason, displacement["original_blocked_reason"])
    assertNull(artifacts["goal_continuation_outcome"])
  }

  @Test
  fun `displacing a stale blocked outcome is idempotent across a second resume`() {
    // Bug this catches: repeated resumes duplicating displacement evidence, or reconcile alternating
    // between released and re-blocked (AC-005/AC-007).
    val staleReason =
      "Owned paths already staged outside this workflow; run git restore --staged and retry."
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(
      blockedContinuationRecord(
        workflowId = "wftr-stale-idempotent",
        workflowStatus = "running",
        stepStatus = "running",
        blockedReasonArtifact = null,
        storedBlockedReason = staleReason,
        declaredProgressTimestamp = Instant.now(),
      ),
    )
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val first = store.reconcileAuthoritativeOutcomes(
      issueKey = "SKILL-176.4",
      activeWorkflowIds = setOf("wftr-stale-idempotent"),
      gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
    )
    val artifactsAfterFirst = decodeArtifacts(
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-stale-idempotent")).artifactsJson,
    )
    assertEquals(
      staleReason,
      (artifactsAfterFirst["goal_continuation_outcome_displacement"] as Map<*, *>)["original_blocked_reason"],
    )
    assertNull(artifactsAfterFirst["goal_continuation_outcome"])
    assertEquals(
      "running",
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-stale-idempotent")).workflowStatus,
    )

    val second = store.reconcileAuthoritativeOutcomes(
      issueKey = "SKILL-176.4",
      activeWorkflowIds = setOf("wftr-stale-idempotent"),
      gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
    )
    assertEquals(first, second)
    val artifactsAfterSecond = decodeArtifacts(
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-stale-idempotent")).artifactsJson,
    )
    assertEquals(
      artifactsAfterFirst["goal_continuation_outcome_displacement"],
      artifactsAfterSecond["goal_continuation_outcome_displacement"],
    )
    assertEquals(
      "running",
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wftr-stale-idempotent")).workflowStatus,
    )
  }

  @Test
  fun `COMPLETE without sha still falls through to the measure branch alongside corroboration`() {
    // Bug this catches: the new non-complete corroboration replacing the SKILL-68 COMPLETE-without-sha
    // takeUnless (AC-006). A SHA-less complete must still reach measured recovery.
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(completeWithoutShaContinuationRecord("wftr-complete-no-sha"))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      gitOperations = MeasuringHeadShaGitOperations,
    )

    val readOnly = requireNotNull(store.terminalOutcome("wftr-complete-no-sha", "SKILL-176.4", 4))
    assertEquals(GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME, readOnly.status)
    assertNull(readOnly.commitSha)

    val recovered = requireNotNull(
      store.recoverAndPersistTerminalOutcome(
        workflowId = "wftr-complete-no-sha",
        issueKey = "SKILL-176.4",
        subtaskId = 4,
        repoRoot = Path.of("."),
        dbPathOverride = null,
      ),
    )
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, recovered.status)
    assertEquals("measured-head-sha", recovered.commitSha)
  }

  // Mirrors the production SQLite CURRENT_TIMESTAMP shape ("yyyy-MM-dd HH:mm:ss", UTC, no 'T'/zone).
  private fun sqliteTimestamp(instant: Instant): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneOffset.UTC)
    .format(instant.truncatedTo(ChronoUnit.SECONDS))

  @Suppress("LongParameterList") // mirrors blocked continuation fixture fields varied per case
  private fun blockedContinuationRecord(
    workflowId: String,
    workflowStatus: String,
    stepStatus: String,
    blockedReasonArtifact: String?,
    storedBlockedReason: String,
    declaredProgressTimestamp: Instant? = null,
  ): WorkflowStateRecord {
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, workflowId, "fis-176", "preplan")
    val artifacts = linkedMapOf<String, Any?>(
      "goal_continuation" to mapOf(
        "issue_key" to "SKILL-176.4",
        "subtask_id" to 4,
        "suppress_pr" to true,
      ),
      "goal_continuation_outcome" to mapOf(
        "issue_key" to "SKILL-176.4",
        "subtask_id" to 4,
        "status" to "blocked",
        "workflow_id" to workflowId,
        "blocked_reason" to storedBlockedReason,
        "last_resumable_step" to "review",
      ),
    )
    if (blockedReasonArtifact != null) {
      artifacts["blocked_reason"] = blockedReasonArtifact
    }
    if (declaredProgressTimestamp != null) {
      artifacts["goal_progress_latest_event"] = GoalProgressEvent(
        eventKind = GoalProgressEventKind.OPERATION_HEARTBEAT,
        workflowId = workflowId,
        workflowPhase = "goal_runner_supervision",
        processAlive = true,
        sequenceNumber = 1,
        timestamp = declaredProgressTimestamp.toString(),
        operationName = "child_agent_run",
        operationKind = "long_child_run",
        expectedLong = true,
      ).toArtifactMap()
    }
    return engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = workflowStatus,
        currentStepId = "review",
        stepUpdates = listOf(
          mapOf("step_id" to "review", "status" to stepStatus, "attempt_count" to 1),
        ),
        artifactsPatch = artifacts,
        sessionId = "ftr-176",
      ),
    ).toRecord()
  }

  private fun completeWithoutShaContinuationRecord(workflowId: String): WorkflowStateRecord {
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, workflowId, "fis-176", "preplan")
    return engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "commit_push",
        stepUpdates = listOf(
          mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-176.4",
            "subtask_id" to 4,
            "suppress_pr" to true,
          ),
          "goal_continuation_outcome" to mapOf(
            "issue_key" to "SKILL-176.4",
            "subtask_id" to 4,
            "status" to "complete",
            "workflow_id" to workflowId,
            "last_resumable_step" to "commit_push",
          ),
        ),
        sessionId = "ftr-176",
      ),
    ).toRecord()
  }

  private fun runtimeCandidateRecordNoDeclaredEvent(
    workflowId: String,
    updatedAt: String?,
  ): WorkflowStateRecord {
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
    return engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = listOf(
          mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-87.1",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
        ),
        sessionId = "ftr-001",
      ),
    ).toRecord().copy(updatedAt = updatedAt)
  }

  private fun goalReviewWorkflowRecord(
    workflowId: String,
    state: GoalSubtaskReviewState,
    rawReviewResult: String,
  ): WorkflowStateRecord {
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
    return engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "review",
        stepUpdates = null,
        artifactsPatch = mapOf(
          FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to FeatureTaskRuntimeGoalContinuationArtifact(
            issueKey = "SKILL-119",
            subtaskId = 2,
            suppressPr = true,
            goalBranch = "feat/SKILL-119",
            codeReviewMode = CodeReviewExecutionMode.AUTO,
          ).toArtifactMap(),
          GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to state.toArtifactMap(),
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to mapOf("1" to rawReviewResult),
        ),
        sessionId = "ftr-001",
      ),
    ).toRecord()
  }

  private fun runtimeCandidateRecord(
    workflowId: String,
    declaredProgressTimestamp: Instant,
  ): WorkflowStateRecord {
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
    val declaredEvent = GoalProgressEvent(
      eventKind = GoalProgressEventKind.OPERATION_HEARTBEAT,
      workflowId = workflowId,
      workflowPhase = "goal_runner_supervision",
      processAlive = true,
      sequenceNumber = 1,
      timestamp = declaredProgressTimestamp.toString(),
      operationName = "child_agent_run",
      operationKind = "long_child_run",
      expectedLong = true,
    )
    return engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = listOf(
          mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
        ),
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-87.1",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
          "goal_progress_latest_event" to declaredEvent.toArtifactMap(),
        ),
        sessionId = "ftr-001",
      ),
    ).toRecord()
  }

  private fun taskRuntimeWorkflowRecord(workflowId: String): WorkflowStateRecord {
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
    return engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = null,
        artifactsPatch = emptyMap(),
        sessionId = "ftr-001",
      ),
    ).toRecord()
  }

  private fun decodeArtifacts(artifactsJson: String): Map<String, Any?> {
    val element = JsonSupport.json.parseToJsonElement(artifactsJson)
    return requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(element)))
  }

  private fun tornBlockedReviewRecord(workflowId: String): WorkflowStateRecord {
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
    val reviewRecord = FeatureTaskRuntimePhaseRecord(
      phaseId = "review",
      status = "running",
      attemptCount = 2,
      startedAt = "2026-08-15T09:17:42Z",
      resolvedAgentId = "cursor",
    )
    return engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = "blocked",
        currentStepId = "review",
        stepUpdates = listOf(
          mapOf("step_id" to "review", "status" to "blocked", "attempt_count" to 2),
        ),
        artifactsPatch = mapOf(
          FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to mapOf(
            "review" to reviewRecord.toArtifactMap(),
          ),
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-191",
            "subtask_id" to 9,
            "suppress_pr" to true,
          ),
        ),
        sessionId = "ftr-001",
      ),
    ).toRecord()
  }

  private fun crashedChildRecord(workflowId: String): WorkflowStateRecord {
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
    return engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = null,
        artifactsPatch = mapOf(
          "goal_continuation" to mapOf(
            "issue_key" to "SKILL-87.1",
            "subtask_id" to 1,
            "suppress_pr" to true,
          ),
        ),
        sessionId = "ftr-001",
      ),
    ).toRecord()
  }

  private fun expiredLeaseOwnership(workflowId: String, expiresAt: String = "2000-01-01T00:00:30Z") =
    FeatureTaskRuntimeWorkerOwnership(
      workflowId = workflowId,
      generation = 1,
      ownerToken = "crashed-owner-$workflowId",
      hostIdentity = "host",
      bootIdentity = "boot",
      pid = 9,
      processBirthToken = "birth-9",
      leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
      heartbeatAt = "2000-01-01T00:00:00Z",
      expiresAt = expiresAt,
      phaseId = "implement",
      phaseAttempt = 1,
    )
}

private object DeadProcessSupervisor : FeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess() = FeatureTaskRuntimeProcessIdentity("host", "boot", 9, "birth-9")
  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = FeatureTaskRuntimeProcessInspection.NotRunning
  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true
  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true
  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ) = NoopFeatureTaskRuntimeHeartbeat
  override fun pause(durationMillis: Long) = Unit
}

private object LiveProcessSupervisor : FeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess() = FeatureTaskRuntimeProcessIdentity("host", "boot", 9, "birth-9")
  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = FeatureTaskRuntimeProcessInspection.ExactLive
  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true
  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true
  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ) = NoopFeatureTaskRuntimeHeartbeat
  override fun pause(durationMillis: Long) = Unit
}

private object MeasuringHeadShaGitOperations : WorkflowGitOperations by NoopWorkflowGitOperations {
  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "measured-head-sha")
}
