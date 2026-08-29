package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

internal fun FeatureTaskRuntimeRunLoop.qualityGateSelection(): FeatureTaskRuntimeQualityGateSelection =
  request.goalContinuation?.qualityGateSelection ?: FeatureTaskRuntimeQualityGateSelection.VALIDATE

internal fun FeatureTaskRuntimeRunLoop.transitionTarget(
  phaseId: String,
  edge: FeatureTaskRuntimeBackwardEdge?,
  effectiveVerdict: FeatureTaskRuntimeVerdict,
  transition: FeatureTaskRuntimeNextPhase,
): String? = when (transition) {
  is FeatureTaskRuntimeNextPhase.TerminalAdvance -> null
  is FeatureTaskRuntimeNextPhase.TerminalBlock -> {
    blockOnCapExhaustion(phaseId, transition)
    null
  }
  is FeatureTaskRuntimeNextPhase.Next -> nextTransitionTarget(phaseId, edge, effectiveVerdict, transition)
}

internal fun FeatureTaskRuntimeRunLoop.nextTransitionTarget(
  phaseId: String,
  edge: FeatureTaskRuntimeBackwardEdge?,
  effectiveVerdict: FeatureTaskRuntimeVerdict,
  transition: FeatureTaskRuntimeNextPhase.Next,
): String? {
  val loopId = transition.loopId
  return when {
    loopId == null && !establishForwardCheckpoint(phaseId, transition.phaseId) -> null
    loopId == null -> transition.phaseId
    reentersMutatingPhase(requireNotNull(edge), transition.phaseId) &&
      !establishRemediationCheckpoint(phaseId, loopId) -> null
    loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID &&
      !authoritativeAuditRepairPlanMatches(phaseId) -> {
      blockAt(
        phaseId,
        "Audit-gap edge requires unmet acceptance criteria on the settled audit; none were readable.",
      )
      null
    }
    else -> {
      recordBackwardEdge(
        edge = edge,
        destinationPhaseId = transition.phaseId,
        loopId = loopId,
        edgeIteration = requireNotNull(transition.edgeIteration),
        verdict = effectiveVerdict,
      )
      transition.phaseId
    }
  }
}

internal fun FeatureTaskRuntimeRunLoop.authoritativeAuditRepairPlanMatches(auditPhaseId: String): Boolean =
  state.verdictFor(auditPhaseId) == FeatureTaskRuntimeVerdict.GAPS_FOUND

internal fun FeatureTaskRuntimeRunLoop.reentersMutatingPhase(
  edge: FeatureTaskRuntimeBackwardEdge,
  destinationPhaseId: String,
): Boolean =
  spanBetween(destinationPhaseId, edge.fromPhaseId).any(FeatureTaskRuntimePhaseWorkflowDefinition::isMutatingPhase)

internal fun FeatureTaskRuntimeRunLoop.spanBetween(destinationPhaseId: String, sourcePhaseId: String): List<String> =
  transitions.spanBetween(destinationPhaseId, sourcePhaseId)

internal fun FeatureTaskRuntimeRunLoop.establishForwardCheckpoint(
  precedingPhaseId: String,
  destinationPhaseId: String,
): Boolean = if (
  precedingPhaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT &&
  destinationPhaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
) {
  checkpointEstablished(
    precedingPhaseId = precedingPhaseId,
    loopId = null,
    intent = FeatureTaskRuntimeCheckpointMessage.INTENT_AUDITED_IMPLEMENTATION,
    blockedReason = ::auditReviewCheckpointBlockedReason,
  )
} else {
  true
}

/**
 * Every path that lets the remediation proceed records the pre-fix sha, including the paths that
 * skip the checkpoint commit. HEAD is the pre-fix tree on all of them, and without the sha the
 * reserved pass silently falls back to labelling the full base-to-current delta as the pre-fix
 * tree — the exact scope bound AC-012 exists to enforce.
 *
 * A Stage commit and its base record are one unit: if `updateReviewState` fails after the commit,
 * HEAD soft-resets to the pre-commit parent so the branch ref and the durable base stay paired.
 */
