package skillbill.workflow.taskruntime

import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdgeCapScope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseEntryGate
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

internal object FeatureTaskRuntimePhaseWorkflowTransitions {
  fun transitions(definition: WorkflowDefinition): FeatureTaskRuntimeTransitionDeclaration =
    FeatureTaskRuntimeTransitionDeclaration(
      forwardPhaseIds = definition.stepIds,
      entryGates = listOf(
        FeatureTaskRuntimePhaseEntryGate(
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
          requiredPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          requiredVerdict = FeatureTaskRuntimeVerdict.SATISFIED,
        ),
        FeatureTaskRuntimePhaseEntryGate(
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
          requiredPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
          requiredVerdict = FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
        ),
      ),
      backwardEdges = listOf(
        FeatureTaskRuntimeBackwardEdge(
          fromPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
          triggeringVerdict = FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
          destinationPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
          loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
          perEdgeCap = 1,
          capExhaustionBehavior = FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE,
          capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
        ),
        FeatureTaskRuntimeBackwardEdge(
          fromPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          triggeringVerdict = FeatureTaskRuntimeVerdict.GAPS_FOUND,
          destinationPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
          loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
          perEdgeCap = null,
          capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
          warnAfterIterations = FeatureTaskRuntimePhaseWorkflowDefinition.SEMANTIC_LOOP_WARNING_THRESHOLD,
        ),
      ),
      loopOnlyPhaseIds = setOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
      ),
      loopOnlySuccessors = emptyMap(),
    )

  fun backwardEdgeForLoop(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    loopId: String,
  ): FeatureTaskRuntimeBackwardEdge? =
    transitions.backwardEdges.firstOrNull { it.loopId == loopId }
}
