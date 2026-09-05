package skillbill.application

import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.decomposition.encodeDecompositionManifestMap
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffFoundationValidator
import skillbill.application.featuretask.featureTaskRuntimePhaseRecorder
import skillbill.application.goalrunner.GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY
import skillbill.application.goalrunner.GoalRunnerStatusTestPorts
import skillbill.application.goalrunner.OutcomeStoreTestArtifactPorts
import skillbill.application.goalrunner.PASSED_CONTINUATION_OUTCOME
import skillbill.application.goalrunner.PASSED_PHASE_OUTPUT_CONTRACT
import skillbill.application.goalrunner.PASSED_QUALITY_GATE_SELECTION
import skillbill.application.goalrunner.PASSED_REMEDIATION_BASE
import skillbill.application.goalrunner.PASSED_REVIEW_BASE
import skillbill.application.goalrunner.PASSED_UPSTREAM_OUTPUT
import skillbill.application.goalrunner.PASSED_VALIDATION_DEPTH
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeDiagnosisRequest
import skillbill.application.goalrunner.model.GoalRunnerChildWedgeRepairRequest
import skillbill.application.goalrunner.model.GoalRunnerRepairRequest
import skillbill.application.goalrunner.model.GoalRunnerRepairStatus
import skillbill.application.goalrunner.model.GoalRunnerWedgeClass
import skillbill.application.goalrunner.testGoalRunnerStatusService
import skillbill.application.goalrunner.testWorkflowGoalRunnerOutcomeStore
import skillbill.application.phaseartifacts.phaseRecordsFrom
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.application.workflow.toRecord
import skillbill.contracts.JsonCodec
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperationsProvider
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
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
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = false),
          reviewState = healthyReviewState(),
        ),
      ),
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis = store.diagnoseChildWedges(
      GoalRunnerChildWedgeDiagnosisRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        subtasks = listOf(subtask(1, workflowId)),
        repoRoot = Path.of("."),
      ),
    )

    assertEquals(1, diagnosis.wedges.size)
    assertEquals(GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH, diagnosis.wedges.single().wedgeClass)
    assertEquals("validation_depth", diagnosis.wedges.single().field)
    assertNull(diagnosis.wedges.single().currentValue)
    assertFalse(PASSED_VALIDATION_DEPTH in diagnosis.passedChecks)
  }

  @Test
  fun `diagnosis names missing quality_gate_selection with absent current value`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-missing-selection"
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true, includeQualityGateSelection = false),
          reviewState = healthyReviewState(),
        ),
      ),
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis = store.diagnoseChildWedges(
      GoalRunnerChildWedgeDiagnosisRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        subtasks = listOf(subtask(1, workflowId)),
        repoRoot = Path.of("."),
      ),
    )

    assertEquals(1, diagnosis.wedges.size)
    assertEquals(GoalRunnerWedgeClass.MISSING_QUALITY_GATE_SELECTION, diagnosis.wedges.single().wedgeClass)
    assertEquals("quality_gate_selection", diagnosis.wedges.single().field)
    assertNull(diagnosis.wedges.single().currentValue)
    assertFalse(PASSED_QUALITY_GATE_SELECTION in diagnosis.passedChecks)
  }

  @Test
  fun `healthy child diagnosis names every check that passed`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-healthy"
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState(),
        ),
      ),
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis = store.diagnoseChildWedges(
      GoalRunnerChildWedgeDiagnosisRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        subtasks = listOf(subtask(1, workflowId)),
        repoRoot = Path.of("."),
      ),
    )

    assertTrue(diagnosis.isHealthy)
    assertEquals(
      listOf(
        PASSED_VALIDATION_DEPTH,
        PASSED_QUALITY_GATE_SELECTION,
        PASSED_REVIEW_BASE,
        PASSED_REMEDIATION_BASE,
        PASSED_CONTINUATION_OUTCOME,
        PASSED_UPSTREAM_OUTPUT,
        PASSED_PHASE_OUTPUT_CONTRACT,
      ),
      diagnosis.passedChecks,
    )
  }

  @Test
  fun `diagnosis names phase output contract incompatibility and apply refuses hard reset`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-contract-version"
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState(),
          extraArtifacts = mapOf(
            "goal_planning_import" to mapOf(
              "phase_output_contract_version" to "0.3",
            ),
          ),
        ),
      ),
    )
    val store = repairStore(workflows, git = ReachableGit())
    val diagnosis = store.diagnoseChildWedges(
      GoalRunnerChildWedgeDiagnosisRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        subtasks = listOf(subtask(1, workflowId)),
        repoRoot = Path.of("."),
      ),
    )
    assertFalse(diagnosis.isHealthy)
    assertEquals(
      GoalRunnerWedgeClass.PHASE_OUTPUT_CONTRACT_INCOMPATIBLE,
      diagnosis.wedges.single().wedgeClass,
    )
    assertEquals("0.3", diagnosis.wedges.single().currentValue)

    val service = testGoalRunnerStatusService(
      manifestStore = RepairManifestStore(workflowId),
      outcomeStore = store,
      phaseRecorder = goalTestPhaseRecorder(),
      ports = GoalRunnerStatusTestPorts(
        childRepairStore = store,
      ),
    )
    val applied = service.repair(
      GoalRunnerRepairRequest(
        issueKey = ISSUE_KEY,
        apply = true,
        subtaskId = 1,
        repoRoot = Path.of("."),
      ),
    )
    assertEquals(GoalRunnerRepairStatus.OPERATOR_REQUIRED, applied.status)
    assertTrue(applied.appliedRepairs.isEmpty())
    assertContains(
      applied.refusalReason.orEmpty(),
      "skill-bill goal reset $ISSUE_KEY --hard --yes",
    )
  }

  @Test
  fun `diagnosis names unreachable remediation base with the stored sha`() {
    val unreachable = "a".repeat(40)
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-unreachable-remediation"
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState().copy(remediationBaseSha = unreachable),
        ),
      ),
    )
    val store = repairStore(workflows, git = ReachableGit(unreachableShas = setOf(unreachable)))

    val diagnosis = store.diagnoseChildWedges(
      GoalRunnerChildWedgeDiagnosisRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        subtasks = listOf(subtask(1, workflowId)),
        repoRoot = Path.of("."),
      ),
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
        RepairChildRecordArgs(
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
      ),
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis = store.diagnoseChildWedges(
      GoalRunnerChildWedgeDiagnosisRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        subtasks = listOf(subtask(1, workflowId)),
        repoRoot = Path.of("."),
      ),
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
        RepairChildRecordArgs(
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
          abandonedBlockedStepId = "implement_fix",
        ),
      ),
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis = store.diagnoseChildWedges(
      GoalRunnerChildWedgeDiagnosisRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        subtasks = listOf(subtask(1, workflowId)),
        repoRoot = Path.of("."),
      ),
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
        "review" to unsettledUpstreamPhaseRecord("review").toArtifactMap(),
        "verify_findings" to unsettledUpstreamPhaseRecord("verify_findings").toArtifactMap(),
        "implement_fix" to unsettledUpstreamPhaseRecord(
          phaseId = "implement_fix",
          status = "blocked",
          blockedReason = "Phase 'implement_fix' requires upstream output(s) verify_findings that are not present",
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
      GoalRunnerChildWedgeDiagnosisRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        subtasks = listOf(subtask(1, workflowId)),
        repoRoot = Path.of("."),
      ),
    )

    assertEquals(GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT, diagnosis.wedges.single().wedgeClass)
    assertEquals("verify_findings", diagnosis.wedges.single().field)
    assertFalse(PASSED_UPSTREAM_OUTPUT in diagnosis.passedChecks)
  }

  @Test
  fun `diagnosis names build not validate for build-stamped child missing settled build output`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-unsettled-build-upstream"
    val artifacts = linkedMapOf<String, Any?>(
      "goal_continuation" to FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        suppressPr = true,
        goalBranch = GOAL_BRANCH,
        parentWorkflowId = "wfl-parent",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
        validationDepth = ValidationDepth.FULL,
        qualityGateSelection = FeatureTaskRuntimeQualityGateSelection.BUILD,
      ).toArtifactMap(),
      GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to healthyReviewState().toArtifactMap(),
      FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to mapOf(
        "review" to unsettledUpstreamPhaseRecord("review", status = "completed").copy(
          outputArtifact = """{"contract_version":"0.1"}""",
        ).toArtifactMap(),
        "build" to unsettledUpstreamPhaseRecord("build").toArtifactMap(),
        "write_history" to unsettledUpstreamPhaseRecord(
          phaseId = "write_history",
          status = "blocked",
          blockedReason = "Phase 'write_history' requires upstream output(s) build that are not present",
        ).toArtifactMap(),
      ),
    )
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, workflowId, "fis-repair", "write_history")
    workflows.saveFeatureTaskRuntimeWorkflow(
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          workflowStatus = "running",
          currentStepId = "write_history",
          stepUpdates = null,
          artifactsPatch = artifacts,
          sessionId = "ftr-repair",
        ),
      ).toRecord(),
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis = store.diagnoseChildWedges(
      GoalRunnerChildWedgeDiagnosisRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        subtasks = listOf(subtask(1, workflowId)),
        repoRoot = Path.of("."),
      ),
    )

    assertEquals(GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT, diagnosis.wedges.single().wedgeClass)
    assertEquals("build", diagnosis.wedges.single().field)
    assertFalse(PASSED_UPSTREAM_OUTPUT in diagnosis.passedChecks)
  }

  @Test
  fun `repairing build-stamped completed upstream missing output reopens build not validate`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-unsettled-build-upstream"
    seedRepairParent(workflows, workflowId)
    val artifacts = unsettledBuildUpstreamArtifacts()
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, workflowId, "fis-repair", "write_history")
    workflows.saveFeatureTaskRuntimeWorkflow(
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          workflowStatus = "blocked",
          currentStepId = "write_history",
          stepUpdates = null,
          artifactsPatch = artifacts,
          sessionId = "ftr-repair",
        ),
      ).toRecord(),
    )
    val store = repairStore(workflows, git = ReachableGit())

    val applied = store.applyChildWedgeRepairs(
      GoalRunnerChildWedgeRepairRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        wedgeClasses = listOf(GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT),
        repoRoot = Path.of("."),
      ),
    )

    assertEquals(1, applied.repairs.size)
    assertEquals("build", applied.repairs.single().field)
    val updated = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId))
    assertEquals("running", updated.workflowStatus)
    assertEquals("build", updated.currentStepId)
    val records = phaseRecordsFrom(decodeArtifacts(updated.artifactsJson))
    assertEquals("pending", records.getValue("build").status)
    assertEquals("pending", records.getValue("write_history").status)
  }

  @Test
  fun `repairing completed upstream missing output reopens verify_findings and clears implement_fix block`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-unsettled-upstream"
    seedRepairParent(workflows, workflowId)
    val artifacts = linkedMapOf<String, Any?>(
      "goal_continuation" to continuationMap(includeValidationDepth = true),
      GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to healthyReviewState().toArtifactMap(),
      FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to mapOf(
        "review" to unsettledUpstreamPhaseRecord("review").toArtifactMap(),
        "verify_findings" to unsettledUpstreamPhaseRecord("verify_findings").toArtifactMap(),
        "implement_fix" to unsettledUpstreamPhaseRecord(
          phaseId = "implement_fix",
          status = "blocked",
          blockedReason = "Phase 'implement_fix' requires upstream output(s) verify_findings that are not present",
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
      GoalRunnerChildWedgeRepairRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        wedgeClasses = listOf(GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT),
        repoRoot = Path.of("."),
      ),
    )

    assertEquals(1, applied.repairs.size)
    assertEquals("verify_findings", applied.repairs.single().field)
    val updated = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId))
    assertEquals("running", updated.workflowStatus)
    assertEquals("verify_findings", updated.currentStepId)
    val records = phaseRecordsFrom(decodeArtifacts(updated.artifactsJson))
    assertEquals("pending", records.getValue("verify_findings").status)
    assertEquals("pending", records.getValue("implement_fix").status)
    val evidence = (decodeArtifacts(updated.artifactsJson)[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] as List<*>)
      .single() as Map<*, *>
    assertEquals("completed_upstream_missing_output", evidence["wedge_class"])
    assertEquals("verify_findings", evidence["field"])
  }
}

