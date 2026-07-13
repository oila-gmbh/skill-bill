package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
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
 *   [FeatureTaskRuntimeBackwardEdge.triggeringVerdict] match the settled phase yields a backward
 *   [FeatureTaskRuntimeNextPhase.Next] while its optional cap permits another iteration.
 * - A bounded edge at its cap either advances along the forward pipeline or yields a
 *   [FeatureTaskRuntimeNextPhase.TerminalBlock], according to its declared exhaustion behavior.
 * - Otherwise the default forward edge fires: the next forward index whose phase is not loop-only
 *   (loop-only phases are reachable only as backward-edge destinations), or
 *   [FeatureTaskRuntimeNextPhase.TerminalAdvance] when no such phase remains.
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
    val nextIndex = (index + 1 until declaration.forwardPhaseIds.size)
      .firstOrNull { declaration.forwardPhaseIds[it] !in declaration.loopOnlyPhaseIds }
    return if (nextIndex != null) {
      FeatureTaskRuntimeNextPhase.Next(phaseId = declaration.forwardPhaseIds[nextIndex])
    } else {
      FeatureTaskRuntimeNextPhase.TerminalAdvance
    }
  }
}
