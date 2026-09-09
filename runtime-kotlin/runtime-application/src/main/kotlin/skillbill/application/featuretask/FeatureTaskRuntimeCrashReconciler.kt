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
  fun reconcile(): FeatureTaskRuntimeCrashReconciliationResult {
    val now = clock.instant().toString()
    val candidates = runCatching {
      database.read { it.workflowStates.findFeatureTaskRuntimeCrashReconciliationCandidates(now) }
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
      reconcileCandidate(candidate)?.let { reasonClass ->
        reasonClassCounts.merge(reasonClass, 1, Int::plus)
        reconciledCount++
      }
    }
    return FeatureTaskRuntimeCrashReconciliationResult(reconciledCount, reasonClassCounts)
  }

  // Returns the reason class a candidate was reconciled under, the FAULT_REASON_CLASS sentinel when
  // an unexpected fault interrupted it, or null when it was alive, ambiguous, or lost the fencing
  // race. The store returns false for a lost race, so an exception reaching this catch is a genuine
  // infrastructure or programming fault, surfaced as the fault class rather than masked as idle.
  // Never throws: the pass runs unconditionally and must not block an otherwise healthy start.
  private fun reconcileCandidate(candidate: FeatureTaskRuntimeCrashReconciliationCandidate): String? = runCatching {
    if (!supervisor.inspect(candidate.ownership).isConfirmedDead()) {
      return@runCatching null
    }
    val reason = interruptionReason()
    // The fenced reconcile write re-checks lease expiry inside the transaction against `now`, so a
    // lease extended between the scan and here (or another pass winning the race) returns false.
    val reconciled = database.transaction {      it.workflowStates.reconcileFeatureTaskRuntimeCrashedWorker(
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
