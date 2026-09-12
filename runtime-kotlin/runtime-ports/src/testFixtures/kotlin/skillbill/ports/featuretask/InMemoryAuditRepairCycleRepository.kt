package skillbill.ports.featuretask

import skillbill.error.AuditRepairCycleConflictError
import skillbill.error.InvalidAuditRepairCycleSchemaError
import skillbill.ports.featuretask.model.AuditRepairStatusSnapshot
import skillbill.workflow.taskruntime.model.AuditRepairCycle
import skillbill.workflow.taskruntime.model.AuditRepairCheckpoint
import skillbill.workflow.taskruntime.model.AuditRepairIdentity
import skillbill.workflow.taskruntime.model.AuditRepairLaunchBinding
import skillbill.workflow.taskruntime.model.AuditRepairRevision
import skillbill.workflow.taskruntime.model.AuditRepairStage

class InMemoryAuditRepairCycleRepository : AuditRepairCycleRepository {
  private val cycles = mutableMapOf<Pair<String, String>, AuditRepairCycle>()
  private val launches = mutableMapOf<Triple<String, Int, String>, AuditRepairLaunchBinding>()

  override fun <T> withStageOwner(identity: AuditRepairIdentity, action: (AuditRepairCycleRepository) -> T): T {
    validateStageOwner(identity)
    val before = cycles.toMap()
    return try {
      action(this)
    } catch (error: Exception) {
      cycles.clear()
      cycles.putAll(before)
      throw error
    }
  }

  override fun start(cycle: AuditRepairCycle): AuditRepairCycle {
    cycle.validate()
    if (cycle.revisions.size != 1) {
      throw InvalidAuditRepairCycleSchemaError("A new cycle must contain only its diagnosis.")
    }
    val binding = launches[Triple(cycle.identity.workflowId, cycle.identity.auditAttempt, cycle.identity.cycleId)]
    if (binding != null && binding.identity != cycle.identity) {
      throw AuditRepairCycleConflictError("Audit diagnosis does not match its durable launch binding.")
    }
    if (binding == null && launches.any { it.value.workflowId == cycle.identity.workflowId }) {
      throw AuditRepairCycleConflictError("Audit diagnosis has no durable launch binding.")
    }
    val existing = findForAttempt(cycle.identity.workflowId, cycle.identity.auditAttempt)
    if (existing != null) {
      if (!sameDiagnosis(existing, cycle)) {
        throw AuditRepairCycleConflictError("Conflicting diagnosis.")
      }
      return existing
    }
    if (findActive(cycle.identity.workflowId) != null) {
      throw AuditRepairCycleConflictError("An active audit-repair cycle already owns this workflow.")
    }
    cycles[cycle.identity.workflowId to cycle.identity.cycleId] = cycle
    return cycle
  }

  private fun sameDiagnosis(left: AuditRepairCycle, right: AuditRepairCycle): Boolean = left.copy(
    identity = right.identity,
    revisions = listOf(left.revisions.first().copy(recordedAt = "")),
  ) == right.copy(revisions = listOf(right.revisions.first().copy(recordedAt = "")))

  override fun find(workflowId: String, cycleId: String): AuditRepairCycle? = cycles[workflowId to cycleId]

  override fun findForAttempt(workflowId: String, auditAttempt: Int): AuditRepairCycle? = cycles.values
    .singleOrNull { it.identity.workflowId == workflowId && it.identity.auditAttempt == auditAttempt }

  override fun statusSnapshot(workflowId: String): AuditRepairStatusSnapshot? = null

  override fun findActive(workflowId: String): AuditRepairCycle? = cycles.values
    .filter { it.identity.workflowId == workflowId && it.current.stage != AuditRepairStage.SATISFIED }
    .maxByOrNull { it.identity.auditAttempt }

