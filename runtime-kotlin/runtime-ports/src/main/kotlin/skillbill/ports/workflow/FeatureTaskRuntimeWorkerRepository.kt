package skillbill.ports.workflow

import skillbill.ports.featuretask.model.FeatureTaskRuntimeCrashReconciliationCandidate
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership

interface FeatureTaskRuntimeWorkerRepository {

  fun getFeatureTaskRuntimeWorkerOwnership(workflowId: String): FeatureTaskRuntimeWorkerOwnership? = null

  fun acquireFeatureTaskRuntimeWorker(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    expectedUpdatedAt: String?,
  ): Boolean = error("Feature-task runtime worker acquisition is not implemented by this persistence adapter.")

  fun reserveFeatureTaskRuntimeWorkerTakeover(
    workflowId: String,
    expectedOwnerToken: String,
    expectedGeneration: Long,
  ): Boolean = error("Feature-task runtime worker takeover is not implemented by this persistence adapter.")

  fun transferFeatureTaskRuntimeWorker(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    expectedOwnerToken: String,
    expectedGeneration: Long,
  ): Boolean = error("Feature-task runtime worker transfer is not implemented by this persistence adapter.")

  fun heartbeatFeatureTaskRuntimeWorker(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean =
    error("Feature-task runtime worker heartbeat is not implemented by this persistence adapter.")

  fun releaseFeatureTaskRuntimeWorker(workflowId: String, ownerToken: String, generation: Long): Boolean =
    error("Feature-task runtime worker release is not implemented by this persistence adapter.")

  /**
   * Non-terminal runtime rows whose worker lease has already expired as of [nowInstant] (an
   * RFC 3339 instant): the crash-reconciliation candidate set. Liveness is NOT decided here — the
   * caller confirms the process is dead before writing. Rows without a lease, with a live lease, or
   * in a terminal status are never returned.
   */
  fun findFeatureTaskRuntimeCrashReconciliationCandidates(
    nowInstant: String,
  ): List<FeatureTaskRuntimeCrashReconciliationCandidate> = emptyList()

  /**
   * Atomic fenced reconcile write: transition the still-`running` row to the resumable `pending`
   * state at its existing current_step_id, record [interruptionReason], and release the lease under
   * the existing owner_token/generation fencing, all only while the lease is still expired as of
   * [nowInstant]. Returns false on a lost fencing race (another pass reconciled first) so the caller
   * can skip rather than fail; the write never partially applies.
   */
  fun reconcileFeatureTaskRuntimeCrashedWorker(
    workflowId: String,
    ownerToken: String,
    generation: Long,
    interruptionReason: String,
    nowInstant: String,
  ): Boolean = error("Feature-task runtime crash reconciliation is not implemented by this persistence adapter.")
}