internal class GoalRunnerRepairContinuationTest : GoalRunnerRepairFixtures() {
  @Test
  fun `repairing completed upstream missing output with another wedge applies both repairs`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-upstream-and-depth"
    seedRepairParent(workflows, workflowId)
    saveBlockedUnsettledUpstreamChild(workflows, workflowId)
    val store = repairStore(workflows, git = ReachableGit())
    val applied = store.applyChildWedgeRepairs(
      GoalRunnerChildWedgeRepairRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        wedgeClasses = listOf(
          GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT,
          GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH,
        ),
        repoRoot = Path.of("."),
      ),
    )
    assertEquals(2, applied.repairs.size)
    assertEquals(
      setOf(GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT, GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH),
      applied.repairs.map { it.wedgeClass }.toSet(),
    )
    val updated = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId))
    assertEquals("running", updated.workflowStatus)
    assertEquals("verify_findings", updated.currentStepId)
    val after = decodeArtifacts(updated.artifactsJson)
    assertEquals("full", (after["goal_continuation"] as Map<*, *>)["validation_depth"])
    val records = phaseRecordsFrom(after)
    assertEquals("pending", records.getValue("verify_findings").status)
    assertEquals("pending", records.getValue("implement_fix").status)
    assertEquals(2, (after[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] as List<*>).size)
  }

  private fun saveBlockedUnsettledUpstreamChild(workflows: InMemoryWorkflowStates, workflowId: String) {
    val artifacts = linkedMapOf<String, Any?>(
      "goal_continuation" to continuationMap(includeValidationDepth = false),
      GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to healthyReviewState().toArtifactMap(),
      FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to mapOf(
        "review" to unsettledUpstreamPhaseRecord("review").toArtifactMap(),
        "verify_findings" to unsettledUpstreamPhaseRecord("verify_findings").toArtifactMap(),
        "implement_fix" to unsettledUpstreamPhaseRecord(
          phaseId = "implement_fix",
          status = "blocked",
          blockedReason =
          "Phase 'implement_fix' requires upstream output(s) verify_findings that are not present",
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
  }

  @Test
  fun `repairing missing quality_gate_selection stamps validate with evidence`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-selection"
    workflows.saveFeatureTaskRuntimeWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true, includeQualityGateSelection = false),
          reviewState = healthyReviewState(),
        ),
      ),
    )
    val store = repairStore(workflows, git = ReachableGit())

    val applied = store.applyChildWedgeRepairs(
      GoalRunnerChildWedgeRepairRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        wedgeClasses = listOf(GoalRunnerWedgeClass.MISSING_QUALITY_GATE_SELECTION),
        repoRoot = Path.of("."),
      ),
    )

    assertEquals(1, applied.repairs.size)
    assertEquals(GoalRunnerWedgeClass.MISSING_QUALITY_GATE_SELECTION, applied.repairs.single().wedgeClass)
    val after = decodeArtifacts(
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson,
    )
    val continuation = after["goal_continuation"] as Map<*, *>
    assertEquals("validate", continuation["quality_gate_selection"])
    val evidence = (after[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] as List<*>).single() as Map<*, *>
    assertEquals("missing_quality_gate_selection", evidence["wedge_class"])
    assertEquals("quality_gate_selection", evidence["field"])
    assertEquals("validate", evidence["new_value"])
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
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = false),
          reviewState = review,
          commitSha = COMPLETED_COMMIT,
        ),
      ),
    )
    val before = decodeArtifacts(
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson,
    )
    val store = repairStore(workflows, git = ReachableGit())

    val applied = store.applyChildWedgeRepairs(
      GoalRunnerChildWedgeRepairRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        wedgeClasses = listOf(GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH),
        repoRoot = Path.of("."),
      ),
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
        RepairChildRecordArgs(
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
      ),
    )
    val beforeReview = decodeArtifacts(
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson,
    )[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY]
    val store = repairStore(workflows, git = ReachableGit())

    val applied = store.applyChildWedgeRepairs(
      GoalRunnerChildWedgeRepairRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        wedgeClasses = listOf(GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME),
        repoRoot = Path.of("."),
      ),
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
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = review,
          commitSha = COMPLETED_COMMIT,
        ),
      ),
    )
    val store = repairStore(
      workflows,
      git = ReachableGit(unreachableShas = setOf(unreachable), recoveredSha = recovered),
    )

    val applied = store.applyChildWedgeRepairs(
      GoalRunnerChildWedgeRepairRequest(
        workflowId = workflowId,
        issueKey = ISSUE_KEY,
        subtaskId = 1,
        wedgeClasses = listOf(GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE),
        repoRoot = Path.of("."),
      ),
    )

    assertEquals(1, applied.repairs.size)
    assertEquals(unreachable, applied.repairs.single().priorValue)
    assertEquals(recovered, applied.repairs.single().newValue)
    val after = decodeArtifacts(
      requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson,
    )
    val state = GoalSubtaskReviewState.fromArtifactMap(
      requireNotNull(JsonCodec.anyToStringAnyMap(after[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY])),
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
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = false),
          reviewState = healthyReviewState(),
          commitSha = COMPLETED_COMMIT,
        ),
      ),
    )
    val beforeJson = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson
    workflows.failSaveWhen = { row ->
      decodeArtifacts(row.artifactsJson).containsKey(GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY)
    }
    val store = repairStore(workflows, git = ReachableGit())

    val failed = runCatching {
      store.applyChildWedgeRepairs(
        GoalRunnerChildWedgeRepairRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          wedgeClasses = listOf(GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH),
          repoRoot = Path.of("."),
        ),
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
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = false),
          reviewState = healthyReviewState(),
        ),
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
    val phaseRecorder = featureTaskRuntimePhaseRecorder(
      database,
      testWorkflowSnapshotValidator,
      AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
      AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
      testHarnessClock,
      NoopRuntimeDiagnostics,
    )
    val service = testGoalRunnerStatusService(
      manifestStore = RepairManifestStore(workflowId),
      outcomeStore = store,
      phaseRecorder = phaseRecorder,
      ports = GoalRunnerStatusTestPorts(
        workerSupervisor = LiveProcessSupervisor,
        childRepairStore = store,
      ),
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
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState(),
        ),
      ),
    )
    val beforeJson = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow(workflowId)).artifactsJson
    val store = repairStore(workflows, git = ReachableGit())
    val service = testGoalRunnerStatusService(
      manifestStore = RepairManifestStore(workflowId),
      outcomeStore = store,
      phaseRecorder = goalTestPhaseRecorder(),
      ports = GoalRunnerStatusTestPorts(
        childRepairStore = store,
      ),
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
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState(),
        ),
      ),
    )
    val store = repairStore(workflows, git = ReachableGit())
    val service = testGoalRunnerStatusService(
      manifestStore = RepairManifestStore(workflowId),
      outcomeStore = store,
      phaseRecorder = goalTestPhaseRecorder(),
      ports = GoalRunnerStatusTestPorts(
        childRepairStore = store,
      ),
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
    assertTrue(checkNotNull(result.refusalReason).contains(PASSED_VALIDATION_DEPTH))
  }
}

