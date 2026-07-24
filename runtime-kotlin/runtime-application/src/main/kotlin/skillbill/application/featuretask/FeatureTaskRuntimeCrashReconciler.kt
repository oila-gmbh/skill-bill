package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.FeatureTaskRuntimeCrashReconciliationReason
import skillbill.application.model.FeatureTaskRuntimeCrashReconciliationResult
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.model.FeatureTaskRuntimeCrashReconciliationCandidate
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import java.time.Instant

/**
 * Reconciles orphaned non-terminal runtime rows left by a killed child process. A candidate is a
 * running row whose worker lease has expired and whose process the injected supervisor confirms
 * dead; the reconciler transitions it to the resumable `pending` state and releases the lease under
 * the existing owner_token/generation fencing, reusing the worker-lease and workflow-store machinery
 * rather than a parallel state machine.
 *
 * The pass runs unconditionally and never throws on a benign race: an empty candidate set is a
 * no-op, an already-reconciled row drops out of the candidate query, and a lost fencing race (a
 * concurrent startup reconciled first) is skipped rather than failing the pass.
 */
@Inject
class FeatureTaskRuntimeCrashReconciler(
  private val database: DatabaseSessionFactory,
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  fun reconcile(dbOverride: String? = null): FeatureTaskRuntimeCrashReconciliationResult {
    val now = Instant.now().toString()
    val candidates = runCatching {
      database.read(dbOverride) { it.workflowStates.findFeatureTaskRuntimeCrashReconciliationCandidates(now) }
    }.getOrElse { error ->
      diagnostics.warning("Crash-reconciliation candidate scan failed; startup is unaffected.", error)
      return FeatureTaskRuntimeCrashReconciliationResult.NONE
    }
    if (candidates.isEmpty()) return FeatureTaskRuntimeCrashReconciliationResult.NONE
    val reasonClassCounts = mutableMapOf<String, Int>()
    var reconciledCount = 0
    candidates.forEach { candidate ->
      reconcileCandidate(candidate, dbOverride)?.let { reasonClass ->
        reasonClassCounts.merge(reasonClass, 1, Int::plus)
        // The fault class counts toward telemetry visibility but not toward reconciled rows.
        if (reasonClass != FAULT_REASON_CLASS) reconciledCount++
      }
    }
    return FeatureTaskRuntimeCrashReconciliationResult(reconciledCount, reasonClassCounts)
  }

  // Returns the reason class a candidate was reconciled under, the FAULT_REASON_CLASS sentinel when
  // an unexpected fault interrupted it, or null when it was alive, ambiguous, or lost the fencing
  // race. The store returns false for a lost race, so an exception reaching this catch is a genuine
  // infrastructure or programming fault, surfaced as the fault class rather than masked as idle.
  // Never throws: the pass runs unconditionally and must not block an otherwise healthy start.
  private fun reconcileCandidate(
    candidate: FeatureTaskRuntimeCrashReconciliationCandidate,
    dbOverride: String?,
  ): String? = runCatching {
    if (!FeatureTaskRuntimeCrashLiveness.isConfirmedDead(supervisor.inspect(candidate.ownership))) {
      return@runCatching null
    }
    val reason = interruptionReason(candidate)
    // The fenced reconcile write re-checks lease expiry inside the transaction against `now`, so a
    // lease extended between the scan and here (or another pass winning the race) returns false.
    val reconciled = database.transaction(dbOverride) {
      it.workflowStates.reconcileFeatureTaskRuntimeCrashedWorker(
        workflowId = candidate.ownership.workflowId,
        ownerToken = candidate.ownership.ownerToken,
        generation = candidate.ownership.generation,
        interruptionReason = "${reason.wireValue}: worker lease expired and process confirmed dead",
        nowInstant = Instant.now().toString(),
      )
    }
    if (reconciled) reason.wireValue else null
  }.getOrElse { error ->
    diagnostics.warning(
      "Crash reconciliation faulted on a candidate; the pass continues and the fault is counted.",
      error,
    )
    FAULT_REASON_CLASS
  }

  private companion object {
    const val FAULT_REASON_CLASS = "reconcile_fault"
  }

  // Recorded exit status is not durably persisted on the row today, so lease expiry is the only
  // evidence available; the reason class stays open for a future exit-status source.
  private fun interruptionReason(
    @Suppress("UNUSED_PARAMETER") candidate: FeatureTaskRuntimeCrashReconciliationCandidate,
  ): FeatureTaskRuntimeCrashReconciliationReason = FeatureTaskRuntimeCrashReconciliationReason.LEASE_EXPIRED
}
