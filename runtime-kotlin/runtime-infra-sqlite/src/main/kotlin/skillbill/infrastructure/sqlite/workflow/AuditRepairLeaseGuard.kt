package skillbill.infrastructure.sqlite.workflow

import skillbill.error.AuditRepairCycleConflictError
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AuditRepairIdentity
import java.sql.Connection
import java.time.Clock
import java.time.Instant

internal class AuditRepairLeaseGuard(private val connection: Connection, private val clock: Clock) {
  fun validate(identity: AuditRepairIdentity) {
    val activeAudit = connection.prepareStatement(
      """
      SELECT 1 FROM feature_task_workflows
      WHERE workflow_id = ? AND mode = 'runtime' AND workflow_status = 'running' AND current_step_id = ?
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, identity.workflowId)
      statement.setString(parameterIndex++, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
      statement.executeQuery().use { it.next() }
    }
    if (!activeAudit) {
      throw AuditRepairCycleConflictError("Workflow is not running its audit phase.")
    }
    validateLease(identity)
  }

  private fun validateLease(identity: AuditRepairIdentity) {
    val ownership = connection.featureTaskRuntimeWorkerOwnership(identity.workflowId)
      ?: throw AuditRepairCycleConflictError("Audit worker lease is absent.")
    val fenceMatches = ownership.ownerToken == identity.ownerToken && ownership.generation == identity.fencingGeneration
    val activeWorker = ownership.leaseState == FeatureTaskRuntimeWorkerLeaseState.ACTIVE
    if (!fenceMatches || !activeWorker || !Instant.parse(ownership.expiresAt).isAfter(clock.instant())) {
      throw AuditRepairCycleConflictError("Audit worker lease is stale or belongs to another attempt.")
    }
  }
}
