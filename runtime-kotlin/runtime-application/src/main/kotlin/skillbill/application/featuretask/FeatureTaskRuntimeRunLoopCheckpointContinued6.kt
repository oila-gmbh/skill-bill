package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

@Inject
class FeatureTaskRuntimeRunLoopCheckpointContinued6 {
  fun remediationCheckpointBlockedReasonFor(runLoop: FeatureTaskRuntimeRunLoop): (String, String) -> String =
    { branch, error -> runLoop.collaborators.planningBranch.remediationCheckpointBlockedReason(branch, error) }

  fun blockCheckpoint(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    branch: String,
    error: String,
    blockedReason: (String, String) -> String,
  ): Boolean {
    runLoop.collaborators.planningBranch.blockAt(runLoop, precedingPhaseId, blockedReason(branch, error))
    return false
  }

  fun matchingBackwardEdge(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
  ): FeatureTaskRuntimeBackwardEdge? =
    runLoop.transitions.backwardEdges.firstOrNull { it.fromPhaseId == phaseId && it.triggeringVerdict == verdict }

  /**
   * Record-only resume reconstruction: a durable fix record carries this loop's context at the current
   * watermark but no `LOOP_EDGE` ledger row reconstructed it as in-flight, so the reserved iteration is
   * re-entered instead of a fresh one being allocated (no double-applied mutation). It is one-shot per
   * run — the loop is live-claimed the moment either this path or a live edge fire mints an iteration.
   * Without that bound the unbounded loop would re-satisfy this reconstruction on every re-review and
   * keep replaying the already-reviewed fix instead of earning the next remediation pass.
   */
}
