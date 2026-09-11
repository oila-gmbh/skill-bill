package skillbill.engine.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.runtime.RuntimeSingleton
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.workflowStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Confirmed-dead verdict for crash reconciliation, derived only from the injected supervisor's
 * process inspection. Only [FeatureTaskRuntimeProcessInspection.NotRunning] is confirmed dead;
 * [ExactLive] is alive, and OwnershipMismatch/Unsupported are ambiguous evidence that must never
 * trigger reconciliation. This keeps liveness detection behind the injectable supervisor port with
 * no agent-identity branching (AC-005).
 */
@RuntimeSingleton
@Inject
class FeatureTaskRuntimeWorkerCoordinator(
  private val database: DatabaseSessionFactory,
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val clock: Clock,
) {
  fun <T> runOwned(workflowId: String, block: () -> T): T {
    val ownership = acquireOrRecover(workflowId)
    val heartbeats = supervisor.startHeartbeat(heartbeatPlan(workflowId)) { heartbeat(ownership) }
    val result = try {
      block()
    } finally {
      heartbeats.stop()
      database.transaction {
        it.workflowStates.releaseFeatureTaskRuntimeWorker(workflowId, ownership.ownerToken, ownership.generation)
      }
    }
    // Checked after the block rather than inside the finally so a failing block reports its own cause.
    heartbeats.fencingLostReason()?.let { reason ->
      error("Worker for workflow '$workflowId' lost lease fencing mid-phase: $reason")
    }
    return result
  }

  private fun heartbeatPlan(workflowId: String) = FeatureTaskRuntimeHeartbeatPlan(
    label = workflowId,
    intervalSeconds = HEARTBEAT_SECONDS,
    leaseSeconds = LEASE_DURATION.seconds,
  )

  private fun acquireOrRecover(workflowId: String): FeatureTaskRuntimeWorkerOwnership {
    val existing = database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) }
    return if (existing == null) acquireUnowned(workflowId) else recoverOwned(existing)
  }

  private fun acquireUnowned(workflowId: String): FeatureTaskRuntimeWorkerOwnership {
    repeat(UNOWNED_ACQUIRE_ATTEMPTS) {
      when (val claim = claimUnowned(workflowId)) {
        is UnownedClaim.Owned -> return claim.ownership
        is UnownedClaim.Recover -> return recoverOwned(claim.existing)
        UnownedClaim.Lost -> Unit
      }
    }
    error("Workflow '$workflowId' changed before worker ownership could be acquired.")
  }

  private fun claimUnowned(workflowId: String): UnownedClaim = database.selfManagedWrite { unitOfWork ->
    val existing = unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId)
    if (existing != null) return@selfManagedWrite UnownedClaim.Recover(existing)
    val row = unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)
      ?: throw InvalidWorkflowStateSchemaError("Feature-task runtime worker workflow '$workflowId' is missing.")
    if (row.workflowStatus.workflowStatus() in TERMINAL_WORKFLOW_STATUSES) {
      error(
        "Cannot acquire worker ownership for terminal workflow '$workflowId' (${row.workflowStatus}).",
      )
    }
    val ownership = newOwnership(
      workflowId,
      generation = 1,
      phaseId = row.currentStepId,
      phaseAttempt = 1,
    )
    if (unitOfWork.workflowStates.acquireFeatureTaskRuntimeWorker(ownership, row.updatedAt)) {
      UnownedClaim.Owned(ownership)
    } else {
      UnownedClaim.Lost
    }
  }

  private fun recoverOwned(existing: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeWorkerOwnership {
    when (val inspection = supervisor.inspect(existing)) {
      FeatureTaskRuntimeProcessInspection.ExactLive -> stopExactWorker(existing)
      FeatureTaskRuntimeProcessInspection.NotRunning -> Unit
      is FeatureTaskRuntimeProcessInspection.OwnershipMismatch ->
        if (leaseIsActive(existing)) error(inspection.reason)
      is FeatureTaskRuntimeProcessInspection.Unsupported ->
        if (leaseIsActive(existing)) error(inspection.reason)
    }
    val reserved = database.transaction {
      it.workflowStates.reserveFeatureTaskRuntimeWorkerTakeover(
        existing.workflowId,
        existing.ownerToken,
        existing.generation,
      )
    }
    if (!reserved) error("Concurrent continuation already claimed workflow '${existing.workflowId}'.")
    val replacement = newOwnership(
      existing.workflowId,
      existing.generation + 1,
      existing.phaseId,
      existing.phaseAttempt + 1,
    )
    val transferred = database.transaction {
      it.workflowStates.transferFeatureTaskRuntimeWorker(replacement, existing.ownerToken, existing.generation)
    }
    if (!transferred) error("Worker takeover fencing changed for workflow '${existing.workflowId}'.")
    return replacement
  }

  private fun leaseIsActive(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean =
    Instant.parse(ownership.expiresAt).isAfter(clock.instant())

  private fun stopExactWorker(existing: FeatureTaskRuntimeWorkerOwnership) {
    supervisor.terminateGracefully(existing)
    repeat(GRACE_POLLS) {
      if (supervisor.inspect(existing) == FeatureTaskRuntimeProcessInspection.NotRunning) return
      supervisor.pause(GRACE_POLL_MILLIS)
    }
    if (supervisor.inspect(existing) == FeatureTaskRuntimeProcessInspection.ExactLive) {
      supervisor.terminateForcibly(existing)
    }
    if (supervisor.inspect(existing) != FeatureTaskRuntimeProcessInspection.NotRunning) {
      error("Exact worker for workflow '${existing.workflowId}' could not be stopped safely.")
    }
  }

  private fun heartbeat(base: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeHeartbeatTick {
    val now = clock.instant()
    val updated = base.copy(heartbeatAt = now.toString(), expiresAt = now.plus(LEASE_DURATION).toString())
    val persisted = database.transaction {
      it.workflowStates.heartbeatFeatureTaskRuntimeWorker(updated)
    }
    return if (persisted) {
      FeatureTaskRuntimeHeartbeatTick.Renewed
    } else {
      FeatureTaskRuntimeHeartbeatTick.FencingLost(
        "worker lease fencing was lost for workflow '${base.workflowId}'",
      )
    }
  }

  private fun newOwnership(
    workflowId: String,
    generation: Long,
    phaseId: String,
    phaseAttempt: Int,
  ): FeatureTaskRuntimeWorkerOwnership {
    val process = supervisor.currentProcess()
    val now = clock.instant()
    return FeatureTaskRuntimeWorkerOwnership(
      workflowId = workflowId,
      generation = generation,
      ownerToken = UUID.randomUUID().toString(),
      hostIdentity = process.hostIdentity,
      bootIdentity = process.bootIdentity,
      pid = process.pid,
      processBirthToken = process.processBirthToken,
      leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
      heartbeatAt = now.toString(),
      expiresAt = now.plus(LEASE_DURATION).toString(),
      phaseId = phaseId,
      phaseAttempt = phaseAttempt,
    )
  }

  private companion object {
    val LEASE_DURATION: Duration = Duration.ofSeconds(30)
    const val HEARTBEAT_SECONDS: Long = 10
    const val GRACE_POLLS: Int = 20
    const val GRACE_POLL_MILLIS: Long = 100
    const val UNOWNED_ACQUIRE_ATTEMPTS: Int = 3
    val TERMINAL_WORKFLOW_STATUSES = setOf(WorkflowStatus.COMPLETED, WorkflowStatus.FAILED, WorkflowStatus.ABANDONED)
  }

  private sealed class UnownedClaim {
    class Owned(val ownership: FeatureTaskRuntimeWorkerOwnership) : UnownedClaim()
    class Recover(val existing: FeatureTaskRuntimeWorkerOwnership) : UnownedClaim()
    data object Lost : UnownedClaim()
  }
}
