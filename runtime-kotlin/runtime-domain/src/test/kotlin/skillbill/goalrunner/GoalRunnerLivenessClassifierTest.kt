package skillbill.goalrunner

import skillbill.goalrunner.model.GoalRunnerLivenessClassifier
import skillbill.goalrunner.model.GoalRunnerLivenessInputs
import skillbill.goalrunner.model.GoalRunnerLivenessState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalRunnerLivenessClassifierTest {
  private val base = GoalRunnerLivenessInputs(
    processAlive = true,
    operationActive = false,
    operationExpectedLong = false,
    durableAdvanceWithinInterval = false,
    operationDeadlineOverrun = false,
    wallClockCapExceeded = false,
  )

  @Test
  fun `live declared long operation classifies working and disarms idle timeout`() {
    val decision = GoalRunnerLivenessClassifier.classify(
      base.copy(operationActive = true, operationExpectedLong = true),
    )
    assertEquals(GoalRunnerLivenessState.WORKING, decision.state)
    assertFalse(decision.armIdleTimeout)
    assertTrue(decision.disarmIdleTimeout)
  }

  @Test
  fun `durable advance classifies progressing and disarms idle timeout`() {
    val decision = GoalRunnerLivenessClassifier.classify(
      base.copy(durableAdvanceWithinInterval = true),
    )
    assertEquals(GoalRunnerLivenessState.PROGRESSING, decision.state)
    assertFalse(decision.armIdleTimeout)
  }

  @Test
  fun `no operation and no durable advance classifies idle and arms idle timeout`() {
    val decision = GoalRunnerLivenessClassifier.classify(base)
    assertEquals(GoalRunnerLivenessState.IDLE, decision.state)
    assertTrue(decision.armIdleTimeout)
  }

  @Test
  fun `idle is the only state that arms the idle timeout`() {
    GoalRunnerLivenessState.entries.forEach { state ->
      assertEquals(
        state == GoalRunnerLivenessState.IDLE,
        state.armsIdleTimeout,
        "$state armsIdleTimeout must equal (state == IDLE)",
      )
    }
  }

  @Test
  fun `dead process classifies unresponsive deterministically`() {
    val decision = GoalRunnerLivenessClassifier.classify(
      base.copy(processAlive = false, operationActive = true),
    )
    assertEquals(GoalRunnerLivenessState.UNRESPONSIVE, decision.state)
    assertFalse(decision.armIdleTimeout)
  }

  @Test
  fun `operation deadline overrun classifies unresponsive even when process alive`() {
    val decision = GoalRunnerLivenessClassifier.classify(
      base.copy(operationActive = true, operationDeadlineOverrun = true),
    )
    assertEquals(GoalRunnerLivenessState.UNRESPONSIVE, decision.state)
  }

  @Test
  fun `wall clock cap exceeded classifies unresponsive`() {
    val decision = GoalRunnerLivenessClassifier.classify(base.copy(wallClockCapExceeded = true))
    assertEquals(GoalRunnerLivenessState.UNRESPONSIVE, decision.state)
  }
}
