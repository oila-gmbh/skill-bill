package skillbill.application.featuretask

import skillbill.application.InMemoryRuntimeWorkflowRepository
import skillbill.application.RuntimeFakeDatabaseSessionFactory
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.toRecord
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_MAX_PASSES
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDispositionVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.model.GoalSubtaskPauseRelease
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AC-014 and AC-016: the pause data is only useful if it survives the process that wrote it. These
 * drive the real recorder against a workflow store and read the state back, rather than asserting on
 * objects the test constructs itself.
 */
class GoalSubtaskReviewStateDurablePersistenceTest {
  private val workflowId = "wftr-skill142-1"

  @Test
  fun `an operator decision and its consumption survive a reload`() {
    val recorder = recorderWith(pausedState())

    val decided = recorder.updateReviewState(workflowId) {
      it.applyOperatorDecision(GoalSubtaskOperatorDecision.RETRY_FIX)
    }
    assertNotNull(decided)
    assertEquals(
      GoalSubtaskPauseRelease.RETRY_FIX,
      recorder.reviewState(workflowId)?.pauseRelease,
      "A resumed run must read the grant back off durable state.",
    )

    recorder.updateReviewState(workflowId) { it.consumeOperatorDecision() }
    val afterConsume = assertNotNull(recorder.reviewState(workflowId))
    assertNull(
      afterConsume.operatorDecision,
      "A consumed grant must be cleared durably; otherwise every resume re-grants an unbudgeted iteration.",
    )
    assertTrue(afterConsume.retryReviewPending, "The granted round is in flight until it is re-reviewed.")
  }

  @Test
  fun `accept_and_advance and abandon_subtask are readable releases, not write-only records`() {
    listOf(
      GoalSubtaskOperatorDecision.ACCEPT_AND_ADVANCE to GoalSubtaskPauseRelease.ADVANCE,
      GoalSubtaskOperatorDecision.ABANDON_SUBTASK to GoalSubtaskPauseRelease.ABANDON,
    ).forEach { (decision, expected) ->
      val recorder = recorderWith(pausedState())
      recorder.updateReviewState(workflowId) { it.applyOperatorDecision(decision) }
      assertEquals(
        expected,
        recorder.reviewState(workflowId)?.pauseRelease,
        "${decision.wireValue} must release the pause it answers.",
      )
    }
  }

  @Test
  fun `the resolved tier, deciding rule, and remediation base sha all survive a reload`() {
    val recorder = recorderWith(pausedState())

    recorder.updateReviewState(workflowId) {
      it.copy(
        resolvedTier = CodeReviewExecutionMode.INLINE,
        decidingRule = "auto_mode_by_pass_number:pass_n_inline",
      )
    }
    recorder.updateReviewState(workflowId) { it.copy(remediationBaseSha = "b".repeat(40)) }

    val reloaded = assertNotNull(recorder.reviewState(workflowId))
    assertEquals(CodeReviewExecutionMode.INLINE, reloaded.resolvedTier)
    assertEquals("auto_mode_by_pass_number:pass_n_inline", reloaded.decidingRule)
    assertEquals("b".repeat(40), reloaded.remediationBaseSha)
  }

  @Test
  fun `resume reuses the baseline and consumed pass count exactly`() {
    val recorder = recorderWith(pausedState())

    val reloaded = assertNotNull(recorder.reviewState(workflowId))
    assertEquals("a".repeat(40), reloaded.reviewBaseSha, "review_base_sha is immutable across the pause.")
    assertEquals(listOf("scratch/untracked.txt"), reloaded.baselineUntrackedPaths)
    assertEquals(GOAL_SUBTASK_REVIEW_MAX_PASSES, reloaded.completedPassCount)
    assertNull(reloaded.reservedPassNumber, "A consumed pass must never be re-reserved by a plain resume.")
  }

  private fun recorderWith(state: GoalSubtaskReviewState): FeatureTaskRuntimeGoalContinuationRecorder {
    val repository = InMemoryRuntimeWorkflowRepository()
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
    val seeded = engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "review",
        stepUpdates = null,
        artifactsPatch = mapOf(
          FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to FeatureTaskRuntimeGoalContinuationArtifact(
            issueKey = "SKILL-142",
            subtaskId = 5,
            suppressPr = true,
            goalBranch = "feat/SKILL-142",
            codeReviewMode = CodeReviewExecutionMode.INLINE,
          ).toArtifactMap(),
          GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to state.toArtifactMap(),
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to state.passResults.associate { result ->
            result.passNumber.toString() to """{"phase_id":"review","status":"completed"}"""
          },
        ),
        sessionId = "fis-001",
      ),
    ).toRecord()
    repository.saveFeatureTaskRuntimeWorkflow(seeded)
    return FeatureTaskRuntimeGoalContinuationRecorder(
      RuntimeFakeDatabaseSessionFactory(repository),
      testWorkflowSnapshotValidator,
    )
  }

  private fun pausedState(): GoalSubtaskReviewState {
    val initial = GoalSubtaskReviewState.initial(
      reviewBaseSha = "a".repeat(40),
      baselineUntrackedPaths = listOf("scratch/untracked.txt"),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    )
    val passOne = initial.reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = listOf(
        GoalSubtaskReviewCompactFinding(
          severity = "blocker",
          label = "GoalRunnerPolicy",
          text = "a Blocker the remediation pass must disposition",
          findingId = "F-001",
        ),
      ),
    )
    return passOne.reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = emptyList(),
      blockerDispositions = listOf(
        GoalSubtaskBlockerDisposition(
          findingId = "F-001",
          verdict = GoalSubtaskBlockerDispositionVerdict.UNRESOLVED,
          evidence = listOf("still reproduces at the same seam"),
        ),
      ),
    )
  }
}
