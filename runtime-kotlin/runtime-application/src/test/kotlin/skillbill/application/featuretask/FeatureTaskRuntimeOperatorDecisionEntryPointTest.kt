package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import kotlin.test.Test
import kotlin.test.assertEquals

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
    val reopened = FeatureTaskRuntimePhaseRecord(
      phaseId = "implement_fix",
      status = "blocked",
      attemptCount = 13,
      startedAt = "2026-08-19T20:07:08Z",
      finishedAt = "2026-08-19T21:42:42Z",
      resolvedAgentId = "cursor",
      blockedReason = "an operator decision is required before implementation",
    ).asPendingForOperatorResume()

    val projected = stepUpdatesFrom(mapOf(reopened.phaseId to reopened)).single()

    assertEquals("implement_fix", projected["step_id"])
    assertEquals("pending", projected["status"], "a reopened phase is unstarted work, not completed work")
  }
}
