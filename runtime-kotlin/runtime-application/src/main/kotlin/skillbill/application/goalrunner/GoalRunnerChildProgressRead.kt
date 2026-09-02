package skillbill.application.goalrunner

import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress

internal sealed interface GoalRunnerChildProgressRead {
  data object Absent : GoalRunnerChildProgressRead

  data class Present(val progress: GoalRunnerWorkflowProgress?) : GoalRunnerChildProgressRead

  data class Failed(val error: Exception) : GoalRunnerChildProgressRead
}
