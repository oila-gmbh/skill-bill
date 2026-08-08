package skillbill.application.model

import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.persistence.model.GoalPlanningContractProvenance
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

sealed interface GoalPlanningSweepOutcome {
  data class PreparedAll(
    val identity: GoalPlanningIdentity? = null,
    val provenance: GoalPlanningContractProvenance? = null,
    val descriptors: List<GovernedGoalSubtaskDescriptor> = emptyList(),
  ) : GoalPlanningSweepOutcome {
    fun hydrationFor(subtaskId: Int) = identity?.let { expectedIdentity ->
      val expectedProvenance = requireNotNull(provenance)
      val descriptor = descriptors.singleOrNull { it.subtaskId == subtaskId } ?: return@let null
      skillbill.ports.goalrunner.model.GoalChildPlanningHydrationRequest(
        expectedIdentity,
        expectedProvenance,
        descriptor,
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
  data class Captured(
    val payload: String,
    val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  ) : GoalPlanningPhaseProduction

  data class SchemaRejected(
    val reason: String,
  ) : GoalPlanningPhaseProduction

  /**
   * The launch itself succeeded and the provider returned nothing usable. Distinct from
   * [SchemaRejected] because there is no rejected output to remediate: relaunching the identical
   * prompt is the whole fix, and the prior-failure remediation text would describe output that was
   * never produced.
   */
  data class EmptyProviderTurn(
    val reason: String,
    val evidence: GoalPlanningEmptyTurnEvidence,
  ) : GoalPlanningPhaseProduction

  data class Stopped(val outcome: GoalPlanningSweepOutcome.Stopped) : GoalPlanningPhaseProduction
}

/**
 * Launch-shaped facts about a zero-exit planning turn that yielded nothing. These are the facts that
 * separate a provider that answered nothing from a harvest that lost the answer, and they are the
 * only evidence the run retains once the undecodable transport is dropped.
 */
data class GoalPlanningEmptyTurnEvidence(
  val agentId: String,
  val durationMs: Long,
  val exitStatus: Int?,
  val inputTokens: Long?,
  val outputTokens: Long?,
  val assistantEventCount: Int?,
  val rawOutputPreview: String?,
) {
  /** Payload-free one-line summary safe to surface to an operator and to store as a reason. */
  fun summary(): String = buildString {
    append("EmptyProviderTurn: agent=")
    append(agentId)
    append(" durationMs=")
    append(durationMs)
    append(" exitStatus=")
    append(exitStatus ?: "none")
    append(" assistantEvents=")
    append(assistantEventCount ?: "unknown")
    append(" inputTokens=")
    append(inputTokens ?: "unknown")
    append(" outputTokens=")
    append(outputTokens ?: "unknown")
  }
}

data class GoalPlanningRejectionRecord(
  val parentWorkflowId: String,
  val issueKey: String,
  val dbPathOverride: String?,
  val phaseId: String,
  val subtaskId: Int,
  val attempt: Int,
  val rule: String,
  val reason: String,
  val agentId: String,
  val rawEvidence: String,
)
