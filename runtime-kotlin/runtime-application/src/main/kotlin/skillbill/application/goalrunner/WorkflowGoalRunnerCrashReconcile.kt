package skillbill.application.goalrunner

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.goalrunner.model.CrashReconcileExpiredWorkerRequest
import skillbill.application.featuretask.FeatureTaskRuntimeCrashLiveness
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.workflow.model.WorkflowStateRecord
import java.time.Instant

internal fun crashReconcileExpiredWorkerToResumable(
  request: CrashReconcileExpiredWorkerRequest,
): GoalRunnerStoredOutcome? {
  val now = Instant.now()
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

internal fun crashReconcileToResumable(
  workflowStates: WorkflowStateRepository,
  workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  workflowId: String,
  issueKey: String,
  subtaskId: Int,
): GoalRunnerStoredOutcome? {
  val ownership = workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId)
  val row = ownership?.let { workflowStates.getFeatureTaskRuntimeWorkflow(workflowId) }
  val continuation = row
    ?.takeIf { it.workflowStatus == "running" }
    ?.let { goalContinuation(decodeArtifacts(it.artifactsJson)) }
    ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
  if (ownership == null || row == null || continuation == null) return null
  return crashReconcileExpiredWorkerToResumable(
    CrashReconcileExpiredWorkerRequest(
      workflowStates = workflowStates,
      workerSupervisor = workerSupervisor,
      workflowId = workflowId,
      continuation = continuation,
      ownership = ownership,
      row = row,
    ),
  )
}
