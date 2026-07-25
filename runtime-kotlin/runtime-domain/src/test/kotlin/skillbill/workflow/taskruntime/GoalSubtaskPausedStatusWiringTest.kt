package skillbill.workflow.taskruntime

import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDispositionVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * AC-013 and AC-014: an unresolved Blocker disposition pauses the subtask on SKILL-141's existing
 * non-terminal resumable status rather than a second pause mechanism, and the operator decision is
 * only accepted from that state.
 */
class GoalSubtaskPausedStatusWiringTest {
  @Test
  fun `the child phase workflow accepts the paused step status`() {
    assertTrue(
      FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED in
        FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepStatuses,
      "A paused phase row cannot be written unless the definition admits the status.",
    )
    assertTrue(
      FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED in
        FeatureTaskRuntimePhaseWorkflowDefinition.definition.workflowStatuses,
    )
  }

  @Test
  fun `paused is a distinct goal-runner terminal status, not blocked`() {
    assertEquals(
      FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED,
      GoalRunnerTerminalStatus.PAUSED.name.lowercase(),
    )
  }

  @Test
  fun `an unresolved disposition pauses and then accepts exactly one operator decision`() {
    val paused = pausedState()

    assertTrue(paused.pausedForOperatorDecision)
    assertEquals(
      GoalSubtaskOperatorDecision.RETRY_FIX,
      paused.applyOperatorDecision(GoalSubtaskOperatorDecision.RETRY_FIX).operatorDecision,
    )
  }

  @Test
  fun `an operator decision outside the paused state is rejected`() {
    val resolvedPass = initialState().reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = emptyList(),
      blockerDispositions = listOf(
        GoalSubtaskBlockerDisposition("pass1-blocker-1", GoalSubtaskBlockerDispositionVerdict.RESOLVED, listOf("fixed")),
      ),
    )

    assertTrue(!resolvedPass.pausedForOperatorDecision)
    assertFailsWith<IllegalArgumentException> {
      resolvedPass.applyOperatorDecision(GoalSubtaskOperatorDecision.ACCEPT_AND_ADVANCE)
    }
  }

  private fun pausedState() = initialState().reserveNextPass().completeReservedPass(
    verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
    unresolvedFindingCount = 1,
    findings = emptyList(),
    blockerDispositions = listOf(
      GoalSubtaskBlockerDisposition(
        findingId = "pass1-blocker-1",
        verdict = GoalSubtaskBlockerDispositionVerdict.UNRESOLVED,
        evidence = listOf("still reproduces at the same seam"),
      ),
    ),
  )

  private fun initialState() = GoalSubtaskReviewState.initial(
    reviewBaseSha = "a".repeat(40),
    baselineUntrackedPaths = emptyList(),
    codeReviewMode = CodeReviewExecutionMode.DELEGATED,
  )
}
