package skillbill.workflow.taskruntime

import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDispositionVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.model.GoalSubtaskPauseRelease
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A paused subtask rides SKILL-141's existing non-terminal resumable status rather than a second
 * pause mechanism, and the operator decision is only accepted from that state. Since SKILL-157 no
 * pass count mints the pause; it is an operator-driven control the remediation loop never triggers
 * on its own.
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
  fun `a consumed retry grant is single-use and does not re-reserve a consumed pass`() {
    val granted = pausedState().applyOperatorDecision(GoalSubtaskOperatorDecision.RETRY_FIX)

    val consumed = granted.consumeOperatorDecision()
    assertNull(consumed.operatorDecision, "The durable decision must be cleared so a resume cannot re-grant.")
    assertTrue(consumed.retryReviewPending, "The granted round must mark the carried-forward result stale.")

    val reopened = consumed.reserveNextPass()
    assertEquals(
      2,
      reopened.reservedPassNumber,
      "A retry round re-opens the pass it was granted against; it never allocates an extra one.",
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
    assertEquals(2, settled.completedPassCount)
    assertTrue(!settled.retryReviewPending, "A settled round is no longer pending.")
    assertTrue(!settled.pausedForOperatorDecision, "Every Blocker resolved, so the subtask advances.")
  }

  /**
   * SKILL-157 removed the pass ceiling, so no pass count mints a pause: an unresolved Blocker
   * reserves the next remediation pass instead. The pause survives only as an operator-driven
   * control over an already-settled pass, which is the state this builds directly.
   */
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
  ).copy(disposition = GoalSubtaskReviewDisposition.PAUSED)

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
    codeReviewMode = CodeReviewExecutionMode.INLINE,
  )
}
