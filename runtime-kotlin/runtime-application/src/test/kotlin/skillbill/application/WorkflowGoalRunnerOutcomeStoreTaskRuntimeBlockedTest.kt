package skillbill.application

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.phaseRecordsFrom
import skillbill.application.goalrunner.outcomeStoreDeps
import skillbill.application.goalrunner.testWorkflowGoalRunnerOutcomeStore
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
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalProgressEvent
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
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

class WorkflowGoalRunnerOutcomeStoreTaskRuntimeBlockedTest {
  @Test
  fun `stored blocked outcome with standing durable cause is returned with reason text byte-identical`() {
            val reason = "Review requested changes that remain unresolved."
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(
      blockedContinuationRecord(
        BlockedContinuationRecordFixture(
        workflowId = "wftr-standing-block",
        workflowStatus = "blocked",
        stepStatus = "blocked",
        blockedReasonArtifact = reason,
        storedBlockedReason = reason,
        ),
      ),
    )
    val store = testWorkflowGoalRunnerOutcomeStore(
      outcomeStoreDeps(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      ),
    )

    val outcome = requireNotNull(store.terminalOutcome("wftr-standing-block", "SKILL-176.4", 4))

    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals(reason, outcome.blockedReason)
  }

  @Test
  fun `standing blocked outcome with only goal_continuation_outcome reason stays authoritative`() {
                val reason = "Review requested changes that remain unresolved."
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(
      blockedContinuationRecord(
        BlockedContinuationRecordFixture(
        workflowId = "wftr-standing-nested-reason",
        workflowStatus = "blocked",
        stepStatus = "blocked",
        blockedReasonArtifact = null,
        storedBlockedReason = reason,
        ),
      ),
    )
    val store = testWorkflowGoalRunnerOutcomeStore(
      outcomeStoreDeps(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      ),
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
                val staleReason =
      "Owned paths already staged outside this workflow; run git restore --staged and retry."
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(
      blockedContinuationRecord(
        BlockedContinuationRecordFixture(
        workflowId = "wftr-20260808-175505-c5po",
        workflowStatus = "running",
        stepStatus = "running",
        blockedReasonArtifact = null,
        storedBlockedReason = staleReason,
        ),
      ),
    )
    workflows.seedWorkerOwnership(expiredLeaseOwnership("wftr-20260808-175505-c5po"))
    val store = testWorkflowGoalRunnerOutcomeStore(
      outcomeStoreDeps(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
      ).copy(
        workerSupervisor = DeadProcessSupervisor,
      ),
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
            val staleReason =
      "Owned paths already staged outside this workflow; run git restore --staged and retry."
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(
      blockedContinuationRecord(
        BlockedContinuationRecordFixture(
        workflowId = "wftr-stale-idempotent",
        workflowStatus = "running",
        stepStatus = "running",
        blockedReasonArtifact = null,
        storedBlockedReason = staleReason,
        declaredProgressTimestamp = Instant.now(),
        ),
      ),
    )
    val store = testWorkflowGoalRunnerOutcomeStore(
      outcomeStoreDeps(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      ),
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
            val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(completeWithoutShaContinuationRecord("wftr-complete-no-sha"))
    val store = testWorkflowGoalRunnerOutcomeStore(
      outcomeStoreDeps(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
      ).copy(
        gitOperations = MeasuringHeadShaGitOperations,
      ),
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

}
