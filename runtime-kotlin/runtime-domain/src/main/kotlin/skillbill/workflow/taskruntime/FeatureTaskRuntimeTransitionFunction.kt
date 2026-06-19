package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

/**
 * Pure, deterministic transition function for the bounded-cyclic phase executor. Given a settled
 * phase, its verdict, and how many times the matching backward edge has already fired
 * ([edgeIterationCount], the durable per-edge counter the runtime mints), it computes the next
 * transition target:
 *
 * - A backward edge whose [FeatureTaskRuntimeBackwardEdge.fromPhaseId] and
 *   [FeatureTaskRuntimeBackwardEdge.triggeringVerdict] match the settled phase, with the edge below
 *   its cap, yields a backward [FeatureTaskRuntimeNextPhase.Next] to the destination, tagged with the
 *   loop id and the incremented iteration.
 * - The same edge at its cap yields a [FeatureTaskRuntimeNextPhase.TerminalBlock] carrying the loop
 *   id, the iteration count, and the unresolved verdict.
 * - Otherwise the default forward edge fires: the next forward index, or
 *   [FeatureTaskRuntimeNextPhase.TerminalAdvance] at the pipeline end.
 *
 * There is no clock, random, or IO here; the executor in runtime-application owns counter minting and
 * persistence.
 */
object FeatureTaskRuntimeTransitionFunction {
  fun nextTransition(
    declaration: FeatureTaskRuntimeTransitionDeclaration,
    currentPhaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
    edgeIterationCount: Int,
  ): FeatureTaskRuntimeNextPhase {
    require(edgeIterationCount >= 0) {
      "FeatureTaskRuntimeTransitionFunction.edgeIterationCount must be non-negative, was $edgeIterationCount."
    }
    matchingBackwardEdge(declaration, currentPhaseId, verdict)?.let { edge ->
      return if (edgeIterationCount < edge.perEdgeCap) {
        FeatureTaskRuntimeNextPhase.Next(
          phaseId = edge.destinationPhaseId,
          loopId = edge.loopId,
          edgeIteration = edgeIterationCount + 1,
        )
      } else {
        FeatureTaskRuntimeNextPhase.TerminalBlock(
          loopId = edge.loopId,
          edgeIteration = edgeIterationCount,
          unresolvedVerdict = verdict,
        )
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
    val nextIndex = index + 1
    return if (nextIndex < declaration.forwardPhaseIds.size) {
      FeatureTaskRuntimeNextPhase.Next(phaseId = declaration.forwardPhaseIds[nextIndex])
    } else {
      FeatureTaskRuntimeNextPhase.TerminalAdvance
    }
  }
}
