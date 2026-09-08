package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeCrashReconciliationReason
import skillbill.application.featuretask.model.FeatureTaskRuntimeCrashReconciliationResult
import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeCrashReconciliationCandidate
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import java.time.Clock

@Inject
class FeatureTaskRuntimeCrashReconciler(
  private val database: DatabaseSessionFactory,
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
) {
  fun reconcile(dbOverride: String? = null): FeatureTaskRuntimeCrashReconciliationResult {
    val now = clock.instant().toString()
    val candidates = runCatching {
      database.read(dbOverride) { it.workflowStates.findFeatureTaskRuntimeCrashReconciliationCandidates(now) }
    }.getOrElse { error ->
      val reconciliationError = FeatureTaskRuntimeSubtaskCommitReconciliationError(
        workflowId = "unknown",
        issueKey = "unknown",
        subtaskId = "unknown",
        reason = "crash-reconciliation candidate scan could not be read (${error.message.orEmpty()})",
        cause = error,
      )
      diagnostics.warning(
        "record_kind=refusal seam=FeatureTaskRuntimeCrashReconciler.reconcile " +
          "value_used='candidate scan' value_expected=durable crash candidates " +
          "cause=${reconciliationError.reason}",
        reconciliationError,
      )
      throw reconciliationError
    }
    if (candidates.isEmpty()) return FeatureTaskRuntimeCrashReconciliationResult.NONE
    val reasonClassCounts = mutableMapOf<String, Int>()
    var reconciledCount = 0
    candidates.forEach { candidate ->
      reconcileCandidate(candidate, dbOverride)?.let { reasonClass ->
        reasonClassCounts.merge(reasonClass, 1, Int::plus)
        reconciledCount++
      }
    }
    return FeatureTaskRuntimeCrashReconciliationResult(reconciledCount, reasonClassCounts)
  }

  private fun reconcileCandidate(
    candidate: FeatureTaskRuntimeCrashReconciliationCandidate,
    dbOverride: String?,
  ): String? = runCatching {
    if (!FeatureTaskRuntimeCrashLiveness.isConfirmedDead(supervisor.inspect(candidate.ownership))) {
      return@runCatching null
    }
    val reason = interruptionReason()
    val reconciled = database.transaction(dbOverride) {
      it.workflowStates.reconcileFeatureTaskRuntimeCrashedWorker(
        workflowId = candidate.ownership.workflowId,
        ownerToken = candidate.ownership.ownerToken,
        generation = candidate.ownership.generation,
        interruptionReason = "${reason.wireValue}: worker lease expired and process confirmed dead",
        nowInstant = clock.instant().toString(),
      )
    }
    if (reconciled) reason.wireValue else null
  }.getOrElse { error ->
    val reconciliationError = FeatureTaskRuntimeSubtaskCommitReconciliationError(
      workflowId = candidate.ownership.workflowId,
      issueKey = "unknown",
      subtaskId = "unknown",
      reason = "crash reconciliation could not durably reconcile the expired worker (${error.message.orEmpty()})",
      cause = error,
    )
    diagnostics.warning(
      "record_kind=refusal seam=FeatureTaskRuntimeCrashReconciler.reconcileCandidate " +
        "value_used='${candidate.ownership.workflowId}' value_expected=durable crash reconciliation " +
        "cause=${reconciliationError.reason}",
      reconciliationError,
    )
    throw reconciliationError
  }

  private fun interruptionReason(): FeatureTaskRuntimeCrashReconciliationReason =
    FeatureTaskRuntimeCrashReconciliationReason.LEASE_EXPIRED
}