  override fun bindLaunch(binding: AuditRepairLaunchBinding): AuditRepairLaunchBinding {
    val key = Triple(binding.workflowId, binding.auditAttempt, binding.cycleId)
    val existing = launches[key]
    if (existing != null && (
        existing.workflowId != binding.workflowId || existing.auditAttempt != binding.auditAttempt ||
          existing.cycleId != binding.cycleId || existing.executionId != binding.executionId ||
          existing.requestedSessionId != binding.requestedSessionId
        )
    ) {
      throw AuditRepairCycleConflictError("Conflicting audit-repair launch binding.")
    }
    val activeCycle = findActive(binding.workflowId)
    if (activeCycle != null && activeCycle.identity.cycleId != binding.cycleId) {
      throw AuditRepairCycleConflictError("An active audit-repair cycle already owns this workflow.")
    }
    launches[key] = existing?.copy(
      ownerToken = binding.ownerToken,
      fencingGeneration = binding.fencingGeneration,
      checkpoint = existing.checkpoint ?: binding.checkpoint,
    ) ?: binding
    return launches[key]!!
  }

  override fun recordProviderSession(
    identity: AuditRepairIdentity,
    providerSessionId: String,
  ): AuditRepairLaunchBinding {
    val key = Triple(identity.workflowId, identity.auditAttempt, identity.cycleId)
    val existing = launches[key]
      ?: throw AuditRepairCycleConflictError("Audit-repair launch binding is absent.")
    if (existing.executionId != identity.executionId || existing.requestedSessionId != identity.sessionId) {
      throw AuditRepairCycleConflictError("Provider session does not match the launch binding.")
    }
    if (existing.ownerToken != identity.ownerToken || existing.fencingGeneration != identity.fencingGeneration) {
      throw AuditRepairCycleConflictError("Audit-repair launch binding is stale.")
    }
    if (existing.providerSessionId != null && existing.providerSessionId != providerSessionId) {
      throw AuditRepairCycleConflictError("Provider session identity changed during a launch.")
    }
    val updated = existing.copy(providerSessionId = providerSessionId)
    launches[key] = updated
    return updated
  }

  override fun findLaunchBinding(
    workflowId: String,
    executionId: String,
    requestedSessionId: String,
  ): AuditRepairLaunchBinding? = launches.values.singleOrNull {
    it.workflowId == workflowId && it.executionId == executionId && it.requestedSessionId == requestedSessionId
  }

  override fun validateStageOwner(identity: AuditRepairIdentity) {
    val binding = launches[Triple(identity.workflowId, identity.auditAttempt, identity.cycleId)]
      ?: throw AuditRepairCycleConflictError("Audit stage caller has no durable launch binding.")
    if (binding.executionId != identity.executionId || binding.requestedSessionId != identity.sessionId ||
      binding.ownerToken != identity.ownerToken || binding.fencingGeneration != identity.fencingGeneration
    ) {
      throw AuditRepairCycleConflictError("Audit stage caller does not match its durable launch binding.")
    }
  }

  override fun advance(
    identity: AuditRepairIdentity,
    expectedRevision: Int,
    revision: AuditRepairRevision,
  ): AuditRepairCycle {
    val existing = find(identity.workflowId, identity.cycleId)
      ?: throw AuditRepairCycleConflictError("Missing cycle.")
    if (existing.identity.auditAttempt != identity.auditAttempt) {
      throw AuditRepairCycleConflictError("Conflicting audit attempt.")
    }
    if (!sameExecutionBinding(existing.identity, identity)) throw AuditRepairCycleConflictError("Conflicting owner.")
    val next = existing.copy(identity = identity).append(expectedRevision, revision)
    if (next.revisions.size == existing.revisions.size) return existing
    return next.also {
      cycles[identity.workflowId to identity.cycleId] = it
    }
  }

  override fun attachCheckpoint(
    identity: AuditRepairIdentity,
    expectedRevision: Int,
    checkpointIntent: String,
    checkpoint: AuditRepairCheckpoint,
  ): AuditRepairCycle {
    validateStageOwner(identity)
    val existing = find(identity.workflowId, identity.cycleId)
      ?: throw AuditRepairCycleConflictError("Missing cycle.")
    if (!sameExecutionBinding(existing.identity, identity)) throw AuditRepairCycleConflictError("Conflicting owner.")
    return existing.attachCheckpoint(expectedRevision, checkpointIntent, checkpoint).also {
      cycles[identity.workflowId to identity.cycleId] = it
    }
  }

  private fun sameExecutionBinding(left: AuditRepairIdentity, right: AuditRepairIdentity): Boolean =
    left.workflowId == right.workflowId &&
      left.auditAttempt == right.auditAttempt && left.executionId == right.executionId &&
      left.sessionId == right.sessionId && left.cycleId == right.cycleId
}
