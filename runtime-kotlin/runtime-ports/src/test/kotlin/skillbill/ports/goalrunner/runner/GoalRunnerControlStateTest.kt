package skillbill.ports.goalrunner.runner

import skillbill.goalrunner.model.GoalRunnerControlState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalRunnerControlStateTest {
  @Test
  fun `positive stop-after policy and consumed operator request are valid`() {
    val state = GoalRunnerControlState(
      stopAfterSubtaskId = 3,
      pauseRequested = true,
      pauseConsumed = true,
      paused = true,
      pauseReason = "operator_request",
      pausedAt = "2026-08-07T10:00:00Z",
    )

    assertEquals(3, state.stopAfterSubtaskId)
    assertEquals("operator_request", state.pauseReason)
    assertEquals("2026-08-07T10:00:00Z", state.pausedAt)
  }

  @Test
  fun `an unpaused control state carries no pause timestamp`() {
    assertEquals(null, GoalRunnerControlState().pausedAt)
    assertEquals(null, GoalRunnerControlState(pauseRequested = true).pausedAt)
  }

  @Test
  fun `a paused control state without a pause timestamp is rejected at construction`() {
    assertFailsWith<IllegalArgumentException> {
      GoalRunnerControlState(paused = true, pauseReason = "operator_stop", pausedAt = null)
    }
  }

  @Test
  fun `a blank pause timestamp is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      GoalRunnerControlState(paused = true, pauseReason = "operator_stop", pausedAt = "  ")
    }
  }

  @Test
  fun `invalid control states fail loudly`() {
    assertFailsWith<IllegalArgumentException> { GoalRunnerControlState(stopAfterSubtaskId = 0) }
    assertFailsWith<IllegalArgumentException> {
      GoalRunnerControlState(pauseConsumed = true, pauseRequested = false)
    }
    assertFailsWith<IllegalArgumentException> {
      GoalRunnerControlState(paused = true, pauseReason = null, pausedAt = "2026-08-07T10:00:00Z")
    }
  }
}
