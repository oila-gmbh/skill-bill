package skillbill.application

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffFoundationValidator
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.phaseRecordsFrom
import skillbill.application.goalrunner.GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY
import skillbill.application.goalrunner.GoalRunnerStatusService
import skillbill.application.goalrunner.PASSED_CONTINUATION_OUTCOME
import skillbill.application.goalrunner.PASSED_REMEDIATION_BASE
import skillbill.application.goalrunner.PASSED_REVIEW_BASE
import skillbill.application.goalrunner.PASSED_UPSTREAM_OUTPUT
import skillbill.application.goalrunner.PASSED_VALIDATION_DEPTH
import skillbill.application.goalrunner.WorkflowGoalRunnerOutcomeStore
import skillbill.application.model.GoalRunnerRepairRequest
import skillbill.application.model.GoalRunnerRepairStatus
import skillbill.application.model.GoalRunnerWedgeClass
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.toRecord
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.GoalSubtaskReviewGitOperationsProvider
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ISSUE_KEY = "SKILL-176"
private const val GOAL_BRANCH = "feat/SKILL-176-goal-child-resume-self-heal"
private val REACHABLE_SHA = "c".repeat(40)
private val HEAD_SHA = "d".repeat(40)
private val COMPLETED_COMMIT = "e".repeat(40)

/**
 * SKILL-176 subtask 5: operator `goal repair` diagnosis, atomic mutation, evidence, and
 * orchestration preconditions. Wedge fixtures mirror SKILL-15 durable artifact shapes.
 */
