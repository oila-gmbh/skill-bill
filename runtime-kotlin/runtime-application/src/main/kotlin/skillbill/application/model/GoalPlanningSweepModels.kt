package skillbill.application.model

import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.persistence.model.GoalPlanningContractProvenance
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor

sealed interface GoalPlanningSweepOutcome {
  data class PreparedAll(
    val identity: GoalPlanningIdentity? = null,
    val provenance: GoalPlanningContractProvenance? = null,
    val descriptors: List<GovernedGoalSubtaskDescriptor> = emptyList(),
  ) : GoalPlanningSweepOutcome {
    fun hydrationFor(subtaskId: Int) = identity?.let { expectedIdentity ->
      val expectedProvenance = requireNotNull(provenance)
      skillbill.ports.goalrunner.model.GoalChildPlanningHydrationRequest(
        expectedIdentity,
        expectedProvenance,
        descriptors.single { it.subtaskId == subtaskId },
      )
    }
  }

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
