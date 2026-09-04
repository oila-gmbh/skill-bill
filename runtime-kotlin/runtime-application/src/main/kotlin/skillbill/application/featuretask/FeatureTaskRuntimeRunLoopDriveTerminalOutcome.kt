package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

@Inject
class FeatureTaskRuntimeRunLoopDriveTerminalOutcome {
  fun advancePhaseReason(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String): String? =
    if (runLoop.state.isComplete(phaseId)) {
      runLoop.state.outputFor(phaseId)
        ?.takeIf { phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN }
        ?.let { runLoop.collaborators.backwardEdge.applyPlanningStop(runLoop, phaseId, it) }
    } else {
      runLoop.collaborators.backwardEdge.establishBranchIfNeeded(
        runLoop,
        phaseId,
      ) ?: runLoop.collaborators.backwardEdge.runPhaseFor(runLoop, phaseId)
    }

  internal fun settleAdvanceOutcome(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    reason: String?,
  ): PhaseSettlement = when {
    runLoop.session.decomposed != null -> PhaseSettlement.stop()
    runLoop.session.recordRejectionSettlementPending -> {
      runLoop.session.recordRejectionSettlementPending = false
      PhaseSettlement.completed(phaseId, FeatureTaskRuntimeVerdict.RECORD_REJECTED)
    }
    reason != null -> {
      if (runLoop.session.paused == null) runLoop.collaborators.planningBranch.blockAt(runLoop, phaseId, reason)
      PhaseSettlement.stop()
    }
    else -> PhaseSettlement.completed(phaseId, runLoop.state.verdictFor(phaseId))
  }
}
