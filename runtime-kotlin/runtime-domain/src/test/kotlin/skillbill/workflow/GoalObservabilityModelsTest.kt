package skillbill.workflow

import skillbill.workflow.model.GOAL_OBSERVABILITY_HISTORY_LIMIT
import skillbill.workflow.model.GoalObservabilityEvent
import skillbill.workflow.model.GoalObservabilityHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GoalObservabilityModelsTest {
  @Test
  fun `history append preserves sequence order and prunes oldest entries`() {
    val history = (1..(GOAL_OBSERVABILITY_HISTORY_LIMIT + 2))
      .fold(GoalObservabilityHistory()) { current, sequence ->
        current.append(event(sequence))
      }

    assertEquals(GOAL_OBSERVABILITY_HISTORY_LIMIT, history.events.size)
    assertEquals(3, history.events.first().sequenceNumber)
    assertEquals(GOAL_OBSERVABILITY_HISTORY_LIMIT + 2, history.events.last().sequenceNumber)
  }

  @Test
  fun `default artifact rendering omits optional heavy fields`() {
    val rendered = event(1, changedFiles = listOf("src/Main.kt")).toArtifactMap()

    assertFalse(rendered.containsKey("changed_files"))
    assertEquals("implement", rendered["workflow_phase"])
    assertEquals("durable_progress", rendered["liveness_class"])
  }

  private fun event(sequence: Int, changedFiles: List<String> = emptyList()): GoalObservabilityEvent =
    GoalObservabilityEvent(
      issueKey = "SKILL-61",
      subtaskId = 1,
      workflowPhase = "implement",
      workerRole = "phase_subagent",
      livenessClass = "durable_progress",
      activitySummary = "working",
      timestamp = "2026-06-01T00:00:00Z",
      sequenceNumber = sequence,
      changedFiles = changedFiles,
    )
}
