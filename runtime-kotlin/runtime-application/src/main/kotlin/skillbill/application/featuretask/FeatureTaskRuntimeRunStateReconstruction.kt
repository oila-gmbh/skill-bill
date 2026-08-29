package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

internal object FeatureTaskRuntimeRunStateReconstruction {
  fun reconstructInFlightReentries(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    initialLedger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  ): Map<String, InFlightReentry> = buildMap {
    transitions.backwardEdges.forEach { edge ->
      val latestEdge = initialLedger
        .filter { ledger ->
          ledger.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && ledger.loopId == edge.loopId
        }
        .maxByOrNull { it.sequenceNumber }
        ?: return@forEach
      val completedAfterEdge = initialLedger
        .asSequence()
        .filter { it.sequenceNumber > latestEdge.sequenceNumber }
        .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE }
        .map { it.phaseId }
        .filter { phaseId -> initialRecords[phaseId]?.status == STATUS_COMPLETED }
        .toMutableSet()
      initialRecords.values
        .filter { record ->
          record.status == STATUS_COMPLETED &&
            record.loopId == edge.loopId &&
            record.edgeIteration == latestEdge.edgeIteration
        }
        .mapTo(completedAfterEdge) { it.phaseId }
      val span = reopenedSpan(transitions, edge)
      if (span.any { phaseId -> phaseId !in completedAfterEdge }) {
        put(
          edge.loopId,
          InFlightReentry(
            destinationPhaseId = edge.destinationPhaseId,
            edgeIteration = requireNotNull(latestEdge.edgeIteration),
            drivingVerdict = edge.triggeringVerdict,
            span = span,
            completedAfterEdge = completedAfterEdge,
            edgeSequenceNumber = latestEdge.sequenceNumber,
          ),
        )
      }
    }
  }

  fun reconstructFixLoopBudgetBases(args: ReconstructFixLoopBudgetBasesArgs): MutableMap<String, Int> {
    val transitions = args.transitions
    val edgeIterationByLoop = args.edgeIterationByLoop
    val initialRecords = args.initialRecords
    val initialLedger = args.initialLedger
    val completed = args.completed
    val gateInvalidatedPhases = args.gateInvalidatedPhases
    val nextIteration = args.nextIteration
    val bases = mutableMapOf<String, Int>()
    transitions.backwardEdges.forEach { edge ->
      if ((edgeIterationByLoop[edge.loopId] ?: 0) <= 0) {
        return@forEach
      }
      reopenedSpan(transitions, edge).forEach { phaseId ->
        if (phaseId !in completed) {
          bases[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
        }
      }
    }
    seedBudgetBasesOutsideLiveSpans(bases, initialRecords, gateInvalidatedPhases, completed, nextIteration)
    seedOperatorRetryBudgetBases(bases, initialLedger, completed)
    return bases
  }

  fun invalidateIncompleteReentrySpans(
    inFlightReentries: Collection<InFlightReentry>,
    completedPhases: MutableSet<String>,
  ) {
    inFlightReentries.forEach { reentry ->
      reentry.span
        .filterNot(reentry.completedAfterEdge::contains)
        .forEach(completedPhases::remove)
    }
  }

  fun invalidateUnsatisfiedGateSuccessors(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    completedPhases: MutableSet<String>,
    gateInvalidatedPhases: MutableSet<String>,
    durableVerdictFor: (String) -> FeatureTaskRuntimeVerdict,
  ) {
    val durableVerdicts = completedPhases.associateWith(durableVerdictFor)
    transitions.entryGates.forEach { gate ->
      if (transitions.entryGateViolation(gate.phaseId, durableVerdicts) == null) {
        return@forEach
      }
      val gatedIndex = transitions.forwardPhaseIds.indexOf(gate.phaseId)
      if (gatedIndex < 0) {
        return@forEach
      }
      transitions.forwardPhaseIds
        .drop(gatedIndex)
        .filter(completedPhases::contains)
        .forEach { phaseId ->
          completedPhases.remove(phaseId)
          gateInvalidatedPhases += phaseId
        }
    }
  }

  private fun seedOperatorRetryBudgetBases(
    bases: MutableMap<String, Int>,
    initialLedger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    completed: Set<String>,
  ) {
    initialLedger
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.RETRY }
      .groupBy { it.phaseId }
      .forEach { (phaseId, retries) ->
        val latestRetry = retries.maxBy { it.sequenceNumber }
        val settledAfterRetry = initialLedger.any { entry ->
          entry.phaseId == phaseId &&
            entry.sequenceNumber > latestRetry.sequenceNumber &&
            entry.action in setOf(
              FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
              FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
            )
        }
        if (!settledAfterRetry && phaseId !in completed) {
          bases[phaseId] = latestRetry.attemptCount
        }
      }
  }

  private fun seedBudgetBasesOutsideLiveSpans(
    bases: MutableMap<String, Int>,
    initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    gateInvalidatedPhases: Set<String>,
    completed: Set<String>,
    nextIteration: (String) -> Int,
  ) {
    val staleLoopPhases = initialRecords.values
      .filter { it.status != STATUS_COMPLETED && it.loopId != null }
      .map { it.phaseId }
    (staleLoopPhases + gateInvalidatedPhases).forEach { phaseId ->
      if (phaseId !in completed && phaseId !in bases) {
        bases[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
      }
    }
  }

  private fun reopenedSpan(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    edge: FeatureTaskRuntimeBackwardEdge,
  ): List<String> = transitions.spanBetween(edge.destinationPhaseId, edge.fromPhaseId)
}
