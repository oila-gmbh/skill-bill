package skillbill.application.goalrunner

import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.DecompositionSubtask
import java.time.Instant

internal fun workerLivenessSignal(
  phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  currentSubtask: DecompositionSubtask?,
  dbPathOverride: String?,
): String? {
  val workflowId = currentSubtask?.workflowId?.takeIf(String::isNotBlank) ?: return null
  if (phaseRecorder.existingWorkflowMode(workflowId, dbPathOverride) != FeatureTaskWorkflowMode.RUNTIME) return null
  val runningPhase = phaseRecorder.loadPhaseRecords(workflowId, dbPathOverride)
    .orEmpty()
    .values
    .firstOrNull { it.status == "running" }
    ?: return null
  return phaseRecorder.loadWorkerOwnership(workflowId, dbPathOverride)
    .toStaleWorkerSignal(runningPhase.phaseId, runningPhase.attemptCount)
}

private fun FeatureTaskRuntimeWorkerOwnership?.toStaleWorkerSignal(phaseId: String, attemptCount: Int): String? = when {
  this == null -> "worker_liveness=missing; phase=$phaseId; attempt=$attemptCount"
  leaseState == FeatureTaskRuntimeWorkerLeaseState.TAKEOVER_RESERVED ->
    "worker_liveness=takeover_reserved; phase=$phaseId; attempt=$attemptCount; expires_at=$expiresAt"
  Instant.parse(expiresAt).isBefore(Instant.now()) ->
    "worker_liveness=expired; phase=$phaseId; attempt=$attemptCount; expires_at=$expiresAt"
  else -> null
}
