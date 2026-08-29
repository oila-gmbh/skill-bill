package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

@Suppress("TooManyFunctions")
internal class FeatureTaskRuntimeRunState(
  private val initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  private val transitions: FeatureTaskRuntimeTransitionDeclaration,
  private val initialLedger: List<FeatureTaskRuntimePhaseLedgerEntry> = emptyList(),
  private val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  initialReviewGeneration: Int = 0,
) {
  private var reviewGeneration: Int = initialReviewGeneration

  private val hasDurableReviewInvalidationTombstone: Boolean = initialRecords[
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
  ]?.resolvedAgentId == REVIEW_INVALIDATION_AGENT_ID
  private val inFlightReentries: MutableMap<String, InFlightReentry> =
    FeatureTaskRuntimeRunStateReconstruction.reconstructInFlightReentries(
      transitions,
      initialLedger,
      initialRecords,
    ).filterKeys { loopId ->
      !hasDurableReviewInvalidationTombstone ||
        loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
    }.toMutableMap()

  private val gateInvalidatedPhases: MutableSet<String> = mutableSetOf()

  private val parsedOutputsByPayload: MutableMap<String, Map<String, Any?>> = mutableMapOf()

  private val completed: MutableSet<String> =
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
  private val outputs: MutableList<FeatureTaskRuntimePhaseOutput> =
    initialRecords.values
      .mapNotNull(::validatedRecordToOutput)
      .filterNot { it.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN && it.phaseId !in completed }
      .filterNot { it.phaseId in gateInvalidatedPhases }
      .toMutableList()

  private fun validatedRecordToOutput(record: FeatureTaskRuntimePhaseRecord): FeatureTaskRuntimePhaseOutput? =
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
  private val priorRecords: MutableSet<String> = initialRecords.keys.toMutableSet()
  private val phasesLaunchedThisProcess: MutableSet<String> = mutableSetOf()
  private val initialReviewRecord = initialRecords[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW]
    ?.takeIf { FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW !in gateInvalidatedPhases }
  private var currentReviewPassNumber: Int? = initialReviewRecord?.reviewPassNumber
    ?: initialReviewRecord?.let { 1 }
  private var completedReviewPassNumber: Int? = currentReviewPassNumber
    ?.takeIf { initialReviewRecord?.status == STATUS_COMPLETED }

  private val persistedAttemptCounts: MutableMap<String, Int> =
    initialRecords.mapValues { (_, record) -> record.attemptCount }.toMutableMap()

  private val blockedRecords: MutableMap<String, String> = initialRecords
    .filterValues { it.status == STATUS_BLOCKED && it.resolvedAgentId != BRANCH_SETUP_AGENT_ID }
    .mapValues { (_, record) -> record.blockedReason.orEmpty() }
    .toMutableMap()

  private val branchSetupBlockedPhases: MutableSet<String> = initialRecords
    .filterValues { it.status == STATUS_BLOCKED && it.resolvedAgentId == BRANCH_SETUP_AGENT_ID }
    .keys
    .toMutableSet()

  private val edgeIterationByLoop: MutableMap<String, Int> = (
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

  private val liveClaimedLoops: MutableSet<String> = inFlightReentries.keys.toMutableSet()

  private val fixLoopBudgetBaseByPhase: MutableMap<String, Int> =
    FeatureTaskRuntimeRunStateReconstruction.reconstructFixLoopBudgetBases(
      transitions = transitions,
      edgeIterationByLoop = edgeIterationByLoop,
      initialRecords = initialRecords,
      initialLedger = initialLedger,
      completed = completed,
      gateInvalidatedPhases = gateInvalidatedPhases,
      nextIteration = ::nextIteration,
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

  fun edgeIterationCount(loopId: String): Int = edgeIterationByLoop[loopId] ?: 0

  fun recordEdgeIteration(loopId: String, edgeIteration: Int) {
    edgeIterationByLoop[loopId] = edgeIteration
    liveClaimedLoops += loopId
  }

  fun isLoopLiveClaimed(loopId: String): Boolean = loopId in liveClaimedLoops

  fun discardStaleReentry(loopId: String) {
    inFlightReentries.remove(loopId)
    edgeIterationByLoop.remove(loopId)
    liveClaimedLoops.remove(loopId)
  }

  fun inFlightReentry(loopId: String): InFlightReentry? = inFlightReentries[loopId]

  fun latestInFlightReentry(): Pair<String, InFlightReentry>? =
    inFlightReentries.maxByOrNull { (_, reentry) -> reentry.edgeSequenceNumber }?.toPair()

  fun auditGapPlanningContextError(): String? = listOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
  ).firstNotNullOfOrNull(::planningContextError)

  private fun planningContextError(phaseId: String): String? {
    val record = initialRecords[phaseId]
    val output = outputFor(phaseId)
    if (output == null || record?.status?.let { it != STATUS_COMPLETED } == true) {
      return "Audit-gap remediation requires a valid completed original '$phaseId' output."
    }
    val validatedOutput = output.normalizedOutput?.envelope
    return when {
      validatedOutput == null ->
        "Audit-gap remediation requires a valid completed original '$phaseId' output; " +
          "its durable record carries no normalized output."
      validatedOutput["phase_id"] != phaseId ->
        "Audit-gap remediation requires a valid completed original '$phaseId' output; " +
          "the persisted record declares phase_id '${validatedOutput["phase_id"]}'."
      record?.loopId != null || record?.edgeIteration != null ->
        "Audit-gap remediation cannot prove original planning-context identity because '$phaseId' " +
          "carries legacy backward-edge metadata. Migrate or restart this experimental durable workflow; " +
          "the runtime will not regenerate or silently reuse overwritten planning context."
      else -> null
    }
  }

  fun reopenForReentry(phaseId: String) {
    completed.remove(phaseId)
    fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
  }

  fun invalidateProducerOutput(phaseId: String) {
    completed.remove(phaseId)
    outputs.removeAll { it.phaseId == phaseId }
    fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
  }

  fun fixLoopIterationFor(phaseId: String, absoluteIteration: Int): Int =
    absoluteIteration - (fixLoopBudgetBaseByPhase[phaseId] ?: 0)

  fun restartAttemptBudget(phaseId: String) {
    fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
  }

  fun trailingNonOutputAttempts(
    phaseId: String,
    isProcessFailure: (String) -> Boolean,
  ): List<FeatureTaskRuntimeNonOutputAttempt> {
    val base = fixLoopBudgetBaseByPhase[phaseId] ?: 0
    return initialLedger
      .filter { entry ->
        entry.phaseId == phaseId &&
          entry.attemptCount > base &&
          entry.action in NON_OUTPUT_LEDGER_ACTIONS
      }
      .sortedBy(FeatureTaskRuntimePhaseLedgerEntry::sequenceNumber)
      .takeLastWhile { entry ->
        entry.action == FeatureTaskRuntimePhaseLedgerAction.PAUSED ||
          isProcessFailure(entry.blockedReason.orEmpty())
      }
      .map { entry ->
        FeatureTaskRuntimeNonOutputAttempt(
          paused = entry.action == FeatureTaskRuntimePhaseLedgerAction.PAUSED,
          reason = entry.blockedReason.orEmpty(),
        )
      }
  }

  fun legacyReviewPreparationRetryConsumedBudget(phaseId: String, currentReason: String): Boolean {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ||
      !currentReason.startsWith("Phase 'review' exhausted the bounded fix loop")
    ) {
      return false
    }
    val recentBlocks = initialLedger
      .filter { entry ->
        entry.phaseId == phaseId && entry.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED
      }
      .sortedByDescending(FeatureTaskRuntimePhaseLedgerEntry::sequenceNumber)
      .take(2)
    return recentBlocks.firstOrNull()?.blockedReason == currentReason &&
      recentBlocks.getOrNull(1)?.blockedReason
        ?.startsWith("Goal-subtask review state or durable raw evidence is malformed: [SQLITE_BUSY]") == true
  }

  fun legacyLaunchSeamRejectionConsumedBudget(phaseId: String, currentReason: String): Boolean {
    if (!currentReason.startsWith("Phase '$phaseId' exhausted the bounded fix loop") ||
      phaseId !in FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER
    ) {
      return false
    }
    val recentBlocks = initialLedger
      .filter { entry ->
        entry.phaseId == phaseId && entry.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED
      }
      .sortedByDescending(FeatureTaskRuntimePhaseLedgerEntry::sequenceNumber)
      .take(2)
    return recentBlocks.firstOrNull()?.blockedReason == currentReason &&
      recentBlocks.getOrNull(1)?.blockedReason
        ?.contains("rejected an upstream bounded planning projection at the launch seam") == true
  }

  private fun durableVerdictFor(phaseId: String): FeatureTaskRuntimeVerdict =
    FeatureTaskRuntimeOutputVerification.verdictFor(
      phaseId,
      parsedOutput(initialRecords[phaseId]?.let(::validatedRecordToOutput)),
    )

  private fun parsedOutput(output: FeatureTaskRuntimePhaseOutput?): Map<String, Any?>? {
    val payload = output?.payload ?: return null
    return parsedOutputsByPayload.getOrPut(payload) {
      JsonSupport.parseObjectOrNull(payload)
        ?.let(JsonSupport::jsonElementToValue)
        ?.let(JsonSupport::anyToStringAnyMap)
        ?: runCatching {
          outputValidator.validatePhaseOutput(payload, sourceLabel = output.phaseId)
            .requireAcceptedOutput(output.phaseId)
            .normalizedOutput
            .envelope
        }.getOrNull()
        ?: emptyMap()
    }
  }

  fun verdictFor(phaseId: String): FeatureTaskRuntimeVerdict =
    FeatureTaskRuntimeOutputVerification.verdictFor(phaseId, parsedOutput(outputFor(phaseId)))

  fun settledVerdictsByPhaseId(): Map<String, FeatureTaskRuntimeVerdict> = completed.associateWith(::verdictFor)

  fun spanBlockedByEntryGate(span: List<String>): Boolean {
    val settledVerdicts = settledVerdictsByPhaseId()
    return span.any { phaseId -> transitions.entryGateViolation(phaseId, settledVerdicts) != null }
  }

  fun unresolvedReviewFindings(phaseId: String): List<FeatureTaskRuntimeReviewFinding> =
    FeatureTaskRuntimeOutputVerification.unresolvedReviewFindings(parsedOutput(outputFor(phaseId)))

  fun outputFor(phaseId: String): FeatureTaskRuntimePhaseOutput? =
    outputs.filter { it.phaseId == phaseId }.maxByOrNull { it.iteration }

  fun outputCountFor(phaseId: String): Int = outputs.count { it.phaseId == phaseId }

  fun reviewGeneration(): Int = reviewGeneration

  fun advanceReviewGeneration(next: Int) {
    if (next > reviewGeneration) reviewGeneration = next
  }

  fun evidenceGeneration(phaseId: String): Int =
    if (phaseId in FeatureTaskRuntimePhaseWorkflowDefinition.GENERATION_SCOPED_PHASE_IDS) reviewGeneration else 0

  fun currentReviewPassNumber(): Int? = currentReviewPassNumber

  fun completedReviewPassNumber(): Int? = completedReviewPassNumber

  fun reserveReviewPass(passNumber: Int?) {
    if (passNumber != null) currentReviewPassNumber = passNumber
  }

  fun isComplete(phaseId: String): Boolean = phaseId in completed

  fun hasPriorRecord(phaseId: String): Boolean = phaseId in priorRecords

  fun resumedFromPriorProcess(phaseId: String): Boolean =
    phaseId in initialRecords && phaseId !in phasesLaunchedThisProcess

  fun recordPhaseLaunched(phaseId: String) {
    phasesLaunchedThisProcess += phaseId
  }

  fun persistedBlockedReason(phaseId: String): String? = blockedRecords[phaseId]

  fun hasBranchSetupBlock(phaseId: String): Boolean = phaseId in branchSetupBlockedPhases

  fun clearBranchSetupBlock(phaseId: String) {
    branchSetupBlockedPhases.remove(phaseId)
    persistedAttemptCounts.remove(phaseId)
  }

  fun nextIteration(phaseId: String): Int {
    val latestOutputIteration = outputs.filter { it.phaseId == phaseId }.maxOfOrNull { it.iteration } ?: 0
    val persistedAttempts = persistedAttemptCounts[phaseId] ?: 0
    return maxOf(persistedAttempts, latestOutputIteration) + 1
  }

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
