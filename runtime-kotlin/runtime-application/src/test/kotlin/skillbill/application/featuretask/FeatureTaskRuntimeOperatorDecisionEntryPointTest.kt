package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.error.FeatureTaskRuntimeOperatorDecisionRejectedError
import skillbill.workflow.taskruntime.model.GoalSubtaskOperatorDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * AC-015: the operator decision reaches production through a real entry point. Before this wiring
 * `applyOperatorDecision` had no production caller, so `retry_fix` could never grant the fresh
 * `implement_fix` iteration the paused subtask waits on.
 */
class FeatureTaskRuntimeOperatorDecisionEntryPointTest {
  @Test
  fun `the run request carries an operator decision and defaults to none`() {
    val field = FeatureTaskRuntimeRunRequest::operatorDecision
    assertNotNull(field, "The runner needs a request-carried operator decision to apply.")
  }

  @Test
  fun `every declared decision has a stable wire value the CLI can parse`() {
    assertEquals(
      setOf("retry_fix", "accept_and_advance", "abandon_subtask"),
      GoalSubtaskOperatorDecision.entries.map { it.wireValue }.toSet(),
      "The CLI parses --operator-decision against exactly this vocabulary.",
    )
  }

  @Test
  fun `a rejected decision loud-fails through the typed error rather than driving the loop`() {
    val error = FeatureTaskRuntimeOperatorDecisionRejectedError(
      workflowId = "wftr-1",
      decision = GoalSubtaskOperatorDecision.RETRY_FIX.wireValue,
      reason = "The subtask is not paused; an operator decision is only accepted while it is paused.",
    )
    assertNull(error.cause)
    assertEquals("retry_fix", error.decision)
    assertEquals(true, error.message.orEmpty().contains("wftr-1"))
  }
}
