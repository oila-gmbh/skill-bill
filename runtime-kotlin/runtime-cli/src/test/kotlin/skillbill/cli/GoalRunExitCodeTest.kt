package skillbill.cli

import skillbill.cli.goal.goalRunExitCode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Bug this catches: goal run collapses paused/blocked/failed into exit 1, so harnesses cannot tell
 * a durable operator pause from failure by exit code alone.
 */
class GoalRunExitCodeTest {
  @Test
  fun `complete exits 0`() {
    assertEquals(0, goalRunExitCode("complete", reason = null))
  }

  @Test
  fun `paused exits 2 not 1`() {
    assertEquals(2, goalRunExitCode("stopped", "paused"))
  }

  @Test
  fun `failed exits 1`() {
    assertEquals(1, goalRunExitCode("stopped", "failed"))
  }

  @Test
  fun `timeout classifies as failed exit 1`() {
    assertEquals(1, goalRunExitCode("stopped", "timeout"))
  }

  @Test
  fun `blocked exits 3`() {
    assertEquals(3, goalRunExitCode("stopped", "blocked"))
  }

  @Test
  fun `policy_blocked exits 3`() {
    assertEquals(3, goalRunExitCode("stopped", "policy_blocked"))
  }
}
