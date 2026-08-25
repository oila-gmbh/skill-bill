package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.config.model.PhaseModelDirective
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.coroutines.cancellation.CancellationException

/**
 * Why a phase is running again. Kinds a bare fix-loop iteration counter could not tell apart, and that
 * call for different operator responses: schema correction is the runtime repairing malformed output,
 * implementation continuation is honest partial work being carried forward, process retry and crash
 * resume are infrastructure recovery, audit/review re-entry is a verifier sending work back, and
 * verification body delivery is the verify_findings heading-selection handshake.
 */
internal enum class FeatureTaskRuntimeContinuationKind(val wireValue: String) {
  IMPLEMENTATION_CONTINUATION("implementation_continuation"),
  SCHEMA_CORRECTION("schema_correction"),
  PROCESS_RETRY("process_retry"),
  CRASH_RESUME("crash_resume"),
  VERIFIER_REENTRY("verifier_reentry"),

  /**
   * The phase is running again because its output left carried items out — review findings, audit
   * gaps, or repair items. Its own kind so the ledger separates unfinished item coverage from a schema
   * correction: those outputs validated, and reading them as schema corrections hid that the phase was
   * still owed work.
   */
  ITEM_COVERAGE("item_coverage"),

  /**
   * verify_findings selected boundary headings on a schema-valid disposition pass; the runtime
   * persisted those selections and must relaunch with resolved entry bodies before settlement.
   * Deliberately not [SCHEMA_CORRECTION]: the envelope validated, and charging the output-gate budget
   * would block the required second turn under cap=1.
   */
  VERIFICATION_BODY_DELIVERY("verification_body_delivery"),
  ;

  companion object {
    /** Category prefix keeping ledger detail inside the documented closed vocabulary. */
    const val LEDGER_DETAIL_PREFIX: String = "continuation:"

    /**
     * Parses the kind from a ledger detail that may carry trailing attributes.
     *
     * The loop-edge detail is `continuation:verifier_reentry driving_verdict=<v>`, so matching the
     * whole remainder against a bare wire value never resolved it. Only the first whitespace-delimited
     * token after the prefix is the kind; anything after it is that entry's own detail.
     */
    fun fromLedgerDetail(detail: String?): FeatureTaskRuntimeContinuationKind? = detail
      ?.takeIf { it.startsWith(LEDGER_DETAIL_PREFIX) }
      ?.removePrefix(LEDGER_DETAIL_PREFIX)
      ?.substringBefore(' ')
      ?.let { value -> entries.firstOrNull { it.wireValue == value } }
  }
}

/**
 * Whether a phase start is a first visit, and if not, why it is running again.
 *
 * One value rather than two parameters because the two answers are one fact: `resumed` alone says only
 * "not the first visit", which is what made a crash resume, a process retry and an in-process re-entry
 * read identically at the event seam.
 */
internal data class FeatureTaskRuntimePhaseStartReentry(
  val resumed: Boolean,
  val startKind: FeatureTaskRuntimeContinuationKind?,
) {
  companion object {
    val FIRST_VISIT: FeatureTaskRuntimePhaseStartReentry =
      FeatureTaskRuntimePhaseStartReentry(resumed = false, startKind = null)
  }
}

/**
 * Emits a feature-task-runtime side-channel event without letting observer faults alter the run.
 *
 * Propagates [CancellationException] so cancelled work cannot continue into later phase work.
 * Ordinary observer failures stay isolated and leave a payload-free [RuntimeDiagnostics] record;
 * a throwing diagnostics sink is also isolated so AC-010 stays intact.
 */
internal fun emitFeatureTaskRuntimeEventSafely(diagnostics: RuntimeDiagnostics, seam: String, emit: () -> Unit) {
  try {
    emit()
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
    try {
      diagnostics.warning(
        "Feature-task-runtime $seam failed; the run is unaffected.",
        error,
      )
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
      // Diagnostic observer failure must not abort the run either.
    }
  }
}

/**
 * Per-phase observability and attempt-ledger sink for one run: at each phase boundary it emits a
 * typed [FeatureTaskRuntimeRunEvent] to the run's event sink and appends a ledger entry. The
 * recorder mints the timestamp and monotonic sequence, so this class never sources time or order.
 *
 * Event-sink failures are isolated: a throwing telemetry or status observer must not change retry,
 * block, or completion outcomes, and must not become a vehicle for rejected-response leakage.
 */
