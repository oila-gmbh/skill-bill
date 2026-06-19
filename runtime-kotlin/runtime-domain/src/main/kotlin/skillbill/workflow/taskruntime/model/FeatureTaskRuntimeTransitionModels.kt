package skillbill.workflow.taskruntime.model

/**
 * Effect-free transition declaration models for the bounded-cyclic phase executor. A transition is a
 * pure function of `(currentPhaseId, verdict, edgeIterationCount)`; the executor consumes this
 * declaration rather than hardcoding loop topology, so backward edges (the remediation loops) are
 * data, not bespoke branches. The forward pipeline is the ordered
 * [FeatureTaskRuntimeTransitionDeclaration.forwardPhaseIds]; the default forward edge from any phase
 * is the next index. Backward edges are declared explicitly and are bounded by a per-edge cap.
 */

/** The next transition target computed for a settled phase. */
sealed interface FeatureTaskRuntimeNextPhase {
  /** Re-enter or advance to [phaseId]. A backward re-entry additionally carries its loop context. */
  data class Next(
    val phaseId: String,
    val loopId: String? = null,
    val edgeIteration: Int? = null,
  ) : FeatureTaskRuntimeNextPhase {
    init {
      require(phaseId.isNotBlank()) { "FeatureTaskRuntimeNextPhase.Next.phaseId must be non-blank." }
      edgeIteration?.let { iteration ->
        require(iteration >= 1) {
          "FeatureTaskRuntimeNextPhase.Next.edgeIteration must be >= 1 when present, was $iteration."
        }
      }
    }
  }

  /** The pipeline ran to its end with no re-entry: the run advances to terminal success. */
  data object TerminalAdvance : FeatureTaskRuntimeNextPhase

  /**
   * A backward edge matched its triggering verdict but its per-edge cap is reached: the run blocks
   * loudly carrying the loop id, the exhausted iteration count, and the unresolved verdict.
   */
  data class TerminalBlock(
    val loopId: String,
    val edgeIteration: Int,
    val unresolvedVerdict: FeatureTaskRuntimeVerdict,
  ) : FeatureTaskRuntimeNextPhase {
    init {
      require(loopId.isNotBlank()) { "FeatureTaskRuntimeNextPhase.TerminalBlock.loopId must be non-blank." }
      require(edgeIteration >= 1) {
        "FeatureTaskRuntimeNextPhase.TerminalBlock.edgeIteration must be >= 1, was $edgeIteration."
      }
    }
  }
}

/**
 * One declared backward edge: when [fromPhaseId] settles with [triggeringVerdict], the run re-enters
 * [destinationPhaseId], bounded by [perEdgeCap] re-entries. [loopId] names the loop for durable
 * accounting and loud blocking.
 */
data class FeatureTaskRuntimeBackwardEdge(
  val fromPhaseId: String,
  val triggeringVerdict: FeatureTaskRuntimeVerdict,
  val destinationPhaseId: String,
  val loopId: String,
  val perEdgeCap: Int,
) {
  init {
    require(fromPhaseId.isNotBlank()) { "FeatureTaskRuntimeBackwardEdge.fromPhaseId must be non-blank." }
    require(destinationPhaseId.isNotBlank()) { "FeatureTaskRuntimeBackwardEdge.destinationPhaseId must be non-blank." }
    require(loopId.isNotBlank()) { "FeatureTaskRuntimeBackwardEdge.loopId must be non-blank." }
    require(perEdgeCap >= 1) { "FeatureTaskRuntimeBackwardEdge.perEdgeCap must be >= 1, was $perEdgeCap." }
  }
}

/**
 * The full transition topology: the ordered forward pipeline (default forward edge = next index) and
 * the set of backward edges. An edge-free declaration is behaviorally identical to a strict forward
 * pipeline.
 *
 * [loopOnlyPhaseIds] are phases the forward edge skips: they sit in the pipeline only as backward-edge
 * destinations (e.g. a remediation `implement_fix` phase) and are never reached by forward advance, so
 * a clean run never launches them. An empty set leaves the forward advance strictly index+1.
 */
data class FeatureTaskRuntimeTransitionDeclaration(
  val forwardPhaseIds: List<String>,
  val backwardEdges: List<FeatureTaskRuntimeBackwardEdge> = emptyList(),
  val loopOnlyPhaseIds: Set<String> = emptySet(),
) {
  init {
    require(forwardPhaseIds.isNotEmpty()) {
      "FeatureTaskRuntimeTransitionDeclaration.forwardPhaseIds must list at least one phase."
    }
    require(forwardPhaseIds.none(String::isBlank)) {
      "FeatureTaskRuntimeTransitionDeclaration.forwardPhaseIds must not contain blank ids."
    }
    require(forwardPhaseIds.toSet().size == forwardPhaseIds.size) {
      "FeatureTaskRuntimeTransitionDeclaration.forwardPhaseIds must be distinct."
    }
    require(loopOnlyPhaseIds.all { it in forwardPhaseIds }) {
      "FeatureTaskRuntimeTransitionDeclaration.loopOnlyPhaseIds must be a subset of forwardPhaseIds."
    }
    backwardEdges.forEach { edge ->
      require(edge.fromPhaseId in forwardPhaseIds) {
        "FeatureTaskRuntimeBackwardEdge.fromPhaseId '${edge.fromPhaseId}' is not in the forward pipeline."
      }
      require(edge.destinationPhaseId in forwardPhaseIds) {
        "FeatureTaskRuntimeBackwardEdge.destinationPhaseId '${edge.destinationPhaseId}' is not in the forward pipeline."
      }
    }
  }
}
