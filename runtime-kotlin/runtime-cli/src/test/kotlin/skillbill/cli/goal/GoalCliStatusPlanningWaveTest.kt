package skillbill.cli.goal

import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bug this catches: a 5-wide planning wave still presenting to an operator as one planning
 * subtask, which is the under-reporting `goal status` exists to remove.
 */
class GoalCliStatusPlanningWaveTest {
  @Test
  fun `goal status carries every concurrent planning subtask and names the count on the human line`() {
    val projection = GoalRunnerStatusProjection(
      issueKey = "SKILL-230",
      completeCount = 1,
      pendingCount = 7,
      blockedCount = 0,
      currentSubtaskId = 2,
      currentStep = "planning",
      activeAgent = "claude",
      planning = GoalPlanningStatusSnapshot(
        state = GoalPlanningStatusState.PARTIALLY_PLANNED,
        sharedPreplanPrepared = true,
        plannedSubtaskCount = 1,
        totalSubtaskCount = 8,
        currentPlanningSubtaskId = 2,
        planningWaveSubtaskIds = listOf(2, 3, 4, 5, 6),
        reason = "Planning resumes at subtask 2.",
      ),
    )

    val payload = projection.toGoalStatusCliMap("SKILL-230")
    val planning = payload["planning"] as Map<*, *>
    assertEquals(listOf(2, 3, 4, 5, 6), planning["planning_wave_subtasks"])
    assertEquals(2, planning["current_planning_subtask"])

    val text = goalStatusText(payload)
    assertTrue(text.contains("wave=5 subtasks"), text)
  }
}
