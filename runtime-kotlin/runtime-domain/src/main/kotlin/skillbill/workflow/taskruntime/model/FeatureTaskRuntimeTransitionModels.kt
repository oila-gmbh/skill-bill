package skillbill.workflow.taskruntime.model

/**
 * Effect-free transition declaration models for the bounded-cyclic phase executor. A transition is a
 * pure function of `(currentPhaseId, verdict, edgeIterationCount)`; the executor consumes this
 * declaration rather than hardcoding loop topology, so backward edges (the remediation loops) are
 * data, not bespoke branches. The forward pipeline is the ordered
 * [FeatureTaskRuntimeTransitionDeclaration.forwardPhaseIds]; the default forward edge from any phase
 * is the next index. Backward edges are declared explicitly and may carry a per-edge cap.
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

enum class FeatureTaskRuntimeCapExhaustionBehavior {
  BLOCK,
  ADVANCE,
}

/**
 * One declared backward edge: when [fromPhaseId] settles with [triggeringVerdict], the run re-enters
 * [destinationPhaseId]. [perEdgeCap] bounds re-entries when present; `null` allows reconciliation to
 * continue until the triggering verdict clears. [loopId] names the loop for durable accounting.
 */
data class FeatureTaskRuntimeBackwardEdge(
  val fromPhaseId: String,
  val triggeringVerdict: FeatureTaskRuntimeVerdict,
  val destinationPhaseId: String,
  val loopId: String,
  val perEdgeCap: Int?,
  val capExhaustionBehavior: FeatureTaskRuntimeCapExhaustionBehavior =
    FeatureTaskRuntimeCapExhaustionBehavior.BLOCK,
) {
  init {
    require(fromPhaseId.isNotBlank()) { "FeatureTaskRuntimeBackwardEdge.fromPhaseId must be non-blank." }
    require(destinationPhaseId.isNotBlank()) { "FeatureTaskRuntimeBackwardEdge.destinationPhaseId must be non-blank." }
    require(loopId.isNotBlank()) { "FeatureTaskRuntimeBackwardEdge.loopId must be non-blank." }
    require(perEdgeCap == null || perEdgeCap >= 1) {
      "FeatureTaskRuntimeBackwardEdge.perEdgeCap must be null or >= 1, was $perEdgeCap."
    }
  }
}

/**
 * One declared phase-entry gate: [phaseId] is unreachable until [requiredPhaseId] has settled with
 * [requiredVerdict]. The gate is topology data rather than a bespoke branch in the transition
 * function, so an ordering invariant stays inspectable and testable without phase-identity branching.
 */
data class FeatureTaskRuntimePhaseEntryGate(
  val phaseId: String,
  val requiredPhaseId: String,
  val requiredVerdict: FeatureTaskRuntimeVerdict,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimePhaseEntryGate.phaseId must be non-blank." }
    require(requiredPhaseId.isNotBlank()) {
      "FeatureTaskRuntimePhaseEntryGate.requiredPhaseId must be non-blank."
    }
    require(phaseId != requiredPhaseId) {
      "FeatureTaskRuntimePhaseEntryGate.requiredPhaseId must differ from phaseId, both were '$phaseId'."
    }
  }
}

/**
 * The full transition topology: the ordered forward pipeline (default forward edge = next index), the
 * set of backward edges, and the phase-entry gates. An edge-free, gate-free declaration is
 * behaviorally identical to a strict forward pipeline.
 *
 * [loopOnlyPhaseIds] are phases the forward edge skips: they sit in the pipeline only as backward-edge
 * destinations (e.g. a remediation `implement_fix` phase) and are never reached by forward advance, so
 * a clean run never launches them. An empty set leaves the forward advance strictly index+1.
 *
 * [entryGates] declare ordering invariants the topology alone cannot enforce: a gated phase may only
 * be entered once its gating phase settled with the required verdict. The init block requires each
 * gate's required phase to strictly precede the gated phase in [forwardPhaseIds], so a future reorder
 * that regresses the ordering fails at class-init rather than silently at runtime.
 */
data class FeatureTaskRuntimeTransitionDeclaration(
  val forwardPhaseIds: List<String>,
  val backwardEdges: List<FeatureTaskRuntimeBackwardEdge> = emptyList(),
  val loopOnlyPhaseIds: Set<String> = emptySet(),
  val entryGates: List<FeatureTaskRuntimePhaseEntryGate> = emptyList(),
) {
  /**
   * The gate blocking entry into [phaseId] given the settled verdict per completed phase, or `null`
   * when the phase is ungated or every gate is satisfied. Single shared predicate for both
   * enforcement seams (the transition function and the run loop's phase-entry seam) so the rule
   * cannot drift between them.
   */
  fun entryGateViolation(
    phaseId: String,
    settledVerdictsByPhaseId: Map<String, FeatureTaskRuntimeVerdict>,
  ): FeatureTaskRuntimePhaseEntryGate? = entryGates.firstOrNull { gate ->
    gate.phaseId == phaseId && settledVerdictsByPhaseId[gate.requiredPhaseId] != gate.requiredVerdict
  }

  /**
   * The forward span a backward edge reopens, [destinationPhaseId] through [sourcePhaseId] inclusive,
   * derived from the LIVE [forwardPhaseIds] rather than from any durable record. Resume therefore
   * always invalidates the phase set the current topology implies, so a run recorded under an older
   * ordering cannot resume against a stale span. Falls back to the destination alone when the indices
   * do not bracket. Single source for the run loop's live edge path and the run state's resume path.
   */
  fun spanBetween(destinationPhaseId: String, sourcePhaseId: String): List<String> {
    val destinationIndex = forwardPhaseIds.indexOf(destinationPhaseId)
    val sourceIndex = forwardPhaseIds.indexOf(sourcePhaseId)
    return if (destinationIndex in 0..sourceIndex) {
      forwardPhaseIds.subList(destinationIndex, sourceIndex + 1)
    } else {
      listOf(destinationPhaseId)
    }
  }

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
    entryGates.forEach { gate ->
      val gatedIndex = forwardPhaseIds.indexOf(gate.phaseId)
      val requiredIndex = forwardPhaseIds.indexOf(gate.requiredPhaseId)
      require(gatedIndex >= 0) {
        "FeatureTaskRuntimePhaseEntryGate.phaseId '${gate.phaseId}' is not in the forward pipeline."
      }
      require(requiredIndex >= 0) {
        "FeatureTaskRuntimePhaseEntryGate.requiredPhaseId '${gate.requiredPhaseId}' is not in the forward pipeline."
      }
      require(requiredIndex < gatedIndex) {
        "FeatureTaskRuntimePhaseEntryGate requires '${gate.requiredPhaseId}' to precede '${gate.phaseId}' in the " +
          "forward pipeline, but it is at index $requiredIndex against $gatedIndex."
      }
    }
  }
}
