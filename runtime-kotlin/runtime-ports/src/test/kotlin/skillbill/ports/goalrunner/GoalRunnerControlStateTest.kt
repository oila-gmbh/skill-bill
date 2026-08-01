package skillbill.ports.goalrunner

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
    )

    assertEquals(3, state.stopAfterSubtaskId)
    assertEquals("operator_request", state.pauseReason)
  }

  @Test
  fun `invalid control states fail loudly`() {
    assertFailsWith<IllegalArgumentException> { GoalRunnerControlState(stopAfterSubtaskId = 0) }
    assertFailsWith<IllegalArgumentException> {
      GoalRunnerControlState(pauseConsumed = true, pauseRequested = false)
    }
    assertFailsWith<IllegalArgumentException> {
      GoalRunnerControlState(paused = true, pauseReason = null)
    }
  }
}
