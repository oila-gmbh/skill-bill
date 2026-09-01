package skillbill.infrastructure.sqlite.goalrunner

import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.featuretask.persistence.FeatureTaskRuntimeCrashLiveness
import skillbill.ports.goalrunner.persistence.model.CrashReconcileExpiredWorkerRequest
import java.time.Clock
import java.time.Instant

internal fun crashReconcileExpiredWorkerToResumable(
  request: CrashReconcileExpiredWorkerRequest,
  clock: Clock,
): GoalRunnerStoredOutcome? {
  val now = clock.instant()
  if (!runCatching { Instant.parse(request.ownership.expiresAt).isBefore(now) }.getOrDefault(false)) return null
  if (!FeatureTaskRuntimeCrashLiveness.isConfirmedDead(request.workerSupervisor.inspect(request.ownership))) return null
  val reconciled = request.workflowStates.reconcileFeatureTaskRuntimeCrashedWorker(
    workflowId = request.workflowId,
    ownerToken = request.ownership.ownerToken,
    generation = request.ownership.generation,
    interruptionReason = "lease_expired: worker lease expired and process confirmed dead",
    nowInstant = now.toString(),
  )
  if (!reconciled) return null
  return GoalRunnerStoredOutcome(
    status = GoalRunnerTerminalStatus.RECONCILABLE,
    workflowId = request.workflowId,
    commitSha = null,
    blockedReason = null,
    lastResumableStep = request.row.currentStepId.ifBlank { "preplan" },
    suppressPr = request.continuation.suppressPr,
  )
}
