package skillbill.application.goalrunner

import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalChildRecoveryTest {
  @Test
  fun `classification distinguishes absent active resumable and incompatible children`() {
    assertEquals(DurableChildRecoveryClass.ABSENT, classifyDurableChild(null))
    assertEquals(DurableChildRecoveryClass.ACTIVE, classifyDurableChild(progress("running")))
    assertEquals(DurableChildRecoveryClass.RESUMABLE, classifyDurableChild(progress("pending")))
    assertEquals(DurableChildRecoveryClass.RESUMABLE, classifyDurableChild(progress("paused")))
    listOf("blocked", "failed", "abandoned", "timed_out", "completed").forEach { status ->
      assertEquals(
        DurableChildRecoveryClass.INCOMPATIBLE_TERMINAL,
        classifyDurableChild(progress(status)),
        status,
      )
    }
  }

  @Test
  fun `scoped recovery command is stable and explicit`() {
    assertEquals(
      "skill-bill goal reset SKILL-143 --subtask 2 --delete-child-workflow",
      scopedChildRecoveryCommand("SKILL-143", 2),
    )
  }

  @Test
  fun `ledger safe action distinguishes resumable and terminal children`() {
    assertEquals(
      "resume_from_last_resumable_step",
      recoverySafeAction("SKILL-143", 2, progress("paused"), "inspect_blocked_reason"),
    )
    assertEquals(
      "skill-bill goal reset SKILL-143 --subtask 2 --delete-child-workflow",
      recoverySafeAction("SKILL-143", 2, progress("failed"), "inspect_blocked_reason"),
    )
  }

  private fun progress(status: String) = GoalRunnerWorkflowProgress(
    workflowId = "child-1",
    workflowStatus = status,
    currentStepId = "implement",
    progressToken = "token",
  )
}
