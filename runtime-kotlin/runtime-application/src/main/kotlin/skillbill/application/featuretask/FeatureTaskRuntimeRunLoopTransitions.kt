package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

@Inject
class FeatureTaskRuntimeRunLoopTransitions {
  fun qualityGateSelection(runLoop: FeatureTaskRuntimeRunLoop): FeatureTaskRuntimeQualityGateSelection =
    runLoop.request.goalContinuation?.qualityGateSelection ?: FeatureTaskRuntimeQualityGateSelection.VALIDATE

  fun transitionTarget(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    edge: FeatureTaskRuntimeBackwardEdge?,
    effectiveVerdict: FeatureTaskRuntimeVerdict,
    transition: FeatureTaskRuntimeNextPhase,
  ): String? = when (transition) {
    is FeatureTaskRuntimeNextPhase.TerminalAdvance -> null
    is FeatureTaskRuntimeNextPhase.TerminalBlock -> {
      runLoop.collaborators.planningBranch.blockOnCapExhaustion(runLoop, phaseId, transition)
      null
    }
    is FeatureTaskRuntimeNextPhase.Next -> nextTransitionTarget(runLoop, phaseId, edge, effectiveVerdict, transition)
  }

  fun nextTransitionTarget(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    edge: FeatureTaskRuntimeBackwardEdge?,
    effectiveVerdict: FeatureTaskRuntimeVerdict,
    transition: FeatureTaskRuntimeNextPhase.Next,
  ): String? {
    val loopId = transition.loopId
    return when {
      loopId == null && !establishForwardCheckpoint(runLoop, phaseId, transition.phaseId) -> null
      loopId == null -> transition.phaseId
      reentersMutatingPhase(runLoop, requireNotNull(edge), transition.phaseId) &&
        !runLoop.collaborators.checkpointContinued1.establishRemediationCheckpoint(runLoop, phaseId, loopId) -> null
      loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID &&
        !authoritativeAuditRepairPlanMatches(runLoop, phaseId) -> {
        runLoop.collaborators.planningBranch.blockAt(
          runLoop,
          phaseId,
          "Audit-gap edge requires unmet acceptance criteria on the settled audit; none were readable.",
        )
        null
      }
      else -> {
        runLoop.collaborators.backwardEdge.recordBackwardEdge(
          runLoop,
          BackwardEdgeRecordArgs(
            edge = edge,
            destinationPhaseId = transition.phaseId,
            loopId = loopId,
            edgeIteration = requireNotNull(transition.edgeIteration),
            verdict = effectiveVerdict,
          ),
        )
        transition.phaseId
      }
    }
  }

  fun authoritativeAuditRepairPlanMatches(runLoop: FeatureTaskRuntimeRunLoop, auditPhaseId: String): Boolean =
    runLoop.state.verdictFor(auditPhaseId) == FeatureTaskRuntimeVerdict.GAPS_FOUND

  fun reentersMutatingPhase(
    runLoop: FeatureTaskRuntimeRunLoop,
    edge: FeatureTaskRuntimeBackwardEdge,
    destinationPhaseId: String,
  ): Boolean = spanBetween(
    runLoop,
    destinationPhaseId,
    edge.fromPhaseId,
  ).any(FeatureTaskRuntimePhaseWorkflowDefinition::isMutatingPhase)

  fun spanBetween(
    runLoop: FeatureTaskRuntimeRunLoop,
    destinationPhaseId: String,
    sourcePhaseId: String,
  ): List<String> = runLoop.transitions.spanBetween(destinationPhaseId, sourcePhaseId)

  fun establishForwardCheckpoint(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    destinationPhaseId: String,
  ): Boolean = if (
    precedingPhaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT &&
    destinationPhaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
  ) {
    runLoop.collaborators.checkpointContinued3.checkpointEstablished(
      runLoop,
      precedingPhaseId = precedingPhaseId,
      loopId = null,
      intent = FeatureTaskRuntimeCheckpointMessage.INTENT_AUDITED_IMPLEMENTATION,
      blockedReason = { branch,
                        error,
        ->
        runLoop.collaborators.planningBranch.auditReviewCheckpointBlockedReason(branch, error)
      },
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
}
