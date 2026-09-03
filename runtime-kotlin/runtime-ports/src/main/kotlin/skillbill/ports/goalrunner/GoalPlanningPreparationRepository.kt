package skillbill.ports.goalrunner

import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState.BLOCKED
import skillbill.goalrunner.model.GoalPlanningStatusState.NOT_STARTED
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalPlanningPreparationStatus
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint

interface SharedGoalPreplanRepository {
  fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint): Unit =
    error("Shared goal preplan checkpointing is not implemented by this repository.")

  fun replaceSharedPreplan(
    checkpoint: SharedGoalPreplanCheckpoint,
    expectedPayloadSha256: String,
    cascadePlanSubtaskIds: List<Int> = emptyList(),
  ): Unit = error("Shared goal preplan replacement is not implemented by this repository.")

  fun advanceSharedPreplanProvenance(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
    provenance: GoalPlanningContractProvenance,
  ): Unit = error("Shared goal preplan provenance advance is not implemented by this repository.")

  fun cascadeSiblingPlansAfterSharedPreplanRefresh(
    parentGoalWorkflowId: String,
    cascadePlanSubtaskIds: List<Int>,
  ): List<Int> = error("Shared-preplan refresh plan cascade is not implemented by this repository.")

  fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint?

  fun deleteSharedPreplan(identity: GoalPlanningIdentity, expectedPayloadSha256: String): Int =
    error("Shared goal preplan deletion is not implemented by this repository.")

  fun invalidateSharedPreplan(identity: GoalPlanningIdentity, expectedPayloadSha256: String): Int =
    error("Shared goal preplan invalidation is not implemented by this repository.")

  fun listPreparedPlanSubtaskIds(parentGoalWorkflowId: String): List<Int> = emptyList()

  fun hasPreparedSharedPreplan(parentGoalWorkflowId: String): Boolean = false

  fun sharedPreplanPayloadSha256(parentGoalWorkflowId: String): String? = null
}

interface GoalSubtaskPlanRepository {
  fun boundedStatus(
    parentGoalWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int? = null,
    blockedReason: String? = null,
  ): GoalPlanningStatusSnapshot = GoalPlanningStatusSnapshot(
    state = if (blockedReason == null) {
      NOT_STARTED
    } else {
      BLOCKED
    },
    sharedPreplanPrepared = false,
    plannedSubtaskCount = 0,
    totalSubtaskCount = orderedSubtaskIds.size,
    currentPlanningSubtaskId = blockedSubtaskId ?: orderedSubtaskIds.firstOrNull(),
    reason = blockedReason ?: "Goal planning has not started.",
  )

  fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint): Unit =
    error("Goal subtask plan checkpointing is not implemented by this repository.")

  fun replaceSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint): Unit =
    error("Goal subtask plan replacement is not implemented by this repository.")

  fun deleteSubtaskPlan(parentGoalWorkflowId: String, subtaskId: Int): Int =
    error("Goal subtask plan deletion is not implemented by this repository.")

  fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint?

  fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): List<GoalSubtaskPlanCheckpoint>

  fun preparedPlanCount(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): Int = listSubtaskPlansOrdered(expectedIdentity, orderedDescriptors).size

  fun firstMissingPlan(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): Int? {
    val prepared = listSubtaskPlansOrdered(expectedIdentity, orderedDescriptors).mapTo(mutableSetOf()) { it.subtaskId }
    return orderedDescriptors.firstOrNull { it.subtaskId !in prepared }?.subtaskId
  }
}

interface NormalizedGoalPlanningPreparationRepository :
  SharedGoalPreplanRepository,
  GoalSubtaskPlanRepository

interface LegacyGoalPlanningPreparationRepository {
  fun markPrepared(record: GoalPlanningPreparationRecord)

  fun findByGoalAndSubtask(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord?

  fun listPreparedByGoalOrdered(parentGoalWorkflowId: String): List<GoalPlanningPreparationRecord>

  fun preparedCount(parentGoalWorkflowId: String): Int

  fun firstMissingOrIncompleteSubtask(parentGoalWorkflowId: String, orderedSubtaskIds: List<Int>): Int?

  fun preparedStatus(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationStatus?

  fun deleteByGoal(parentGoalWorkflowId: String): Int
}

interface GoalPlanningPreparationRepository :
  NormalizedGoalPlanningPreparationRepository,
  LegacyGoalPlanningPreparationRepository
