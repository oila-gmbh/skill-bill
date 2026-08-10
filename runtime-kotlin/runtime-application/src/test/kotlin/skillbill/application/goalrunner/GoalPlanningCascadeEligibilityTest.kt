package skillbill.application.goalrunner

import skillbill.workflow.model.DecompositionSubtask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalPlanningCascadeEligibilityTest {
  @Test
  fun `complete with commit_sha is not cascade-eligible`() {
    assertTrue(isTerminalWithCommitPlan("complete", "abc123"))
    assertTrue(
      isTerminalWithCommitPlan(
        DecompositionSubtask(id = 1, name = "done", specPath = "s.md", status = "complete", commitSha = "abc123"),
      ),
    )
    assertFalse(isTerminalWithCommitPlan("complete", null))
    assertFalse(isTerminalWithCommitPlan("complete", ""))
    assertFalse(isTerminalWithCommitPlan("pending", "abc123"))
  }

  @Test
  fun `pending blocked in-progress and complete without commit remain eligible`() {
    val subtasks = listOf(
      DecompositionSubtask(1, "a", "a.md", status = "complete", commitSha = "sha-1"),
      DecompositionSubtask(2, "b", "b.md", status = "pending"),
      DecompositionSubtask(3, "c", "c.md", status = "blocked"),
      DecompositionSubtask(4, "d", "d.md", status = "in_progress"),
      DecompositionSubtask(5, "e", "e.md", status = "complete", commitSha = null),
      DecompositionSubtask(6, "f", "f.md", status = "complete", commitSha = "  "),
    )
    assertEquals(
      listOf(2, 3, 4, 5, 6),
      cascadeEligiblePlanSubtaskIds(plannedIds = listOf(1, 2, 3, 4, 5, 6), subtasks = subtasks),
    )
  }

  @Test
  fun `named replan target filtering is independent of eligibility helper`() {
    val subtasks = listOf(
      DecompositionSubtask(1, "a", "a.md", status = "complete", commitSha = "sha-1"),
      DecompositionSubtask(2, "b", "b.md", status = "pending"),
      DecompositionSubtask(3, "c", "c.md", status = "pending"),
    )
    val siblings = cascadeEligiblePlanSubtaskIds(
      plannedIds = listOf(1, 2, 3).filter { it != 3 },
      subtasks = subtasks,
    )
    assertEquals(listOf(2), siblings)
  }

  @Test
  fun `unknown planned id without manifest match stays eligible`() {
    assertEquals(
      listOf(99),
      cascadeEligiblePlanSubtaskIds(
        plannedIds = listOf(99),
        subtasks = listOf(DecompositionSubtask(1, "a", "a.md", status = "complete", commitSha = "sha")),
      ),
    )
  }
}
