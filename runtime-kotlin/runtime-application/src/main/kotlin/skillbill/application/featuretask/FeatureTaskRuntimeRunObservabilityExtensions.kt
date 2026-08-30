package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

internal fun FeatureTaskRuntimeRunObservability.fixLoopIteration(
  phaseId: String,
  resolvedAgentId: String,
  attemptCount: Int,
  fixLoopIteration: Int,
) {
  continuation(
    phaseId,
    resolvedAgentId,
    attemptCount,
    fixLoopIteration,
    FeatureTaskRuntimeContinuationKind.SCHEMA_CORRECTION,
  )
}

internal fun FeatureTaskRuntimeRunObservability.continuation(
  phaseId: String,
  resolvedAgentId: String,
  attemptCount: Int,
  iteration: Int,
  kind: FeatureTaskRuntimeContinuationKind,
) {
  emitSafely(
    skillbill.application.featuretask.model.FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration(
      workflowId = observabilityRequest.workflowId,
      phaseId = phaseId,
      resolvedAgentId = resolvedAgentId,
      attemptCount = attemptCount,
      fixLoopIteration = iteration,
      continuationKind = kind.wireValue,
    ),
  )
  appendLedger(
    FeatureTaskRuntimePhaseLedgerRequest(
      workflowId = observabilityRequest.workflowId,
      action = FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION,
      phaseId = phaseId,
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId,
      fixLoopIteration = iteration,
      blockedReason = "${FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX}${kind.wireValue}",
    ),
  )
}

internal fun FeatureTaskRuntimeRunObservability.completedEvent(
  phaseId: String,
  resolvedAgentId: String,
  attemptCount: Int,
) {
  emitSafely(
    skillbill.application.featuretask.model.FeatureTaskRuntimeRunEvent.PhaseCompleted(
      workflowId = observabilityRequest.workflowId,
      phaseId = phaseId,
      resolvedAgentId = resolvedAgentId,
      attemptCount = attemptCount,
    ),
  )
}

internal fun FeatureTaskRuntimeRunObservability.paused(
  phaseId: String,
  resolvedAgentId: String,
  attemptCount: Int,
  pauseReason: String,
) {
  emitSafely(
    skillbill.application.featuretask.model.FeatureTaskRuntimeRunEvent.PhasePaused(
      workflowId = observabilityRequest.workflowId,
      phaseId = phaseId,
      resolvedAgentId = resolvedAgentId,
      attemptCount = attemptCount,
      pauseReason = pauseReason,
    ),
  )
  appendLedger(
    FeatureTaskRuntimePhaseLedgerRequest(
      workflowId = observabilityRequest.workflowId,
      action = FeatureTaskRuntimePhaseLedgerAction.PAUSED,
      phaseId = phaseId,
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId,
      blockedReason = pauseReason,
    ),
  )
}

internal fun FeatureTaskRuntimeRunObservability.blocked(
  phaseId: String,
  resolvedAgentId: String,
  attemptCount: Int,
  blockedReason: String,
) {
  emitSafely(
    skillbill.application.featuretask.model.FeatureTaskRuntimeRunEvent.PhaseBlocked(
      workflowId = observabilityRequest.workflowId,
      phaseId = phaseId,
      resolvedAgentId = resolvedAgentId,
      attemptCount = attemptCount,
      blockedReason = blockedReason,
    ),
  )
  appendLedger(
    FeatureTaskRuntimePhaseLedgerRequest(
      workflowId = observabilityRequest.workflowId,
      action = FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
      phaseId = phaseId,
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId,
      blockedReason = blockedReason,
    ),
  )
}

internal fun FeatureTaskRuntimeRunObservability.loopEdge(
  phaseId: String,
  loopId: String,
  edgeIteration: Int,
  drivingVerdict: FeatureTaskRuntimeVerdict,
) {
  emitSafely(
    skillbill.application.featuretask.model.FeatureTaskRuntimeRunEvent.PhaseLoopEdge(
      workflowId = observabilityRequest.workflowId,
      phaseId = phaseId,
      loopId = loopId,
      edgeIteration = edgeIteration,
      drivingVerdict = drivingVerdict.wireValue,
      continuationKind = FeatureTaskRuntimeContinuationKind.VERIFIER_REENTRY.wireValue,
    ),
  )
  appendLedger(
    FeatureTaskRuntimePhaseLedgerRequest(
      workflowId = observabilityRequest.workflowId,
      action = FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
      phaseId = phaseId,
      attemptCount = 1,
      loopId = loopId,
      edgeIteration = edgeIteration,
      blockedReason = "${FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX}" +
        "${FeatureTaskRuntimeContinuationKind.VERIFIER_REENTRY.wireValue} " +
        "driving_verdict=${drivingVerdict.wireValue}",
    ),
  )
}

internal val FeatureTaskRuntimeRunObservability.observabilityRequest get() = request
internal fun FeatureTaskRuntimeRunObservability.emitSafely(
  event: skillbill.application.featuretask.model.FeatureTaskRuntimeRunEvent,
) {
  emitFeatureTaskRuntimeEventSafely(
    diagnostics = observabilityDiagnostics,
    seam = "event-sink emission (${event::class.simpleName})",
  ) {
    observabilityRequest.eventSink.emit(event)
  }
}

internal fun FeatureTaskRuntimeRunObservability.appendLedger(ledgerRequest: FeatureTaskRuntimePhaseLedgerRequest) {
  observabilityRecorder.appendLedgerEntry(ledgerRequest, observabilityRequest.dbPathOverride)
}

internal val FeatureTaskRuntimeRunObservability.observabilityRecorder get() = recorder
internal val FeatureTaskRuntimeRunObservability.observabilityDiagnostics get() = diagnostics
