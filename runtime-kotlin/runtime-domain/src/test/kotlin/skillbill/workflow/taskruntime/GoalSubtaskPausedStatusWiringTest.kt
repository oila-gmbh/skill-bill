package skillbill.workflow.taskruntime

import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_MAX_PASSES
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDispositionVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.model.GoalSubtaskPauseRelease
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
        GoalSubtaskBlockerDisposition(
          "pass1-blocker-1",
          GoalSubtaskBlockerDispositionVerdict.RESOLVED,
          listOf("fixed"),
        ),
      ),
    )

    assertTrue(!resolvedPass.pausedForOperatorDecision)
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      resolvedPass.applyOperatorDecision(GoalSubtaskOperatorDecision.ACCEPT_AND_ADVANCE)
    }
  }

  @Test
  fun `every operator decision releases the pause it answers`() {
    val paused = pausedState()

    assertEquals(
      GoalSubtaskPauseRelease.RETRY_FIX,
      paused.applyOperatorDecision(GoalSubtaskOperatorDecision.RETRY_FIX).pauseRelease,
    )
    assertEquals(
      GoalSubtaskPauseRelease.ADVANCE,
      paused.applyOperatorDecision(GoalSubtaskOperatorDecision.ACCEPT_AND_ADVANCE).pauseRelease,
    )
    assertEquals(
      GoalSubtaskPauseRelease.ABANDON,
      paused.applyOperatorDecision(GoalSubtaskOperatorDecision.ABANDON_SUBTASK).pauseRelease,
    )
    assertNull(paused.pauseRelease, "An undecided pause has no release.")
  }

  @Test
  fun `a consumed retry grant is single-use and retains the prior capped attempt`() {
    val granted = pausedState().applyOperatorDecision(GoalSubtaskOperatorDecision.RETRY_FIX)

    val consumed = granted.consumeOperatorDecision()
    assertNull(consumed.operatorDecision, "The durable decision must be cleared so a resume cannot re-grant.")
    assertTrue(consumed.retryReviewPending, "The granted round must mark the carried-forward result stale.")

    val reopened = consumed.reserveNextPass()
    assertEquals(
      GOAL_SUBTASK_REVIEW_MAX_PASSES,
      reopened.reservedPassNumber,
      "A retry round re-opens the consumed final pass; it never reserves a pass beyond the cap.",
    )
    assertTrue(!reopened.pausedForOperatorDecision, "Re-opening clears the stale pause.")

    val settled = reopened.completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = emptyList(),
      blockerDispositions = listOf(
        GoalSubtaskBlockerDisposition("F-001", GoalSubtaskBlockerDispositionVerdict.RESOLVED, listOf("fixed")),
      ),
    )
    assertEquals(GOAL_SUBTASK_REVIEW_MAX_PASSES, settled.completedPassCount)
    assertEquals(3, settled.passResults.size, "The prior capped attempt remains in append-only history.")
    assertEquals(listOf(1, 2, 2), settled.passResults.map { it.passNumber })
    assertTrue(!settled.retryReviewPending, "A settled round is no longer pending.")
    assertTrue(!settled.pausedForOperatorDecision, "Every Blocker resolved, so the subtask advances.")
  }

  private fun pausedState() = firstPass().reserveNextPass().completeReservedPass(
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

  // The pause is only reachable on the final pass, so pass one must be consumed first.
  private fun firstPass() = initialState().reserveNextPass().completeReservedPass(
    verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
    unresolvedFindingCount = 1,
    findings = listOf(
      GoalSubtaskReviewCompactFinding(
        severity = GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY,
        label = "GoalRunnerPolicy",
        text = "a Blocker the remediation pass must disposition",
        findingId = "F-001",
      ),
    ),
  )

  private fun initialState() = GoalSubtaskReviewState.initial(
    reviewBaseSha = "a".repeat(40),
    baselineUntrackedPaths = emptyList(),
    codeReviewMode = CodeReviewExecutionMode.DELEGATED,
  )
}