internal class GoalRunnerRepairTest : GoalRunnerRepairFixtures() {
  @Test
  fun `diagnosis names missing validation_depth with absent current value`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-missing-depth"
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        workflowId = workflowId,
        continuation = continuationMap(includeValidationDepth = false),
        reviewState = healthyReviewState(),
      ),
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis = store.diagnoseChildWedges(
      workflowId = workflowId,
      issueKey = ISSUE_KEY,
      subtaskId = 1,
      subtasks = listOf(subtask(1, workflowId)),
      repoRoot = Path.of("."),
    )

    assertEquals(1, diagnosis.wedges.size)
    assertEquals(GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH, diagnosis.wedges.single().wedgeClass)
    assertEquals("validation_depth", diagnosis.wedges.single().field)
    assertNull(diagnosis.wedges.single().currentValue)
    assertFalse(PASSED_VALIDATION_DEPTH in diagnosis.passedChecks)
  }

  @Test
  fun `healthy child diagnosis names every check that passed`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-healthy"
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        workflowId = workflowId,
        continuation = continuationMap(includeValidationDepth = true),
        reviewState = healthyReviewState(),
      ),
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis = store.diagnoseChildWedges(
      workflowId = workflowId,
      issueKey = ISSUE_KEY,
      subtaskId = 1,
      subtasks = listOf(subtask(1, workflowId)),
      repoRoot = Path.of("."),
    )

    assertTrue(diagnosis.isHealthy)
    assertEquals(
      listOf(
        PASSED_VALIDATION_DEPTH,
        PASSED_REVIEW_BASE,
        PASSED_REMEDIATION_BASE,
        PASSED_CONTINUATION_OUTCOME,
        PASSED_UPSTREAM_OUTPUT,
      ),
      diagnosis.passedChecks,
    )
  }

  @Test
  fun `diagnosis names unreachable remediation base with the stored sha`() {
    val unreachable = "a".repeat(40)
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-unreachable-remediation"
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        workflowId = workflowId,
        continuation = continuationMap(includeValidationDepth = true),
        reviewState = healthyReviewState().copy(remediationBaseSha = unreachable),
      ),
    )
    val store = repairStore(workflows, git = ReachableGit(unreachableShas = setOf(unreachable)))

    val diagnosis = store.diagnoseChildWedges(
      workflowId = workflowId,
      issueKey = ISSUE_KEY,
      subtaskId = 1,
      subtasks = listOf(subtask(1, workflowId)),
      repoRoot = Path.of("."),
    )

    assertEquals(GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE, diagnosis.wedges.single().wedgeClass)
    assertEquals(unreachable, diagnosis.wedges.single().currentValue)
  }

  @Test
  fun `diagnosis names stale blocked goal_continuation_outcome with the stored reason`() {
    val staleReason = "Persisted review base was orphaned after history rewrite"
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-stale-outcome"
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        workflowId = workflowId,
        continuation = continuationMap(includeValidationDepth = true),
        reviewState = healthyReviewState(),
        workflowStatus = "running",
        goalContinuationOutcome = mapOf(
          "issue_key" to ISSUE_KEY,
          "subtask_id" to 1,
          "status" to "blocked",
          "workflow_id" to workflowId,
          "blocked_reason" to staleReason,
          "last_resumable_step" to "review",
        ),
      ),
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis = store.diagnoseChildWedges(
      workflowId = workflowId,
      issueKey = ISSUE_KEY,
      subtaskId = 1,
      subtasks = listOf(subtask(1, workflowId)),
      repoRoot = Path.of("."),
    )

    assertEquals(
      GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME,
      diagnosis.wedges.single().wedgeClass,
    )
    assertEquals(staleReason, diagnosis.wedges.single().currentValue)
  }

  @Test
  fun `a blocked step the fix loop moved past does not corroborate a stale blocked outcome`() {
    // The fix loop leaves the step it abandons marked blocked and advances. Deriving BLOCKED from
    // that historical step, then reading the derived reason back out of goal_continuation_outcome,
    // makes the stored outcome corroborate itself: the child reads terminal forever, repair reports
    // healthy, and every later goal run re-reports the stale reason without relaunching the child.
    val staleReason = "Feature-task-runtime phase 'review' governed evidence was never read"
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-abandoned-upstream-block"
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        workflowId = workflowId,
        continuation = continuationMap(includeValidationDepth = true),
        reviewState = healthyReviewState(),
        workflowStatus = "running",
        goalContinuationOutcome = mapOf(
          "issue_key" to ISSUE_KEY,
          "subtask_id" to 1,
          "status" to "blocked",
          "workflow_id" to workflowId,
          "blocked_reason" to staleReason,
          "last_resumable_step" to "review",
        ),
        abandonedBlockedStepId = "plan_fix",
      ),
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis = store.diagnoseChildWedges(
      workflowId = workflowId,
      issueKey = ISSUE_KEY,
      subtaskId = 1,
      subtasks = listOf(subtask(1, workflowId)),
      repoRoot = Path.of("."),
    )

    assertEquals(
      GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME,
      diagnosis.wedges.single().wedgeClass,
    )
    assertEquals(staleReason, diagnosis.wedges.single().currentValue)
  }

  @Test
  fun `diagnosis names completed upstream missing settled output`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-unsettled-upstream"
    val artifacts = linkedMapOf<String, Any?>(
      "goal_continuation" to continuationMap(includeValidationDepth = true),
      GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to healthyReviewState().toArtifactMap(),
      FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to mapOf(
        "plan_fix" to unsettledUpstreamPhaseRecord("plan_fix").toArtifactMap(),
        "implement_fix" to unsettledUpstreamPhaseRecord(
          phaseId = "implement_fix",
          status = "blocked",
          blockedReason = "Phase 'implement_fix' requires upstream output(s) plan_fix that are not present",
        ).toArtifactMap(),
      ),
    )
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, workflowId, "fis-repair", "implement_fix")
    workflows.saveFeatureTaskRuntimeWorkflow(
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          workflowStatus = "running",
          currentStepId = "implement_fix",
          stepUpdates = null,
          artifactsPatch = artifacts,
          sessionId = "ftr-repair",
        ),
      ).toRecord(),
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis = store.diagnoseChildWedges(
      workflowId = workflowId,
      issueKey = ISSUE_KEY,
      subtaskId = 1,
      subtasks = listOf(subtask(1, workflowId)),
      repoRoot = Path.of("."),
    )

    assertEquals(GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT, diagnosis.wedges.single().wedgeClass)
    assertEquals("plan_fix", diagnosis.wedges.single().field)
    assertFalse(PASSED_UPSTREAM_OUTPUT in diagnosis.passedChecks)
  }

  @Test
  fun `repairing completed upstream missing output reopens plan_fix and clears implement_fix block`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-unsettled-upstream"
    val artifacts = linkedMapOf<String, Any?>(
      "goal_continuation" to continuationMap(includeValidationDepth = true),
      GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to healthyReviewState().toArtifactMap(),
      FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to mapOf(
        "plan_fix" to unsettledUpstreamPhaseRecord("plan_fix").toArtifactMap(),
        "implement_fix" to unsettledUpstreamPhaseRecord(
          phaseId = "implement_fix",
          status = "blocked",
          blockedReason = "Phase 'implement_fix' requires upstream output(s) plan_fix that are not present",
        ).toArtifactMap(),
      ),
    )
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, workflowId, "fis-repair", "implement_fix")
    workflows.saveFeatureTaskRuntimeWorkflow(
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          workflowStatus = "blocked",
          currentStepId = "implement_fix",
          stepUpdates = null,
          artifactsPatch = artifacts,
          sessionId = "ftr-repair",
        ),
      ).toRecord(),
    )
    val store = repairStore(workflows, git = ReachableGit())

    val applied = store.applyChildWedgeRepairs(
      workflowId = workflowId,
      issueKey = ISSUE_KEY,
      subtaskId = 1,
      wedgeClasses = listOf(GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT),
      repoRoot = Path.of("."),
    )

    assertEquals(1, applied.repairs.size)
    assertEquals("plan_fix", applied.repairs.single().field)
    val updated = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId))
    assertEquals("running", updated.workflowStatus)
    assertEquals("plan_fix", updated.currentStepId)
    val records = phaseRecordsFrom(decodeArtifacts(updated.artifactsJson))
    assertEquals("pending", records.getValue("plan_fix").status)
    assertEquals("pending", records.getValue("implement_fix").status)
    val evidence = (decodeArtifacts(updated.artifactsJson)[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] as List<*>)
      .single() as Map<*, *>
    assertEquals("completed_upstream_missing_output", evidence["wedge_class"])
    assertEquals("plan_fix", evidence["field"])
  }
}

