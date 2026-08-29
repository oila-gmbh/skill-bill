package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

internal fun FeatureTaskRuntimeRunLoop.runPhaseDriveLoop() {
  var phaseId: String? = pendingReentry?.phaseId ?: transitions.forwardPhaseIds.first()
  while (phaseId != null) {
    val settled = advance(phaseId)
    val completedPhaseId = settled.completedPhaseId
    phaseId = if (completedPhaseId != null) {
      nextPhaseAfter(completedPhaseId, requireNotNull(settled.completedVerdict))
    } else {
      null
    }
  }
}

internal fun FeatureTaskRuntimeRunLoop.advancePhaseReason(phaseId: String): String? = if (state.isComplete(phaseId)) {
  state.outputFor(phaseId)
    ?.takeIf { phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN }
    ?.let { applyPlanningStop(phaseId, it) }
} else {
  establishBranchIfNeeded(phaseId) ?: runPhaseFor(phaseId)
}

internal fun FeatureTaskRuntimeRunLoop.settleAdvanceOutcome(phaseId: String, reason: String?): PhaseSettlement = when {
  decomposed != null -> PhaseSettlement.stop()
  recordRejectionSettlementPending -> {
    recordRejectionSettlementPending = false
    PhaseSettlement.completed(phaseId, FeatureTaskRuntimeVerdict.RECORD_REJECTED)
  }
  reason != null -> {
    if (paused == null) blockAt(phaseId, reason)
    PhaseSettlement.stop()
  }
  else -> PhaseSettlement.completed(phaseId, state.verdictFor(phaseId))
}