internal abstract class GoalRunnerRepairFixtures {

  protected fun seedRepairParent(workflows: InMemoryWorkflowStates, childWorkflowId: String) {
    val manifest = DecompositionManifest(
      contractVersion = "0.5",
      issueKey = ISSUE_KEY,
      featureName = "repair",
      parentSpecPath = ".feature-specs/$ISSUE_KEY/spec.md",
      status = "in_progress",
      baseBranch = "main",
      featureBranch = "feat/$ISSUE_KEY-repair",
      currentSubtaskIntent = CurrentSubtaskIntent(1, "resume"),
      subtasks = listOf(subtask(1, childWorkflowId)),
    )
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, "wfl-parent", "fis-repair-parent", "preplan")
    workflows.saveFeatureTaskRuntimeWorkflow(
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          workflowStatus = "running",
          currentStepId = "plan",
          stepUpdates = null,
          artifactsPatch = mapOf(
            DECOMPOSITION_RUNTIME_ARTIFACT_KEY to encodeDecompositionManifestMap(
              manifest,
              testDecompositionManifestValidator,
            ),
          ),
          sessionId = "ftr-repair-parent",
        ),
      ).toRecord().copy(issueKey = ISSUE_KEY),
    )
  }

  protected fun repairStore(
    workflows: InMemoryWorkflowStates,
    git: WorkflowGitOperations = NoopWorkflowGitOperations,
  ) = testWorkflowGoalRunnerOutcomeStore(
    FakeDatabaseSessionFactory(workflows),
    testWorkflowSnapshotValidator,
    gitOperations = git,
    artifactPorts = OutcomeStoreTestArtifactPorts(
      decompositionManifestStore = InMemoryRepairManifestFileStore(),
    ),
  )

  protected class InMemoryRepairManifestFileStore :
    DecompositionManifestStore by TestDecompositionManifestStore {
    private val files = linkedMapOf<Path, String>()

    override fun writeTextAtomically(target: Path, content: String) {
      files[target.toAbsolutePath().normalize()] = content
    }

    override fun readText(path: Path): String = files[path.toAbsolutePath().normalize()]
      ?: error("InMemoryRepairManifestFileStore has no content for $path")

    override fun isRegularFile(path: Path): Boolean = path.toAbsolutePath().normalize() in files

    override fun deleteIfExists(target: Path) {
      files.remove(target.toAbsolutePath().normalize())
    }
  }

  protected data class RepairChildRecordArgs(
    val workflowId: String,
    val continuation: Map<String, Any?>,
    val reviewState: GoalSubtaskReviewState,
    val workflowStatus: String = "running",
    val goalContinuationOutcome: Map<String, Any?>? = null,
    val commitSha: String? = null,
    val abandonedBlockedStepId: String? = null,
    val extraArtifacts: Map<String, Any?> = emptyMap(),
  )

  protected fun repairChildRecord(args: RepairChildRecordArgs): WorkflowStateRecord {
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, args.workflowId, "fis-repair", "preplan")
    val artifacts = linkedMapOf<String, Any?>(
      "goal_continuation" to args.continuation,
      GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to args.reviewState.toArtifactMap(),
    )
    if (args.reviewState.completedPassCount > 0) {
      artifacts[GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY] = args.reviewState.passResults.associate { result ->
        result.passNumber.toString() to "raw review result for pass ${result.passNumber}"
      }
    }
    args.goalContinuationOutcome?.let { artifacts["goal_continuation_outcome"] = it }
    args.commitSha?.let { artifacts["commit_sha"] = it }
    artifacts.putAll(args.extraArtifacts)
    val currentStepId = if (args.abandonedBlockedStepId == "implement_fix") "validate" else "review"
    return engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = args.workflowStatus,
        currentStepId = currentStepId,
        stepUpdates = buildList {
          args.abandonedBlockedStepId?.let { stepId ->
            add(mapOf("step_id" to stepId, "status" to "blocked", "attempt_count" to 13))
          }
          add(mapOf("step_id" to currentStepId, "status" to "running", "attempt_count" to 1))
        },
        artifactsPatch = artifacts,
        sessionId = "ftr-repair",
      ),
    ).toRecord()
  }

  protected fun continuationMap(
    includeValidationDepth: Boolean,
    includeQualityGateSelection: Boolean = true,
  ): Map<String, Any?> = FeatureTaskRuntimeGoalContinuationArtifact(
    issueKey = ISSUE_KEY,
    subtaskId = 1,
    suppressPr = true,
    goalBranch = GOAL_BRANCH,
    parentWorkflowId = "wfl-parent",
    codeReviewMode = CodeReviewExecutionMode.INLINE,
    validationDepth = if (includeValidationDepth) ValidationDepth.FULL else null,
    qualityGateSelection = if (includeQualityGateSelection) {
      FeatureTaskRuntimeQualityGateSelection.VALIDATE
    } else {
      null
    },
  ).toArtifactMap().let { map ->
    buildMap {
      putAll(map)
      if (!includeValidationDepth) remove("validation_depth")
      if (!includeQualityGateSelection) remove("quality_gate_selection")
    }
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

  protected fun unsettledBuildUpstreamArtifacts(): LinkedHashMap<String, Any?> = linkedMapOf(
    "goal_continuation" to FeatureTaskRuntimeGoalContinuationArtifact(
      issueKey = ISSUE_KEY,
      subtaskId = 1,
      suppressPr = true,
      goalBranch = GOAL_BRANCH,
      parentWorkflowId = "wfl-parent",
      codeReviewMode = CodeReviewExecutionMode.INLINE,
      validationDepth = ValidationDepth.FULL,
      qualityGateSelection = FeatureTaskRuntimeQualityGateSelection.BUILD,
    ).toArtifactMap(),
    GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to healthyReviewState().toArtifactMap(),
    FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to mapOf(
      "review" to unsettledUpstreamPhaseRecord("review", status = "completed").copy(
        outputArtifact = """{"contract_version":"0.1"}""",
      ).toArtifactMap(),
      "build" to unsettledUpstreamPhaseRecord("build").toArtifactMap(),
      "write_history" to unsettledUpstreamPhaseRecord(
        phaseId = "write_history",
        status = "blocked",
        blockedReason = "Phase 'write_history' requires upstream output(s) build that are not present",
      ).toArtifactMap(),
    ),
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

    override fun awaitExit(ownership: FeatureTaskRuntimeWorkerOwnership, timeout: Duration) = Unit

    override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true

    override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true

    override fun pause(durationMillis: Long) = Unit

    override fun startHeartbeat(
      plan: FeatureTaskRuntimeHeartbeatPlan,
      heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
    ) = NoopFeatureTaskRuntimeHeartbeat
  }
}
