package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.config.model.PhaseModelDirective
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

/**
 * Why a phase is running again. Five kinds that a bare fix-loop iteration counter could not tell
 * apart, and that call for different operator responses: schema correction is the runtime repairing
 * malformed output, implementation continuation is honest partial work being carried forward, process
 * retry and crash resume are infrastructure recovery, and audit/review re-entry is a verifier sending
 * work back.
 */
enum class FeatureTaskRuntimeContinuationKind(val wireValue: String) {
  IMPLEMENTATION_CONTINUATION("implementation_continuation"),
  SCHEMA_CORRECTION("schema_correction"),
  PROCESS_RETRY("process_retry"),
  CRASH_RESUME("crash_resume"),
  VERIFIER_REENTRY("verifier_reentry"),
  ;

  companion object {
    /** Category prefix keeping ledger detail inside the documented closed vocabulary. */
    const val LEDGER_DETAIL_PREFIX: String = "continuation:"

    fun fromLedgerDetail(detail: String?): FeatureTaskRuntimeContinuationKind? = detail
      ?.removePrefix(LEDGER_DETAIL_PREFIX)
      ?.let { value -> entries.firstOrNull { it.wireValue == value } }
  }
}

/**
 * Per-phase observability and attempt-ledger sink for one run: at each phase boundary it emits a
 * typed [FeatureTaskRuntimeRunEvent] to the run's event sink and appends a ledger entry. The
 * recorder mints the timestamp and monotonic sequence, so this class never sources time or order.
 */
internal class FeatureTaskRuntimeRunObservability(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val request: FeatureTaskRuntimeRunRequest,
) {
  // Branch setup is a distinct pre-implement step, not a phase attempt, so it emits only the
  // typed observability event and does not append to the per-phase attempt ledger.
  fun branchResolved(phaseId: String, branch: String, created: Boolean, reused: Boolean) {
    request.eventSink.emit(
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
    request.eventSink.emit(
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
    resumed: Boolean,
    directive: PhaseModelDirective?,
  ) {
    // A resumed start is a crash resume; a fresh start that is nonetheless a repeat attempt is a
    // process retry. Both were previously indistinguishable from each other and from a fix-loop
    // re-run in the ledger.
    val startKind = when {
      resumed -> FeatureTaskRuntimeContinuationKind.CRASH_RESUME
      attemptCount > 1 -> FeatureTaskRuntimeContinuationKind.PROCESS_RETRY
      else -> null
    }
    request.eventSink.emit(
      FeatureTaskRuntimeRunEvent.PhaseStarted(
        workflowId = request.workflowId,
        phaseId = phaseId,
        resolvedAgentId = resolvedAgentId,
        attemptCount = attemptCount,
        resumed = resumed,
        model = directive?.model,
        effort = directive?.effort,
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
    request.eventSink.emit(
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
    request.eventSink.emit(
      FeatureTaskRuntimeRunEvent.PhaseCompleted(
        workflowId = request.workflowId,
        phaseId = phaseId,
        resolvedAgentId = resolvedAgentId,
        attemptCount = attemptCount,
      ),
    )
  }

  fun blocked(phaseId: String, resolvedAgentId: String, attemptCount: Int, blockedReason: String) {
    request.eventSink.emit(
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
  fun loopEdge(phaseId: String, loopId: String, edgeIteration: Int, drivingVerdict: FeatureTaskRuntimeVerdict) {
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

  private fun appendLedger(ledgerRequest: FeatureTaskRuntimePhaseLedgerRequest) {
    recorder.appendLedgerEntry(ledgerRequest, request.dbPathOverride)
  }
}
