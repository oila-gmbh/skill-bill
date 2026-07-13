package skillbill.application.model

import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.ports.goalrunner.model.GoalRunnerManifestState

internal sealed interface GoalRunPreparation {
  data class Prepared(
    val state: GoalRunnerManifestState,
    val request: GoalRunnerRunRequest,
  ) : GoalRunPreparation

  data class PreparationBlocked(val report: GoalRunnerRunReport) : GoalRunPreparation
}
