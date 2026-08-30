package skillbill.application.featuretask

import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

internal class FeatureTaskRuntimeRunState(
  internal val initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  internal val transitions: FeatureTaskRuntimeTransitionDeclaration,
  internal val initialLedger: List<FeatureTaskRuntimePhaseLedgerEntry> = emptyList(),
  internal val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  initialReviewGeneration: Int = 0,
) {
  internal var reviewGeneration: Int = initialReviewGeneration

  private val hasDurableReviewInvalidationTombstone: Boolean = initialRecords[
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
  ]?.resolvedAgentId == REVIEW_INVALIDATION_AGENT_ID
  internal val inFlightReentries: MutableMap<String, InFlightReentry> =
    FeatureTaskRuntimeRunStateReconstruction.reconstructInFlightReentries(
      transitions,
      initialLedger,
      initialRecords,
    ).filterKeys { loopId ->
      !hasDurableReviewInvalidationTombstone ||
        loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
    }.toMutableMap()

  internal val gateInvalidatedPhases: MutableSet<String> = mutableSetOf()

  internal val parsedOutputsByPayload: MutableMap<String, Map<String, Any?>> = mutableMapOf()

  internal val completed: MutableSet<String> =
    initialRecords.values
      .filter { it.status == STATUS_COMPLETED }
      .map { it.phaseId }
      .toMutableSet()
      .also(::invalidateLegacyPlanWithoutPreplan)
      .also { FeatureTaskRuntimeRunStateReconstruction.invalidateIncompleteReentrySpans(inFlightReentries.values, it) }
      .also {
        FeatureTaskRuntimeRunStateReconstruction.invalidateUnsatisfiedGateSuccessors(
          transitions,
          it,
          gateInvalidatedPhases,
          ::durableVerdictFor,
        )
      }
  internal val outputs: MutableList<FeatureTaskRuntimePhaseOutput> =
    initialRecords.values
      .mapNotNull(::validatedRecordToOutput)
      .filterNot { it.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN && it.phaseId !in completed }
      .filterNot { it.phaseId in gateInvalidatedPhases }
      .toMutableList()

  internal fun validatedRecordToOutput(record: FeatureTaskRuntimePhaseRecord): FeatureTaskRuntimePhaseOutput? =
    record.outputArtifact?.let { artifact ->
      val accepted = try {
        outputValidator.validatePhaseOutput(artifact, record.phaseId).requireAcceptedOutput(record.phaseId)
      } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
        if (record.status == STATUS_COMPLETED) throw error
        return@let null
      }
      FeatureTaskRuntimePhaseOutput(
        phaseId = record.phaseId,
        iteration = record.attemptCount,
        payload = accepted.normalizedOutput.canonicalJson,
        normalizedOutput = accepted.normalizedOutput,
        repairEvidence = record.repairEvidence ?: accepted.repairEvidence,
      )
    }
  internal val priorRecords: MutableSet<String> = initialRecords.keys.toMutableSet()
  internal val phasesLaunchedThisProcess: MutableSet<String> = mutableSetOf()
  private val initialReviewRecord = initialRecords[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW]
    ?.takeIf { FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW !in gateInvalidatedPhases }
  internal var currentReviewPassNumber: Int? = initialReviewRecord?.reviewPassNumber
    ?: initialReviewRecord?.let { 1 }
  internal var completedReviewPassNumber: Int? = currentReviewPassNumber
    ?.takeIf { initialReviewRecord?.status == STATUS_COMPLETED }

  internal val persistedAttemptCounts: MutableMap<String, Int> =
    initialRecords.mapValues { (_, record) -> record.attemptCount }.toMutableMap()

  internal val blockedRecords: MutableMap<String, String> = initialRecords
    .filterValues { it.status == STATUS_BLOCKED && it.resolvedAgentId != BRANCH_SETUP_AGENT_ID }
    .mapValues { (_, record) -> record.blockedReason.orEmpty() }
    .toMutableMap()

  internal val branchSetupBlockedPhases: MutableSet<String> = initialRecords
    .filterValues { it.status == STATUS_BLOCKED && it.resolvedAgentId == BRANCH_SETUP_AGENT_ID }
    .keys
    .toMutableSet()

  internal val edgeIterationByLoop: MutableMap<String, Int> = (
    initialRecords.values
      .mapNotNull { record -> record.loopId?.let { loopId -> record.edgeIteration?.let { loopId to it } } } +
      initialLedger.mapNotNull { entry ->
        entry.takeIf { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
          ?.loopId?.let { loopId -> entry.edgeIteration?.let { loopId to it } }
      }
    )
    .groupBy({ it.first }, { it.second })
    .mapValues { (_, iterations) -> iterations.max() }
    .toMutableMap()
    .apply {
      if (hasDurableReviewInvalidationTombstone) remove(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID)
    }

  internal val liveClaimedLoops: MutableSet<String> = inFlightReentries.keys.toMutableSet()

  internal val fixLoopBudgetBaseByPhase: MutableMap<String, Int> =
    FeatureTaskRuntimeRunStateReconstruction.reconstructFixLoopBudgetBases(
      ReconstructFixLoopBudgetBasesArgs(
        transitions = transitions,
        edgeIterationByLoop = edgeIterationByLoop,
        initialRecords = initialRecords,
        initialLedger = initialLedger,
        completed = completed,
        gateInvalidatedPhases = gateInvalidatedPhases,
        nextIteration = ::nextIteration,
      ),
    )

  init {
    if (hasDurableReviewInvalidationTombstone) resetInvalidatedReviewGeneration()
  }

  fun outputs(): List<FeatureTaskRuntimePhaseOutput> = outputs.toList()

  fun phasesRequiringDurableGateInvalidation(): Set<String> = gateInvalidatedPhases.toSet()

  fun resetInvalidatedReviewGeneration() {
    val loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
    val reviewPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
    val fixPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
    inFlightReentries.remove(loopId)
    edgeIterationByLoop.remove(loopId)
    liveClaimedLoops.remove(loopId)
    fixLoopBudgetBaseByPhase.remove(fixPhaseId)
    fixLoopBudgetBaseByPhase.remove(reviewPhaseId)
    persistedAttemptCounts.remove(fixPhaseId)
    persistedAttemptCounts.remove(reviewPhaseId)
    priorRecords.remove(reviewPhaseId)
    blockedRecords.remove(reviewPhaseId)
    currentReviewPassNumber = null
    completedReviewPassNumber = null
  }

  fun recordFor(phaseId: String): FeatureTaskRuntimePhaseRecord? = initialRecords[phaseId]

  fun reopenForReentry(phaseId: String) {
    completed.remove(phaseId)
    fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
  }

  fun invalidateProducerOutput(phaseId: String) {
    completed.remove(phaseId)
    outputs.removeAll { it.phaseId == phaseId }
    fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
  }

  fun isComplete(phaseId: String): Boolean = phaseId in completed

  fun recordCompleted(output: FeatureTaskRuntimePhaseOutput) {
    outputs += output
    completed += output.phaseId
    priorRecords += output.phaseId
    if (output.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
      completedReviewPassNumber = currentReviewPassNumber
    }
  }

  fun completedPhaseIds(): List<String> =
    FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.filter { it in completed }
}
