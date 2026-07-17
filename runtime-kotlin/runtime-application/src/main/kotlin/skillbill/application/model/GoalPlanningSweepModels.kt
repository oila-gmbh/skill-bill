package skillbill.application.model

import skillbill.goalrunner.model.GoalRunnerStopReason

sealed interface GoalPlanningSweepOutcome {
  data object PreparedAll : GoalPlanningSweepOutcome

  data class Stopped(
    val issueKey: String,
    val currentSubtaskId: Int,
    val reason: GoalRunnerStopReason,
    val blockedReason: String,
    val lastResumableStep: String,
  ) : GoalPlanningSweepOutcome
}

internal sealed interface GoalPlanningPhaseProduction {
  data class Captured(val payload: String) : GoalPlanningPhaseProduction
  data class Stopped(val outcome: GoalPlanningSweepOutcome.Stopped) : GoalPlanningPhaseProduction
}
