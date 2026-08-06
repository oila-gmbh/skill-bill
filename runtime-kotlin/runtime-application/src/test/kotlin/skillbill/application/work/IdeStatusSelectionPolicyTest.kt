package skillbill.application.work

import skillbill.application.model.IdeStatusLifecycleState
import skillbill.application.model.IdeStatusSelectionTier
import skillbill.application.model.IdeStatusWorkflowFamily
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdeStatusSelectionPolicyTest {
  @Test
  fun `active outranks paused blocked failed and terminal competitors`() {
    val winner = candidate("active", IdeStatusLifecycleState.ACTIVE, "w-active", "2026-08-06T10:00:00Z")
    val competitors = listOf(
      candidate("paused", IdeStatusLifecycleState.PAUSED, "w-paused", "2026-08-06T11:00:00Z"),
      candidate("blocked", IdeStatusLifecycleState.BLOCKED, "w-blocked", "2026-08-06T11:00:00Z"),
      candidate("failed", IdeStatusLifecycleState.FAILED, "w-failed", "2026-08-06T11:00:00Z"),
      candidate("terminal", IdeStatusLifecycleState.TERMINAL, "w-terminal", "2026-08-06T11:00:00Z"),
      winner,
    )
    assertEquals("w-active", IdeStatusSelectionPolicy.select(competitors)?.workflowId)
  }

  @Test
  fun `paused outranks blocked failed and terminal`() {
    val winner = candidate("paused", IdeStatusLifecycleState.PAUSED, "w-paused", "2026-08-06T10:00:00Z")
    val competitors = listOf(
      candidate("blocked", IdeStatusLifecycleState.BLOCKED, "w-blocked", "2026-08-06T11:00:00Z"),
      candidate("failed", IdeStatusLifecycleState.FAILED, "w-failed", "2026-08-06T11:00:00Z"),
      candidate("terminal", IdeStatusLifecycleState.TERMINAL, "w-terminal", "2026-08-06T11:00:00Z"),
      winner,
    )
    assertEquals("w-paused", IdeStatusSelectionPolicy.select(competitors)?.workflowId)
  }

  @Test
  fun `feature-goal outranks child runtime for the same issue within a tier`() {
    val goal = IdeStatusCandidate(
      workflowId = "goal-1",
      workflowFamily = IdeStatusWorkflowFamily.FEATURE_GOAL,
      issueKey = "SKILL-148",
      currentState = "running",
      lifecycleState = IdeStatusLifecycleState.ACTIVE,
      selectionTier = IdeStatusSelectionTier.ACTIVE,
      updatedAt = Instant.parse("2026-08-06T09:00:00Z"),
      startedAt = Instant.parse("2026-08-06T08:00:00Z"),
      isGoalAuthoritative = true,
    )
    val child = IdeStatusCandidate(
      workflowId = "runtime-child",
      workflowFamily = IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME,
      issueKey = "SKILL-148",
      currentState = "running",
      lifecycleState = IdeStatusLifecycleState.ACTIVE,
      selectionTier = IdeStatusSelectionTier.ACTIVE,
      updatedAt = Instant.parse("2026-08-06T12:00:00Z"),
      startedAt = Instant.parse("2026-08-06T11:00:00Z"),
      isGoalAuthoritative = false,
    )
    assertEquals("goal-1", IdeStatusSelectionPolicy.select(listOf(child, goal))?.workflowId)
  }

  @Test
  fun `within a tier more recent updated_at wins then workflow_id lexicographic order`() {
    val older = candidate("a", IdeStatusLifecycleState.ACTIVE, "w-b", "2026-08-06T10:00:00Z")
    val newer = candidate("a", IdeStatusLifecycleState.ACTIVE, "w-a", "2026-08-06T11:00:00Z")
    assertEquals("w-a", IdeStatusSelectionPolicy.select(listOf(older, newer))?.workflowId)

    val tieA = candidate("a", IdeStatusLifecycleState.ACTIVE, "w-a", "2026-08-06T11:00:00Z")
    val tieB = candidate("a", IdeStatusLifecycleState.ACTIVE, "w-b", "2026-08-06T11:00:00Z")
    assertEquals("w-a", IdeStatusSelectionPolicy.select(listOf(tieB, tieA))?.workflowId)
  }

  @Test
  fun `empty candidate list yields null`() {
    assertNull(IdeStatusSelectionPolicy.select(emptyList()))
  }

  private fun candidate(
    issueKey: String,
    lifecycle: IdeStatusLifecycleState,
    workflowId: String,
    updatedAt: String,
  ): IdeStatusCandidate = IdeStatusCandidate(
    workflowId = workflowId,
    workflowFamily = IdeStatusWorkflowFamily.FEATURE_TASK_PROSE,
    issueKey = issueKey,
    currentState = lifecycle.wireValue,
    lifecycleState = lifecycle,
    selectionTier = IdeStatusSelectionPolicy.selectionTier(lifecycle),
    updatedAt = Instant.parse(updatedAt),
    startedAt = Instant.parse("2026-08-06T08:00:00Z"),
    isGoalAuthoritative = false,
  )
}
