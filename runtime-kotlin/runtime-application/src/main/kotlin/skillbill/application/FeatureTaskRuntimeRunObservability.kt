package skillbill.application

import skillbill.application.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction

/**
 * Per-phase observability and attempt-ledger sink for one run: at each phase boundary it emits a
 * typed [FeatureTaskRuntimeRunEvent] to the run's event sink and appends a ledger entry. The
 * recorder mints the timestamp and monotonic sequence, so this class never sources time or order.
 */
internal class FeatureTaskRuntimeRunObservability(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val request: FeatureTaskRuntimeRunRequest,
) {
  fun started(phaseId: String, resolvedAgentId: String, attemptCount: Int, resumed: Boolean) {
    request.eventSink.emit(
      FeatureTaskRuntimeRunEvent.PhaseStarted(
        workflowId = request.workflowId,
        phaseId = phaseId,
        resolvedAgentId = resolvedAgentId,
        attemptCount = attemptCount,
        resumed = resumed,
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
      ),
    )
  }

  fun fixLoopIteration(phaseId: String, resolvedAgentId: String, attemptCount: Int, fixLoopIteration: Int) {
    request.eventSink.emit(
      FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration(
        workflowId = request.workflowId,
        phaseId = phaseId,
        resolvedAgentId = resolvedAgentId,
        attemptCount = attemptCount,
        fixLoopIteration = fixLoopIteration,
      ),
    )
    appendLedger(
      FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = request.workflowId,
        action = FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION,
        phaseId = phaseId,
        attemptCount = attemptCount,
        resolvedAgentId = resolvedAgentId,
        fixLoopIteration = fixLoopIteration,
      ),
    )
  }

  fun completed(phaseId: String, resolvedAgentId: String, attemptCount: Int) {
    request.eventSink.emit(
      FeatureTaskRuntimeRunEvent.PhaseCompleted(
        workflowId = request.workflowId,
        phaseId = phaseId,
        resolvedAgentId = resolvedAgentId,
        attemptCount = attemptCount,
      ),
    )
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

  private fun appendLedger(ledgerRequest: FeatureTaskRuntimePhaseLedgerRequest) {
    recorder.appendLedgerEntry(ledgerRequest, request.dbPathOverride)
  }
}