internal class GoalRunnerRepairContinuationTest : GoalRunnerRepairFixtures() {
  @Test
  fun `repairing completed upstream missing output with another wedge applies both repairs`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-upstream-and-depth"
    val artifacts = linkedMapOf<String, Any?>(
      "goal_continuation" to continuationMap(includeValidationDepth = false),
      GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to healthyReviewState().toArtifactMap(),
      FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to mapOf(
        "plan_fix" to unsettledUpstreamPhaseRecord("plan_fix").toArtifactMap(),
        "implement_fix" to unsettledUpstreamPhaseRecord(
          phaseId = "implement_fix",
          status = "blocked",
          blockedReason = "Phase 'implement_fix' requires upstream output(s) plan_fix that are not present",
        ).toArtifactMap(),
      ),
    )
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, workflowId, "fis-repair", "implement_fix")
    workflows.saveFeatureTaskRuntimeWorkflow(
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          workflowStatus = "blocked",
          currentStepId = "implement_fix",
          stepUpdates = null,
          artifactsPatch = artifacts,
          sessionId = "ftr-repair",
        ),
      ).toRecord(),
    )
    val store = repairStore(workflows, git = ReachableGit())
    val applied = store.applyChildWedgeRepairs(
      workflowId = workflowId,
      issueKey = ISSUE_KEY,
      subtaskId = 1,
      wedgeClasses = listOf(
        GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT,
        GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH,
      ),
      repoRoot = Path.of("."),
    )
    assertEquals(2, applied.repairs.size)
    assertEquals(
      setOf(GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT, GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH),
      applied.repairs.map { it.wedgeClass }.toSet(),
    )
    val updated = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId))
    assertEquals("running", updated.workflowStatus)
    assertEquals("plan_fix", updated.currentStepId)
    val after = decodeArtifacts(updated.artifactsJson)
    val continuation = after["goal_continuation"] as Map<*, *>
    assertEquals("full", continuation["validation_depth"])
    val records = phaseRecordsFrom(after)
    assertEquals("pending", records.getValue("plan_fix").status)
    assertEquals("pending", records.getValue("implement_fix").status)
    assertEquals(2, (after[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] as List<*>).size)
  }

  @Test
  fun `repairing missing validation_depth preserves review passes and stamps depth with evidence`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-depth"
    val review = GoalSubtaskReviewState.initial(
      reviewBaseSha = REACHABLE_SHA,
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = emptyList(),
    )
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        workflowId = workflowId,
        continuation = continuationMap(includeValidationDepth = false),
        reviewState = review,
        commitSha = COMPLETED_COMMIT,
      ),
    )
    val before = decodeArtifacts(
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson,
    )
    val store = repairStore(workflows, git = ReachableGit())

    val applied = store.applyChildWedgeRepairs(
      workflowId = workflowId,
      issueKey = ISSUE_KEY,
      subtaskId = 1,
      wedgeClasses = listOf(GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH),
      repoRoot = Path.of("."),
    )

    assertEquals(1, applied.repairs.size)
    assertEquals("full", applied.repairs.single().newValue)
    val after = decodeArtifacts(
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson,
    )
    assertEquals(COMPLETED_COMMIT, after["commit_sha"])
    assertEquals(
      before[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY],
      after[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY],
      "review pass results must survive validation_depth repair",
    )
    val continuation = after["goal_continuation"] as Map<*, *>
    assertEquals("full", continuation["validation_depth"])
    val evidence = (after[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] as List<*>).single() as Map<*, *>
    assertEquals("missing_validation_depth", evidence["wedge_class"])
    assertEquals("validation_depth", evidence["field"])
    assertNull(evidence["prior_value"])
    assertEquals("full", evidence["new_value"])
  }

  @Test
  fun `repairing stale blocked outcome removes the artifact and records evidence while preserving commit sha`() {
    val staleReason = "Persisted review base was orphaned after history rewrite"
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-stale"
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        workflowId = workflowId,
        continuation = continuationMap(includeValidationDepth = true),
        reviewState = healthyReviewState(),
        workflowStatus = "running",
        goalContinuationOutcome = mapOf(
          "issue_key" to ISSUE_KEY,
          "subtask_id" to 1,
          "status" to "blocked",
          "workflow_id" to workflowId,
          "blocked_reason" to staleReason,
          "last_resumable_step" to "review",
        ),
        commitSha = COMPLETED_COMMIT,
      ),
    )
    val beforeReview = decodeArtifacts(
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson,
    )[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY]
    val store = repairStore(workflows, git = ReachableGit())

    val applied = store.applyChildWedgeRepairs(
      workflowId = workflowId,
      issueKey = ISSUE_KEY,
      subtaskId = 1,
      wedgeClasses = listOf(GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME),
      repoRoot = Path.of("."),
    )

    assertEquals(1, applied.repairs.size)
    assertEquals(staleReason, applied.repairs.single().priorValue)
    assertNull(applied.repairs.single().newValue)
    val after = decodeArtifacts(
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson,
    )
    assertNull(after["goal_continuation_outcome"])
    assertEquals(COMPLETED_COMMIT, after["commit_sha"])
    assertEquals(beforeReview, after[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY])
    val evidence = (after[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] as List<*>).single() as Map<*, *>
    assertEquals("stale_blocked_continuation_outcome", evidence["wedge_class"])
    assertEquals(staleReason, evidence["prior_value"])
  }

  @Test
  fun `repairing unreachable remediation base repoints the sha and preserves completed review passes`() {
    val unreachable = "a".repeat(40)
    val recovered = "b".repeat(40)
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-remediation"
    val review = GoalSubtaskReviewState.initial(
      reviewBaseSha = REACHABLE_SHA,
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = emptyList(),
    ).copy(remediationBaseSha = unreachable)
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        workflowId = workflowId,
        continuation = continuationMap(includeValidationDepth = true),
        reviewState = review,
        commitSha = COMPLETED_COMMIT,
      ),
    )
    val store = repairStore(
      workflows,
      git = ReachableGit(unreachableShas = setOf(unreachable), recoveredSha = recovered),
    )

    val applied = store.applyChildWedgeRepairs(
      workflowId = workflowId,
      issueKey = ISSUE_KEY,
      subtaskId = 1,
      wedgeClasses = listOf(GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE),
      repoRoot = Path.of("."),
    )

    assertEquals(1, applied.repairs.size)
    assertEquals(unreachable, applied.repairs.single().priorValue)
    assertEquals(recovered, applied.repairs.single().newValue)
    val after = decodeArtifacts(
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson,
    )

    @Suppress("UNCHECKED_CAST")
    val state = GoalSubtaskReviewState.fromArtifactMap(
      after[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY] as Map<String, Any?>,
    )
    assertEquals(recovered, state.remediationBaseSha)
    assertEquals(1, state.completedPassCount)
    assertEquals(COMPLETED_COMMIT, after["commit_sha"])
  }

  @Test
  fun `mid-repair save failure leaves the durable row unchanged`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-atomicity"
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        workflowId = workflowId,
        continuation = continuationMap(includeValidationDepth = false),
        reviewState = healthyReviewState(),
        commitSha = COMPLETED_COMMIT,
      ),
    )
    val beforeJson = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson
    workflows.failSaveWhen = { row ->
      decodeArtifacts(row.artifactsJson).containsKey(GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY)
    }
    val store = repairStore(workflows, git = ReachableGit())

    val failed = runCatching {
      store.applyChildWedgeRepairs(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        wedgeClasses = listOf(GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH),
        repoRoot = Path.of("."),
      )
    }
    assertTrue(failed.isFailure)
    assertEquals(beforeJson, requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson)
  }

  @Test
  fun `live child worker lease refuses apply and writes nothing`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-live-lease"
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        workflowId = workflowId,
        continuation = continuationMap(includeValidationDepth = false),
        reviewState = healthyReviewState(),
      ),
    )
    workflows.seedWorkerOwnership(
      FeatureTaskRuntimeWorkerOwnership(
        workflowId = workflowId,
        generation = 1,
        ownerToken = "owner",
        hostIdentity = "host",
        bootIdentity = "boot",
        pid = 42,
        processBirthToken = "birth",
        leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
        heartbeatAt = "2999-01-01T00:00:00Z",
        expiresAt = "2999-01-01T00:01:00Z",
        phaseId = "implement",
        phaseAttempt = 1,
      ),
    )
    val beforeJson = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson
    val store = repairStore(workflows, git = ReachableGit())
    val database = FakeDatabaseSessionFactory(workflows)
    val phaseRecorder = FeatureTaskRuntimePhaseRecorder(
      database,
      testWorkflowSnapshotValidator,
      AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
      AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
    )
    val service = GoalRunnerStatusService(
      manifestStore = RepairManifestStore(workflowId),
      outcomeStore = store,
      phaseRecorder = phaseRecorder,
      workerSupervisor = LiveProcessSupervisor,
      childRepairStore = store,
    )

    val result = service.repair(
      GoalRunnerRepairRequest(issueKey = ISSUE_KEY, apply = true, repoRoot = Path.of(".")),
    )

    assertEquals(GoalRunnerRepairStatus.LIVE_LEASE_REFUSED, result.status)
    assertEquals(workflowId, result.liveLeaseWorkflowId)
    assertEquals(beforeJson, requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson)
  }

  @Test
  fun `healthy goal repair is a no-op that reports healthy without durable writes`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-healthy-goal"
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        workflowId = workflowId,
        continuation = continuationMap(includeValidationDepth = true),
        reviewState = healthyReviewState(),
      ),
    )
    val beforeJson = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson
    val store = repairStore(workflows, git = ReachableGit())
    val service = GoalRunnerStatusService(
      manifestStore = RepairManifestStore(workflowId),
      outcomeStore = store,
      phaseRecorder = goalTestPhaseRecorder(),
      childRepairStore = store,
    )

    val result = service.repair(
      GoalRunnerRepairRequest(issueKey = ISSUE_KEY, apply = true, repoRoot = Path.of(".")),
    )

    assertEquals(GoalRunnerRepairStatus.HEALTHY, result.status)
    assertEquals(beforeJson, requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson)
  }

  @Test
  fun `apply on a not-wedged child reports the passing checks instead of success`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-not-wedged"
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        workflowId = workflowId,
        continuation = continuationMap(includeValidationDepth = true),
        reviewState = healthyReviewState(),
      ),
    )
    val store = repairStore(workflows, git = ReachableGit())
    val service = GoalRunnerStatusService(
      manifestStore = RepairManifestStore(workflowId),
      outcomeStore = store,
      phaseRecorder = goalTestPhaseRecorder(),
      childRepairStore = store,
    )

    val result = service.repair(
      GoalRunnerRepairRequest(
        issueKey = ISSUE_KEY,
        apply = true,
        subtaskId = 1,
        repoRoot = Path.of("."),
      ),
    )

    assertEquals(GoalRunnerRepairStatus.NOT_WEDGED, result.status)
    assertNotNull(result.refusalReason)
    assertTrue(result.refusalReason.contains(PASSED_VALIDATION_DEPTH))
  }
}

