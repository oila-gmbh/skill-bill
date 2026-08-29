package skillbill.db.workflow

import skillbill.ports.goalrunner.SharedGoalPreplanRepository
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint

internal class SharedGoalPreplanStore(
  private val statusProjection: GoalPlanningStatusProjectionSql,
  private val sharedPreplan: GoalSharedPreplanSql,
) : SharedGoalPreplanRepository {
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
}
