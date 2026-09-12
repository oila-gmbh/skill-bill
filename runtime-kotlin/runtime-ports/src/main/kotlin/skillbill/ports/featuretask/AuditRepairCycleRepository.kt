package skillbill.ports.featuretask

import skillbill.workflow.taskruntime.model.AuditRepairCycle
import skillbill.workflow.taskruntime.model.AuditRepairCheckpoint
import skillbill.workflow.taskruntime.model.AuditRepairIdentity
import skillbill.workflow.taskruntime.model.AuditRepairLaunchBinding
import skillbill.workflow.taskruntime.model.AuditRepairRevision

interface AuditRepairCycleRepository : AuditRepairStatusSnapshotRepository {
  fun start(cycle: AuditRepairCycle): AuditRepairCycle

  fun find(workflowId: String, cycleId: String): AuditRepairCycle?

  fun findForAttempt(workflowId: String, auditAttempt: Int): AuditRepairCycle?

  fun findActive(workflowId: String): AuditRepairCycle?

  fun bindLaunch(binding: AuditRepairLaunchBinding): AuditRepairLaunchBinding

  fun recordProviderSession(identity: AuditRepairIdentity, providerSessionId: String): AuditRepairLaunchBinding

  fun findLaunchBinding(workflowId: String, executionId: String, requestedSessionId: String): AuditRepairLaunchBinding?

  fun validateStageOwner(identity: AuditRepairIdentity)

  fun <T> withStageOwner(identity: AuditRepairIdentity, action: (AuditRepairCycleRepository) -> T): T

  fun advance(identity: AuditRepairIdentity, expectedRevision: Int, revision: AuditRepairRevision): AuditRepairCycle

  fun attachCheckpoint(
    identity: AuditRepairIdentity,
    expectedRevision: Int,
    checkpointIntent: String,
    checkpoint: AuditRepairCheckpoint,
  ): AuditRepairCycle
}
