package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeProcessInspection
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Inject
class FeatureTaskRuntimeWorkerCoordinator(
  private val database: DatabaseSessionFactory,
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor,
) {
  fun <T> runOwned(workflowId: String, dbOverride: String?, block: () -> T): T {
    val ownership = acquireOrRecover(workflowId, dbOverride)
    val heartbeats = Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "skill-bill-worker-heartbeat").apply { isDaemon = true }
    }
    heartbeats.scheduleAtFixedRate(
      { heartbeat(ownership, dbOverride) },
      HEARTBEAT_SECONDS,
      HEARTBEAT_SECONDS,
      TimeUnit.SECONDS,
    )
    return try {
      block()
    } finally {
      heartbeats.shutdownNow()
      database.transaction(dbOverride) {
        it.workflowStates.releaseFeatureTaskRuntimeWorker(workflowId, ownership.ownerToken, ownership.generation)
      }
    }
  }

  private fun acquireOrRecover(workflowId: String, dbOverride: String?): FeatureTaskRuntimeWorkerOwnership {
    val existing = database.read(dbOverride) { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) }
    return if (existing == null) acquireUnowned(workflowId, dbOverride) else recoverOwned(existing, dbOverride)
  }

  private fun acquireUnowned(workflowId: String, dbOverride: String?): FeatureTaskRuntimeWorkerOwnership {
    val row = database.read(dbOverride) { it.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId) }
      ?: throw InvalidWorkflowStateSchemaError("Feature-task runtime worker workflow '$workflowId' is missing.")
    val ownership = newOwnership(workflowId, generation = 1, phaseId = row.currentStepId, phaseAttempt = 1)
    val acquired = database.read(dbOverride) {
      it.workflowStates.acquireFeatureTaskRuntimeWorker(ownership, row.updatedAt)
    }
    if (!acquired) error("Workflow '$workflowId' changed before worker ownership could be acquired.")
    return ownership
  }

  private fun recoverOwned(
    existing: FeatureTaskRuntimeWorkerOwnership,
    dbOverride: String?,
  ): FeatureTaskRuntimeWorkerOwnership {
    when (val inspection = supervisor.inspect(existing)) {
      FeatureTaskRuntimeProcessInspection.ExactLive -> stopExactWorker(existing)
      FeatureTaskRuntimeProcessInspection.NotRunning -> Unit
      is FeatureTaskRuntimeProcessInspection.OwnershipMismatch -> error(inspection.reason)
      is FeatureTaskRuntimeProcessInspection.Unsupported -> error(inspection.reason)
    }
    val reserved = database.transaction(dbOverride) {
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
    val transferred = database.transaction(dbOverride) {
      it.workflowStates.transferFeatureTaskRuntimeWorker(replacement, existing.ownerToken, existing.generation)
    }
    if (!transferred) error("Worker takeover fencing changed for workflow '${existing.workflowId}'.")
    return replacement
  }

  private fun stopExactWorker(existing: FeatureTaskRuntimeWorkerOwnership) {
    supervisor.terminateGracefully(existing)
    repeat(GRACE_POLLS) {
      if (supervisor.inspect(existing) == FeatureTaskRuntimeProcessInspection.NotRunning) return
      Thread.sleep(GRACE_POLL_MILLIS)
    }
    if (supervisor.inspect(existing) == FeatureTaskRuntimeProcessInspection.ExactLive) {
      supervisor.terminateForcibly(existing)
    }
    if (supervisor.inspect(existing) != FeatureTaskRuntimeProcessInspection.NotRunning) {
      error("Exact worker for workflow '${existing.workflowId}' could not be stopped safely.")
    }
  }

  private fun heartbeat(base: FeatureTaskRuntimeWorkerOwnership, dbOverride: String?) {
    val now = Instant.now()
    val updated = base.copy(heartbeatAt = now.toString(), expiresAt = now.plus(LEASE_DURATION).toString())
    val persisted = database.transaction(dbOverride) {
      it.workflowStates.heartbeatFeatureTaskRuntimeWorker(updated)
    }
    check(persisted) { "Worker lease fencing was lost for workflow '${base.workflowId}'." }
  }

  private fun newOwnership(
    workflowId: String,
    generation: Long,
    phaseId: String,
    phaseAttempt: Int,
  ): FeatureTaskRuntimeWorkerOwnership {
    val process = supervisor.currentProcess()
    val now = Instant.now()
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
  }
}
