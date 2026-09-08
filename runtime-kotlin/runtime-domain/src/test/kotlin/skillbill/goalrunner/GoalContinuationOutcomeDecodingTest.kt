package skillbill.goalrunner

import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoalContinuationOutcomeDecodingTest {
  @Test
  fun `continuation decoder preserves terminal aliases and ignores absent or unknown status`() {
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, goalContinuationTerminalStatus("complete"))
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, goalContinuationTerminalStatus("completed"))
    assertEquals(GoalRunnerTerminalStatus.TIMEOUT, goalContinuationTerminalStatus("timeout"))
    assertEquals(GoalRunnerTerminalStatus.TIMEOUT, goalContinuationTerminalStatus("timed_out"))
    assertNull(goalContinuationTerminalStatus(null))
    assertNull(goalContinuationTerminalStatus(""))
    assertNull(goalContinuationTerminalStatus("unknown"))

    val decoded = goalContinuationOutcome(
      artifacts = mapOf<String, Any?>(
        "goal_continuation_outcome" to mapOf(
          "issue_key" to "SKILL-233",
          "subtask_id" to 4,
          "status" to "completed",
          "workflow_id" to "wf-233-4",
        ),
      ),
      issueKey = "SKILL-233",
      subtaskId = 4,
      suppressPr = true,
    )

    assertEquals(GoalRunnerTerminalStatus.COMPLETE, decoded?.status)
  }
}
