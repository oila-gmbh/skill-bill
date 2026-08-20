package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.GoalSubtaskOperatorDecision
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AC-015: the operator decision reaches production through a real entry point. Before this wiring
 * `applyOperatorDecision` had no production caller, so `retry_fix` could never grant the fresh
 * `implement_fix` iteration the paused subtask waits on.
 */
class FeatureTaskRuntimeOperatorDecisionEntryPointTest {
  @Test
  fun `every declared decision has a stable wire value the CLI can parse`() {
    assertEquals(
      setOf("retry_fix", "accept_and_advance", "abandon_subtask"),
      GoalSubtaskOperatorDecision.entries.map { it.wireValue }.toSet(),
      "The CLI parses --operator-decision against exactly this vocabulary.",
    )
  }

  @Test
  fun `a reopened phase record projects onto a pending step instead of failing the projection`() {
    // asPendingForOperatorResume is the only writer of a pending phase record, and every recorder
    // write projects records onto steps[]. With no mapping for pending the projection threw
    // InvalidWorkflowStateSchemaError, so the child died at the first write after any reopen and the
    // parent saw only a non-zero exit.
    val reopened = FeatureTaskRuntimePhaseRecord(
      phaseId = "plan_fix",
      status = "blocked",
      attemptCount = 13,
      startedAt = "2026-08-19T20:07:08Z",
      finishedAt = "2026-08-19T21:42:42Z",
      resolvedAgentId = "cursor",
      blockedReason = "an operator decision is required before implementation",
    ).asPendingForOperatorResume()

    val projected = stepUpdatesFrom(mapOf(reopened.phaseId to reopened)).single()

    assertEquals("plan_fix", projected["step_id"])
    assertEquals("pending", projected["status"], "a reopened phase is unstarted work, not completed work")
  }

  @Test
  fun `a persisted retry_fix grant survives the resume that reads it back`() {
    assertEquals(
      true,
      FeatureTaskRuntimeOperatorRetryGrant.active(
        consumed = false,
        inSessionGrant = false,
        persistedDecision = GoalSubtaskOperatorDecision.RETRY_FIX,
      ),
      "A resumed run reads the grant off durable review state, not off in-memory session flags.",
    )
  }

  @Test
  fun `an active grant suppresses the unresolved-Blocker pause for one transition`() {
    assertEquals(
      false,
      FeatureTaskRuntimeOperatorRetryGrant.pausesOnUnresolvedBlocker(
        grantActive = true,
        unresolvedBlockerPresent = true,
      ),
      "The granted implement_fix iteration must be entered instead of re-pausing on the carried disposition.",
    )
  }

  @Test
  fun `a consumed grant pauses again on the next unresolved pass`() {
    val consumed = FeatureTaskRuntimeOperatorRetryGrant.active(
      consumed = true,
      inSessionGrant = true,
      persistedDecision = GoalSubtaskOperatorDecision.RETRY_FIX,
    )
    assertEquals(false, consumed, "The grant is single-use once the review_fix edge is taken.")
    assertEquals(
      true,
      FeatureTaskRuntimeOperatorRetryGrant.pausesOnUnresolvedBlocker(
        grantActive = consumed,
        unresolvedBlockerPresent = true,
      ),
    )
  }

  @Test
  fun `a non-retry decision never grants an iteration`() {
    listOf(GoalSubtaskOperatorDecision.ACCEPT_AND_ADVANCE, GoalSubtaskOperatorDecision.ABANDON_SUBTASK)
      .forEach { decision ->
        assertEquals(
          false,
          FeatureTaskRuntimeOperatorRetryGrant.active(
            consumed = false,
            inSessionGrant = false,
            persistedDecision = decision,
          ),
          "Only retry_fix grants a fresh implement_fix iteration.",
        )
      }
  }
}
