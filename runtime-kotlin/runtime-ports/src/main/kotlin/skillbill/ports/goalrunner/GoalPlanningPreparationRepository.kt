package skillbill.ports.goalrunner

import skillbill.workflow.engine.model.WorkflowId

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
import skillbill.workflow.decomposition.model.SubtaskId

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
    parentGoalWorkflowId: WorkflowId,
    cascadePlanSubtaskIds: List<Int>,
  ): List<Int> = error("Shared-preplan refresh plan cascade is not implemented by this repository.")

  fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint?

  fun deleteSharedPreplan(identity: GoalPlanningIdentity, expectedPayloadSha256: String): Int =
    error("Shared goal preplan deletion is not implemented by this repository.")

  fun invalidateSharedPreplan(identity: GoalPlanningIdentity, expectedPayloadSha256: String): Int =
    error("Shared goal preplan invalidation is not implemented by this repository.")

  fun listPreparedPlanSubtaskIds(parentGoalWorkflowId: WorkflowId): List<Int>

  fun hasPreparedSharedPreplan(parentGoalWorkflowId: WorkflowId): Boolean

  fun sharedPreplanPayloadSha256(parentGoalWorkflowId: WorkflowId): String?
}

interface GoalSubtaskPlanRepository {
  fun boundedStatus(
    parentGoalWorkflowId: WorkflowId,
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

  fun deleteSubtaskPlan(parentGoalWorkflowId: WorkflowId, subtaskId: SubtaskId): Int =
    error("Goal subtask plan deletion is not implemented by this repository.")

  fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: SubtaskId,
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
  ): SubtaskId? {
    val prepared = listSubtaskPlansOrdered(expectedIdentity, orderedDescriptors).mapTo(mutableSetOf()) { it.subtaskId }
    return orderedDescriptors.firstOrNull { it.subtaskId !in prepared }?.subtaskId
  }
}

interface NormalizedGoalPlanningPreparationRepository :
  SharedGoalPreplanRepository,
  GoalSubtaskPlanRepository

interface LegacyGoalPlanningPreparationRepository {
  fun markPrepared(record: GoalPlanningPreparationRecord)

  fun findByGoalAndSubtask(parentGoalWorkflowId: WorkflowId, subtaskId: SubtaskId): GoalPlanningPreparationRecord?

  fun listPreparedByGoalOrdered(parentGoalWorkflowId: WorkflowId): List<GoalPlanningPreparationRecord>

  fun preparedCount(parentGoalWorkflowId: WorkflowId): Int

  fun firstMissingOrIncompleteSubtask(parentGoalWorkflowId: WorkflowId, orderedSubtaskIds: List<Int>): Int?

  fun preparedStatus(parentGoalWorkflowId: WorkflowId, subtaskId: SubtaskId): GoalPlanningPreparationStatus?

  fun deleteByGoal(parentGoalWorkflowId: WorkflowId): Int
}

interface GoalPlanningPreparationRepository :
  NormalizedGoalPlanningPreparationRepository,
  LegacyGoalPlanningPreparationRepository
