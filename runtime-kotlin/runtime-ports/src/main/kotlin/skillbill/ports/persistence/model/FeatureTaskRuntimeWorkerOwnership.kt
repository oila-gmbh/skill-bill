package skillbill.ports.persistence.model

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION

data class FeatureTaskRuntimeWorkerOwnership(
  val workflowId: String,
  val generation: Long,
  val ownerToken: String,
  val hostIdentity: String,
  val bootIdentity: String,
  val pid: Long,
  val processBirthToken: String,
  val leaseState: FeatureTaskRuntimeWorkerLeaseState,
  val heartbeatAt: String,
  val expiresAt: String,
  val phaseId: String,
  val phaseAttempt: Int,
  val contractVersion: String = FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION,
)

enum class FeatureTaskRuntimeWorkerLeaseState(val wireValue: String) {
  ACTIVE("active"),
  TAKEOVER_RESERVED("takeover_reserved"),
}

/**
 * A non-terminal runtime workflow row whose worker lease has expired: the raw material the
 * crash reconciler inspects for liveness before transitioning to the resumable pending state.
 * Carries the fenced [ownership] so liveness detection and the fenced reconcile write both ride
 * the existing owner_token/generation machinery, plus the row's [currentStepId] the resume point.
 */
data class FeatureTaskRuntimeCrashReconciliationCandidate(
  val ownership: FeatureTaskRuntimeWorkerOwnership,
  val currentStepId: String,
  val workflowStatus: String,
)

sealed interface FeatureTaskRuntimeWorkerAcquisition {
  data class Acquired(val ownership: FeatureTaskRuntimeWorkerOwnership) : FeatureTaskRuntimeWorkerAcquisition
  data class OrphanReclaimed(val ownership: FeatureTaskRuntimeWorkerOwnership) : FeatureTaskRuntimeWorkerAcquisition
  data class ExactLiveOwner(val ownership: FeatureTaskRuntimeWorkerOwnership) : FeatureTaskRuntimeWorkerAcquisition
  data object Contended : FeatureTaskRuntimeWorkerAcquisition
  data class OwnershipMismatch(val reason: String) : FeatureTaskRuntimeWorkerAcquisition
  data class UnsupportedProcessEvidence(val reason: String) : FeatureTaskRuntimeWorkerAcquisition
  data class StaleSelector(val workflowId: String) : FeatureTaskRuntimeWorkerAcquisition
}
