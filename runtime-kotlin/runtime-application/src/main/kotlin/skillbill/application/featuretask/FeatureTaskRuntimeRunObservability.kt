package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.config.model.PhaseModelDirective
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

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
        blockedReason = "driving_verdict=${drivingVerdict.wireValue}",
      ),
    )
  }

  private fun appendLedger(ledgerRequest: FeatureTaskRuntimePhaseLedgerRequest) {
    recorder.appendLedgerEntry(ledgerRequest, request.dbPathOverride)
  }
}
