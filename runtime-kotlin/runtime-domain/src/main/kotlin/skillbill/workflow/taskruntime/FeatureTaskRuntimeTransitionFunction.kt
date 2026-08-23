package skillbill.workflow.taskruntime

import skillbill.error.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

object FeatureTaskRuntimeTransitionFunction {
  fun nextTransition(
    declaration: FeatureTaskRuntimeTransitionDeclaration,
    currentPhaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
    edgeIterationCount: Int,
    context: FeatureTaskRuntimeTransitionContext = FeatureTaskRuntimeTransitionContext(),
  ): FeatureTaskRuntimeNextPhase = computeTransition(declaration, currentPhaseId, verdict, edgeIterationCount)
    .also { transition -> guardEntryGate(declaration, transition, context.settledVerdictsByPhaseId) }

  private fun guardEntryGate(
    declaration: FeatureTaskRuntimeTransitionDeclaration,
    transition: FeatureTaskRuntimeNextPhase,
    settledVerdictsByPhaseId: Map<String, FeatureTaskRuntimeVerdict>,
  ) {
    val targetPhaseId = (transition as? FeatureTaskRuntimeNextPhase.Next)?.phaseId ?: return
    declaration.entryGateViolation(targetPhaseId, settledVerdictsByPhaseId)?.let { gate ->
      throw FeatureTaskRuntimePhaseOrderViolationError(
        phaseId = gate.phaseId,
        requiredPhaseId = gate.requiredPhaseId,
        requiredVerdict = gate.requiredVerdict.wireValue,
        observedVerdict = settledVerdictsByPhaseId[gate.requiredPhaseId]?.wireValue,
      )
    }
  }

  private fun computeTransition(
    declaration: FeatureTaskRuntimeTransitionDeclaration,
    currentPhaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
    edgeIterationCount: Int,
  ): FeatureTaskRuntimeNextPhase {
    require(edgeIterationCount >= 0) {
      "FeatureTaskRuntimeTransitionFunction.edgeIterationCount must be non-negative, was $edgeIterationCount."
    }
    matchingBackwardEdge(declaration, currentPhaseId, verdict)?.let { edge ->
      val mayReenter = edge.perEdgeCap?.let { edgeIterationCount < it } ?: true
      return if (mayReenter) {
        FeatureTaskRuntimeNextPhase.Next(
          phaseId = edge.destinationPhaseId,
          loopId = edge.loopId,
          edgeIteration = edgeIterationCount + 1,
        )
      } else {
        when (edge.capExhaustionBehavior) {
          FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE -> forwardTransition(declaration, currentPhaseId)
          FeatureTaskRuntimeCapExhaustionBehavior.BLOCK -> FeatureTaskRuntimeNextPhase.TerminalBlock(
            loopId = edge.loopId,
            edgeIteration = edgeIterationCount,
            unresolvedVerdict = verdict,
          )
        }
      }
    }
    return forwardTransition(declaration, currentPhaseId)
  }

  private fun matchingBackwardEdge(
    declaration: FeatureTaskRuntimeTransitionDeclaration,
    currentPhaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
  ): FeatureTaskRuntimeBackwardEdge? = declaration.backwardEdges.firstOrNull { edge ->
    edge.fromPhaseId == currentPhaseId && edge.triggeringVerdict == verdict
  }

  private fun forwardTransition(
    declaration: FeatureTaskRuntimeTransitionDeclaration,
    currentPhaseId: String,
  ): FeatureTaskRuntimeNextPhase {
    val index = declaration.forwardPhaseIds.indexOf(currentPhaseId)
    require(index >= 0) {
      "Feature-task-runtime transition: phase '$currentPhaseId' is not in the forward pipeline."
    }
    declaration.loopOnlySuccessors[currentPhaseId]?.let { successor ->
      return FeatureTaskRuntimeNextPhase.Next(phaseId = successor)
    }
    val nextIndex = (index + 1 until declaration.forwardPhaseIds.size)
      .firstOrNull { declaration.forwardPhaseIds[it] !in declaration.loopOnlyPhaseIds }
    return if (nextIndex != null) {
      FeatureTaskRuntimeNextPhase.Next(phaseId = declaration.forwardPhaseIds[nextIndex])
    } else {
      FeatureTaskRuntimeNextPhase.TerminalAdvance
    }
  }
}