internal abstract class GoalRunnerRepairFixtures {
  protected fun repairStore(
    workflows: InMemoryWorkflowStates,
    git: WorkflowGitOperations = NoopWorkflowGitOperations,
  ) = WorkflowGoalRunnerOutcomeStore(
    database = FakeDatabaseSessionFactory(workflows),
    workflowSnapshotValidator = testWorkflowSnapshotValidator,
    gitOperations = git,
  )

  @Suppress("LongParameterList")
  protected fun repairChildRecord(
    workflowId: String,
    continuation: Map<String, Any?>,
    reviewState: GoalSubtaskReviewState,
    workflowStatus: String = "running",
    goalContinuationOutcome: Map<String, Any?>? = null,
    commitSha: String? = null,
    abandonedBlockedStepId: String? = null,
  ): WorkflowStateRecord {
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, workflowId, "fis-repair", "preplan")
    val artifacts = linkedMapOf<String, Any?>(
      "goal_continuation" to continuation,
      GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to reviewState.toArtifactMap(),
    )
    if (reviewState.completedPassCount > 0) {
      artifacts[GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY] = reviewState.passResults.associate { result ->
        result.passNumber.toString() to "raw review result for pass ${result.passNumber}"
      }
    }
    goalContinuationOutcome?.let { artifacts["goal_continuation_outcome"] = it }
    commitSha?.let { artifacts["commit_sha"] = it }
    return engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = workflowStatus,
        currentStepId = "review",
        stepUpdates = buildList {
          abandonedBlockedStepId?.let { stepId ->
            add(mapOf("step_id" to stepId, "status" to "blocked", "attempt_count" to 13))
          }
          add(mapOf("step_id" to "review", "status" to "running", "attempt_count" to 1))
        },
        artifactsPatch = artifacts,
        sessionId = "ftr-repair",
      ),
    ).toRecord()
  }

  protected fun continuationMap(includeValidationDepth: Boolean): Map<String, Any?> =
    FeatureTaskRuntimeGoalContinuationArtifact(
      issueKey = ISSUE_KEY,
      subtaskId = 1,
      suppressPr = true,
      goalBranch = GOAL_BRANCH,
      parentWorkflowId = "wfl-parent",
      codeReviewMode = CodeReviewExecutionMode.INLINE,
      validationDepth = if (includeValidationDepth) ValidationDepth.FULL else null,
    ).toArtifactMap().let { map ->
      if (includeValidationDepth) map else map.filterKeys { it != "validation_depth" }
    }

  protected fun healthyReviewState(): GoalSubtaskReviewState = GoalSubtaskReviewState.initial(
    reviewBaseSha = REACHABLE_SHA,
    baselineUntrackedPaths = emptyList(),
    codeReviewMode = CodeReviewExecutionMode.INLINE,
  )

  protected fun subtask(id: Int, workflowId: String?) = DecompositionSubtask(
    id = id,
    name = "subtask-$id",
    specPath = ".feature-specs/$ISSUE_KEY/spec_subtask_$id.md",
    status = if (workflowId == null) "pending" else "in_progress",
    workflowId = workflowId,
  )

  protected fun unsettledUpstreamPhaseRecord(
    phaseId: String,
    status: String = "completed",
    blockedReason: String? = null,
  ): FeatureTaskRuntimePhaseRecord = FeatureTaskRuntimePhaseRecord(
    phaseId = phaseId,
    status = status,
    attemptCount = 1,
    startedAt = "2026-08-19T10:00:00Z",
    resolvedAgentId = "cursor",
    outputArtifact = null,
    blockedReason = blockedReason,
    loopId = "review_fix",
    edgeIteration = 1,
  )

  protected class RepairManifestStore(
    private val childWorkflowId: String,
  ) : GoalRunnerManifestStore {
    override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState =
      GoalRunnerManifestState(
        parentWorkflowId = "wfl-parent",
        dbPath = "/tmp/repair.db",
        manifest = DecompositionManifest(
          contractVersion = "0.5",
          issueKey = issueKey,
          featureName = "repair",
          parentSpecPath = ".feature-specs/$issueKey/spec.md",
          status = "in_progress",
          baseBranch = "main",
          featureBranch = "feat/$issueKey-repair",
          currentSubtaskIntent = CurrentSubtaskIntent(1, "resume"),
          subtasks = listOf(
            DecompositionSubtask(
              id = 1,
              name = "child",
              specPath = ".feature-specs/$issueKey/spec_subtask_1.md",
              status = "in_progress",
              workflowId = childWorkflowId,
            ),
          ),
        ),
        controlState = GoalRunnerControlState(),
        repoRoot = repoRoot,
      )

    override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState = state

    override fun acquireExecutionLease(
      parentWorkflowId: String,
      lease: GoalRunnerExecutionLease,
      expectedOwnerToken: String?,
      dbPathOverride: String?,
    ): Boolean = true

    override fun heartbeatExecutionLease(
      parentWorkflowId: String,
      lease: GoalRunnerExecutionLease,
      dbPathOverride: String?,
    ): Boolean = true

    override fun releaseExecutionLease(
      parentWorkflowId: String,
      ownerToken: String,
      generation: Long,
      dbPathOverride: String?,
    ): Boolean = true
  }

  protected class ReachableGit(
    private val unreachableShas: Set<String> = emptySet(),
    private val recoveredSha: String = "b".repeat(40),
  ) : WorkflowGitOperations by NoopWorkflowGitOperations, GoalSubtaskReviewGitOperationsProvider {
    override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
      WorkflowGitOperationResult(status = "ok", value = HEAD_SHA)

    override fun isCommitAncestor(
      repoRoot: Path,
      ancestorSha: String,
      descendantSha: String,
    ): WorkflowGitOperationResult = WorkflowGitOperationResult(
      status = "ok",
      value = if (ancestorSha in unreachableShas) "false" else "true",
    )

    override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations =
      object : GoalSubtaskReviewGitOperations {
        override fun captureBaseline(repoRoot: Path, expectedBranch: String): GoalSubtaskReviewBaselineResult =
          GoalSubtaskReviewBaselineResult(
            status = "ok",
            baseline = GoalSubtaskReviewBaseline(REACHABLE_SHA, emptyList()),
          )

        override fun buildInput(
          repoRoot: Path,
          baseline: GoalSubtaskReviewBaseline,
          expectedBranch: String,
        ): GoalSubtaskReviewInputResult = GoalSubtaskReviewInputResult(
          status = "ok",
          input = GoalSubtaskReviewInput(
            reviewBaseSha = baseline.reviewBaseSha,
            currentHeadSha = HEAD_SHA,
            trackedDelta = "",
            ownedUntrackedPatches = "",
          ),
        )

        override fun recoverBaseline(
          repoRoot: Path,
          request: GoalSubtaskReviewBaselineRecoveryRequest,
          expectedBranch: String,
        ): GoalSubtaskReviewBaselineResult = GoalSubtaskReviewBaselineResult(
          status = "ok",
          baseline = request.toRecoveredBaseline(recoveredSha),
        )
      }
  }

  protected object LiveProcessSupervisor : FeatureTaskRuntimeWorkerSupervisor {
    override fun currentProcess(): FeatureTaskRuntimeProcessIdentity =
      FeatureTaskRuntimeProcessIdentity("host", "boot", 1, "birth")

    override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = FeatureTaskRuntimeProcessInspection.ExactLive

    override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true

    override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true

    override fun pause(durationMillis: Long) = Unit

    override fun startHeartbeat(
      plan: FeatureTaskRuntimeHeartbeatPlan,
      heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
    ) = NoopFeatureTaskRuntimeHeartbeat
  }
}