@Suppress("TooManyFunctions") // one emitter per phase-boundary outcome; splitting them scatters the ledger seam
internal class FeatureTaskRuntimeRunObservability(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val request: FeatureTaskRuntimeRunRequest,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  // Branch setup is a distinct pre-implement step, not a phase attempt, so it emits only the
  // typed observability event and does not append to the per-phase attempt ledger.
  fun branchResolved(phaseId: String, branch: String, created: Boolean, reused: Boolean) {
    emitSafely(
      FeatureTaskRuntimeRunEvent.BranchResolved(
        workflowId = request.workflowId,
        phaseId = phaseId,
        branch = branch,
        created = created,
        reused = reused,
      ),
    )
  }

  // Branch-setup blocks are made first-class and symmetric with the per-phase block path: the
  // typed event is emitted AND a ledger entry is appended (the durable blocked per-phase record is
  // persisted by the runner, mirroring blockAndPersist for phase blocks) so a git-failure block is
  // visible to status queries, the ledger audit trail, and the event/monitor stream.
  fun branchSetupBlocked(phaseId: String, resolvedAgentId: String, blockedReason: String) {
    emitSafely(
      FeatureTaskRuntimeRunEvent.BranchSetupBlocked(
        workflowId = request.workflowId,
        phaseId = phaseId,
        blockedReason = blockedReason,
      ),
    )
    appendLedger(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = request.workflowId,
        action = FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
        phaseId = phaseId,
        attemptCount = 1,
        resolvedAgentId = resolvedAgentId,
        blockedReason = blockedReason,
      ),
    )
  }

  fun started(
    phaseId: String,
    resolvedAgentId: String,
    attemptCount: Int,
    directive: PhaseModelDirective?,
    reentry: FeatureTaskRuntimePhaseStartReentry = FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
  ) {
    val resumed = reentry.resumed
    val startKind = reentry.startKind
    emitSafely(
      FeatureTaskRuntimeRunEvent.PhaseStarted(
        workflowId = request.workflowId,
        phaseId = phaseId,
        resolvedAgentId = resolvedAgentId,
        attemptCount = attemptCount,
        resumed = resumed,
        model = directive?.model,
        effort = directive?.effort,
        continuationKind = startKind?.wireValue,
      ),
    )
    appendLedger(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = request.workflowId,
        action = if (resumed) {
          FeatureTaskRuntimePhaseLedgerAction.RESUME
        } else {
          FeatureTaskRuntimePhaseLedgerAction.START
        },
        phaseId = phaseId,
        attemptCount = attemptCount,
        resolvedAgentId = resolvedAgentId,
        blockedReason = startKind?.let { "${FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX}${it.wireValue}" },
      ),
    )
  }

  fun fixLoopIteration(phaseId: String, resolvedAgentId: String, attemptCount: Int, fixLoopIteration: Int) {
    continuation(
      phaseId,
      resolvedAgentId,
      attemptCount,
      fixLoopIteration,
      FeatureTaskRuntimeContinuationKind.SCHEMA_CORRECTION,
    )
  }

  /**
   * Emits one phase re-entry stamped with WHY the phase is running again. Both the event and the
   * durable ledger entry carry the kind, so a status query and a telemetry consumer report the same
   * distinction rather than each inferring one from a bare counter.
   *
   * The ledger's `blocked_reason` column is reused as the kind carrier for non-blocking entries: it
   * is the entry's free-text detail field and a FIX_LOOP_ITERATION entry never carries a block. The
   * value stays inside the documented category-prefix vocabulary.
   */
  fun continuation(
    phaseId: String,
    resolvedAgentId: String,
    attemptCount: Int,
    iteration: Int,
    kind: FeatureTaskRuntimeContinuationKind,
  ) {
    emitSafely(
      FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration(
        workflowId = request.workflowId,
        phaseId = phaseId,
        resolvedAgentId = resolvedAgentId,
        attemptCount = attemptCount,
        fixLoopIteration = iteration,
        continuationKind = kind.wireValue,
      ),
    )
    appendLedger(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = request.workflowId,
        action = FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION,
        phaseId = phaseId,
        attemptCount = attemptCount,
        resolvedAgentId = resolvedAgentId,
        fixLoopIteration = iteration,
        blockedReason = "${FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX}${kind.wireValue}",
      ),
    )
  }

  fun derivationReask(phaseId: String, resolvedAgentId: String, attemptCount: Int, reaskCount: Int) {
    emitFeatureTaskRuntimeEventSafely(diagnostics, "derivation re-ask") {
      diagnostics.warning(
        "seam=FeatureTaskRuntimeRunObservability.derivationReask " +
          "phase=$phaseId attempt=$attemptCount reask=$reaskCount",
        null,
      )
    }
    appendLedger(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = request.workflowId,
        action = FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION,
        phaseId = phaseId,
        attemptCount = attemptCount,
        resolvedAgentId = resolvedAgentId,
        fixLoopIteration = reaskCount,
        blockedReason = "derivation_reask",
      ),
    )
  }

  fun derivationBlocked(phaseId: String, attemptCount: Int, reason: String) {
    emitFeatureTaskRuntimeEventSafely(diagnostics, "derivation block") {
      diagnostics.warning(
        "seam=FeatureTaskRuntimeRunObservability.derivationBlocked phase=$phaseId attempt=$attemptCount reason=$reason",
        null,
      )
    }
  }

  fun completed(phaseId: String, resolvedAgentId: String, attemptCount: Int) {
    completedEvent(phaseId, resolvedAgentId, attemptCount)
    appendLedger(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = request.workflowId,
        action = FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
        phaseId = phaseId,
        attemptCount = attemptCount,
        resolvedAgentId = resolvedAgentId,
      ),
    )
  }

  fun completedEvent(phaseId: String, resolvedAgentId: String, attemptCount: Int) {
    emitSafely(
      FeatureTaskRuntimeRunEvent.PhaseCompleted(
        workflowId = request.workflowId,
        phaseId = phaseId,
        resolvedAgentId = resolvedAgentId,
        attemptCount = attemptCount,
      ),
    )
  }

  fun runtimeOwnedFactUnavailable(seam: String, expected: String, actual: String, cause: String) {
    emitFeatureTaskRuntimeEventSafely(diagnostics, "runtime-owned fact unavailable") {
      diagnostics.warning(
        "seam=$seam expected=$expected actual=$actual cause=$cause",
        null,
      )
    }
  }

  fun paused(phaseId: String, resolvedAgentId: String, attemptCount: Int, pauseReason: String) {
    emitSafely(
      FeatureTaskRuntimeRunEvent.PhasePaused(
        workflowId = request.workflowId,
        phaseId = phaseId,
        resolvedAgentId = resolvedAgentId,
        attemptCount = attemptCount,
        pauseReason = pauseReason,
      ),
    )
    appendLedger(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = request.workflowId,
        action = FeatureTaskRuntimePhaseLedgerAction.PAUSED,
        phaseId = phaseId,
        attemptCount = attemptCount,
        resolvedAgentId = resolvedAgentId,
        blockedReason = pauseReason,
      ),
    )
  }

  fun blocked(phaseId: String, resolvedAgentId: String, attemptCount: Int, blockedReason: String) {
    emitSafely(
      FeatureTaskRuntimeRunEvent.PhaseBlocked(
        workflowId = request.workflowId,
        phaseId = phaseId,
        resolvedAgentId = resolvedAgentId,
        attemptCount = attemptCount,
        blockedReason = blockedReason,
      ),
    )
    appendLedger(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = request.workflowId,
        action = FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
        phaseId = phaseId,
        attemptCount = attemptCount,
        resolvedAgentId = resolvedAgentId,
        blockedReason = blockedReason,
      ),
    )
  }

  // A backward-edge re-entry: appends a durable LOOP_EDGE ledger entry carrying the runtime-minted
  // loop id and per-edge iteration (distinct from attempt_count) so the loop trail is auditable. The
  // re-entered phase's own start/complete events still emit on its relaunch.
  fun validationGateProgress() {
    // Typed progress events are emitted at the gate seam; this hook exists for ledger symmetry if needed.
  }

  fun loopEdge(phaseId: String, loopId: String, edgeIteration: Int, drivingVerdict: FeatureTaskRuntimeVerdict) {
    emitSafely(
      FeatureTaskRuntimeRunEvent.PhaseLoopEdge(
        workflowId = request.workflowId,
        phaseId = phaseId,
        loopId = loopId,
        edgeIteration = edgeIteration,
        drivingVerdict = drivingVerdict.wireValue,
        continuationKind = FeatureTaskRuntimeContinuationKind.VERIFIER_REENTRY.wireValue,
      ),
    )
    appendLedger(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = request.workflowId,
        action = FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
        phaseId = phaseId,
        attemptCount = 1,
        loopId = loopId,
        edgeIteration = edgeIteration,
        // A backward edge is a verifier (audit/review) sending work back; the driving verdict stays in
        // the same detail field so the existing trail is not lost.
        blockedReason = "${FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX}" +
          "${FeatureTaskRuntimeContinuationKind.VERIFIER_REENTRY.wireValue} " +
          "driving_verdict=${drivingVerdict.wireValue}",
      ),
    )
  }

  private fun emitSafely(event: FeatureTaskRuntimeRunEvent) {
    emitFeatureTaskRuntimeEventSafely(
      diagnostics = diagnostics,
      seam = "event-sink emission (${event::class.simpleName})",
    ) {
      request.eventSink.emit(event)
    }
  }

  private fun appendLedger(ledgerRequest: FeatureTaskRuntimePhaseLedgerRequest) {
    recorder.appendLedgerEntry(ledgerRequest, request.dbPathOverride)
  }
}

/**
 * The continuation kind a phase START/RESUME ledger entry claims.
 *
 * Derived from [crashResumed] — a durable record this process did not create — rather than from
 * "resumed", which only means "not the first visit" and so also covers in-process re-entry. A repeat
 * attempt that is not a crash resume is a process retry.
 *
 * A verifier re-entry claims no kind only when it is not also a crash resume: the LOOP_EDGE entry for
 * that edge already carries `verifier_reentry`, and the status projection takes the newest kind-bearing
 * entry, so a generic start kind appended after it would overwrite the accurate description. A crash
 * resume inside a reopened span has no LOOP_EDGE entry of its own, so it must still claim its kind.
 */
internal fun featureTaskRuntimeStartContinuationKind(
  crashResumed: Boolean,
  verifierReentry: Boolean,
  attemptCount: Int,
): FeatureTaskRuntimeContinuationKind? = when {
  crashResumed -> FeatureTaskRuntimeContinuationKind.CRASH_RESUME
  verifierReentry -> null
  attemptCount > 1 -> FeatureTaskRuntimeContinuationKind.PROCESS_RETRY
  else -> null
}
