package skillbill.architecture

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.UnavailableGoalRunnerControlRepository
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GoalRunnerControlBindingArchitectureTest {
  @Test
  fun `unavailable goal runner control repository refuses persistControlState`() {
    assertFailsWith<IllegalStateException> {
      UnavailableGoalRunnerControlRepository.persistControlState(
        parentWorkflowId = "goal-1",
        state = GoalRunnerControlState(),
      )
    }
  }

  @Test
  fun `unavailable goal runner control repository refuses controlState reads`() {
    assertFailsWith<IllegalStateException> {
      UnavailableGoalRunnerControlRepository.controlState(parentWorkflowId = "goal-1")
    }
  }
}
