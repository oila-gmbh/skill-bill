package skillbill.db.workflow

import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalPlanningPreparationStatus
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import java.sql.Connection

class GoalPlanningPreparationStore(
  connection: Connection,
) : GoalPlanningPreparationRepository {
  private val statusProjection = GoalPlanningStatusProjectionSql(connection)
  private val sharedPreplan = GoalSharedPreplanSql(connection)
  private val subtaskPlan = GoalSubtaskPlanSql(connection, sharedPreplan)
  private val preparationRecord = GoalPlanningPreparationRecordSql(connection)

  override fun boundedStatus(
    parentGoalWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
  ): GoalPlanningStatusSnapshot = translateSqlFailure(parentGoalWorkflowId, blockedSubtaskId ?: 0) {
    statusProjection.boundedStatus(
      parentGoalWorkflowId,
      orderedSubtaskIds,
      blockedSubtaskId,
      blockedReason,
    )
  }

  override fun hasPreparedSharedPreplan(parentGoalWorkflowId: String): Boolean = translateSqlFailure(
    parentGoalWorkflowId,
    0,
  ) {
    statusProjection.hasPreparedSharedPreplan(parentGoalWorkflowId)
  }

  override fun listPreparedPlanSubtaskIds(parentGoalWorkflowId: String): List<Int> = translateSqlFailure(
    parentGoalWorkflowId,
    0,
  ) {
    statusProjection.listPreparedPlanSubtaskIds(parentGoalWorkflowId)
  }

  override fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) {
    translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, 0) {
      sharedPreplan.checkpointSharedPreplan(checkpoint)
    }
  }

  override fun replaceSharedPreplan(
    checkpoint: SharedGoalPreplanCheckpoint,
    expectedPayloadSha256: String,
    cascadePlanSubtaskIds: List<Int>,
  ) {
    translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, 0) {
      sharedPreplan.replaceSharedPreplan(checkpoint, expectedPayloadSha256, cascadePlanSubtaskIds)
    }
  }

  override fun advanceSharedPreplanProvenance(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
    provenance: GoalPlanningContractProvenance,
  ) {
    translateSqlFailure(identity.parentGoalWorkflowId, 0) {
      sharedPreplan.advanceSharedPreplanProvenance(identity, expectedPayloadSha256, provenance)
    }
  }

  override fun cascadeSiblingPlansAfterSharedPreplanRefresh(
    parentGoalWorkflowId: String,
    cascadePlanSubtaskIds: List<Int>,
  ): List<Int> = translateSqlFailure(parentGoalWorkflowId, 0) {
    sharedPreplan.cascadeSiblingPlansAfterSharedPreplanRefresh(parentGoalWorkflowId, cascadePlanSubtaskIds)
  }

  override fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint? =
    translateSqlFailure(expectedIdentity.parentGoalWorkflowId, 0) {
      sharedPreplan.findSharedPreplan(expectedIdentity)
    }

  override fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, checkpoint.subtaskId) {
      subtaskPlan.checkpointSubtaskPlan(checkpoint)
    }
  }

  override fun replaceSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, checkpoint.subtaskId) {
      subtaskPlan.replaceSubtaskPlan(checkpoint)
    }
  }

  override fun deleteSubtaskPlan(parentGoalWorkflowId: String, subtaskId: Int): Int =
    translateSqlFailure(parentGoalWorkflowId, subtaskId) {
      subtaskPlan.deleteSubtaskPlan(parentGoalWorkflowId, subtaskId)
    }

  override fun deleteSharedPreplan(identity: GoalPlanningIdentity, expectedPayloadSha256: String): Int =
    translateSqlFailure(identity.parentGoalWorkflowId, 0) {
      sharedPreplan.deleteSharedPreplan(identity, expectedPayloadSha256)
    }

  override fun invalidateSharedPreplan(identity: GoalPlanningIdentity, expectedPayloadSha256: String): Int =
    translateSqlFailure(identity.parentGoalWorkflowId, 0) {
      sharedPreplan.invalidateSharedPreplan(identity, expectedPayloadSha256)
    }

  override fun sharedPreplanPayloadSha256(parentGoalWorkflowId: String): String? =
    translateSqlFailure(parentGoalWorkflowId, 0) {
      sharedPreplan.sharedPreplanPayloadSha256(parentGoalWorkflowId)
    }

  override fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint? = translateSqlFailure(expectedIdentity.parentGoalWorkflowId, subtaskId) {
    subtaskPlan.findSubtaskPlan(expectedIdentity, subtaskId, governedSubSpecPath)
  }

  override fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): List<GoalSubtaskPlanCheckpoint> = translateSqlFailure(expectedIdentity.parentGoalWorkflowId, 0) {
    subtaskPlan.listSubtaskPlansOrdered(expectedIdentity, orderedDescriptors)
  }

  override fun markPrepared(record: GoalPlanningPreparationRecord) {
    preparationRecord.markPrepared(record)
  }

  override fun findByGoalAndSubtask(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord? =
    preparationRecord.findByGoalAndSubtask(parentGoalWorkflowId, subtaskId)

  override fun listPreparedByGoalOrdered(parentGoalWorkflowId: String): List<GoalPlanningPreparationRecord> =
    preparationRecord.listPreparedByGoalOrdered(parentGoalWorkflowId)

  override fun preparedCount(parentGoalWorkflowId: String): Int =
    preparationRecord.preparedCount(parentGoalWorkflowId)

  override fun firstMissingOrIncompleteSubtask(parentGoalWorkflowId: String, orderedSubtaskIds: List<Int>): Int? =
    preparationRecord.firstMissingOrIncompleteSubtask(parentGoalWorkflowId, orderedSubtaskIds)

  override fun preparedStatus(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationStatus? =
    preparationRecord.preparedStatus(parentGoalWorkflowId, subtaskId)

  override fun deleteByGoal(parentGoalWorkflowId: String): Int {
    val plans = subtaskPlan.deleteAllByGoal(parentGoalWorkflowId)
    val shared = sharedPreplan.deleteAllByGoal(parentGoalWorkflowId)
    return plans + shared + preparationRecord.deletePreparedByGoal(parentGoalWorkflowId)
  }
}
