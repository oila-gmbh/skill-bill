package skillbill.application.featuretask

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

class FeatureTaskRuntimeRunState(
  val initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  val transitions: FeatureTaskRuntimeTransitionDeclaration,
  val initialLedger: List<FeatureTaskRuntimePhaseLedgerEntry> = emptyList(),
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  initialReviewGeneration: Int = 0,
) {
  internal var reviewGeneration: Int = initialReviewGeneration
    private set

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

  val gateInvalidatedPhases: MutableSet<String> = mutableSetOf()

  val parsedOutputsByPayload: MutableMap<String, Map<String, Any?>> = mutableMapOf()

  val completed: MutableSet<String> =
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
  val outputs: MutableList<FeatureTaskRuntimePhaseOutput> =
    initialRecords.values
      .mapNotNull(::validatedRecordToOutput)
      .filterNot { it.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN && it.phaseId !in completed }
      .filterNot { it.phaseId in gateInvalidatedPhases }
      .toMutableList()

  fun validatedRecordToOutput(record: FeatureTaskRuntimePhaseRecord): FeatureTaskRuntimePhaseOutput? =
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
  val priorRecords: MutableSet<String> = initialRecords.keys.toMutableSet()
  val phasesLaunchedThisProcess: MutableSet<String> = mutableSetOf()
  private val initialReviewRecord = initialRecords[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW]
    ?.takeIf { FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW !in gateInvalidatedPhases }
  internal var currentReviewPassNumber: Int? = initialReviewRecord?.reviewPassNumber
    ?: initialReviewRecord?.let { 1 }
    private set
  internal var completedReviewPassNumber: Int? = currentReviewPassNumber
    ?.takeIf { initialReviewRecord?.status == STATUS_COMPLETED }
    private set

  val persistedAttemptCounts: MutableMap<String, Int> =
    initialRecords.mapValues { (_, record) -> record.attemptCount }.toMutableMap()

  val blockedRecords: MutableMap<String, String> = initialRecords
    .filterValues { it.status == STATUS_BLOCKED && it.resolvedAgentId != BRANCH_SETUP_AGENT_ID }
    .mapValues { (_, record) -> record.blockedReason.orEmpty() }
    .toMutableMap()

  val branchSetupBlockedPhases: MutableSet<String> = initialRecords
    .filterValues { it.status == STATUS_BLOCKED && it.resolvedAgentId == BRANCH_SETUP_AGENT_ID }
    .keys
    .toMutableSet()

  val edgeIterationByLoop: MutableMap<String, Int> = (
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

  val liveClaimedLoops: MutableSet<String> = inFlightReentries.keys.toMutableSet()

  val fixLoopBudgetBaseByPhase: MutableMap<String, Int> =
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

  fun fixLoopIterationFor(phaseId: String, absoluteIteration: Int): Int =
    absoluteIteration - (fixLoopBudgetBaseByPhase[phaseId] ?: 0)

  fun restartAttemptBudget(phaseId: String) {
    fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
  }

  internal fun trailingNonOutputAttempts(
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
    val recentBlocks = recentBlockedReasons(phaseId)
    return recentBlocks.firstOrNull() == currentReason &&
      recentBlocks.getOrNull(1)
        ?.startsWith("Goal-subtask review state or durable raw evidence is malformed: [SQLITE_BUSY]") == true
  }

  fun legacyLaunchSeamRejectionConsumedBudget(phaseId: String, currentReason: String): Boolean {
    if (!currentReason.startsWith("Phase '$phaseId' exhausted the bounded fix loop") ||
      phaseId !in FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER
    ) {
      return false
    }
    val recentBlocks = recentBlockedReasons(phaseId)
    return recentBlocks.firstOrNull() == currentReason &&
      recentBlocks.getOrNull(1)
        ?.contains("rejected an upstream bounded planning projection at the launch seam") == true
  }

  private fun recentBlockedReasons(phaseId: String): List<String?> = initialLedger
    .filter { entry -> entry.phaseId == phaseId && entry.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED }
    .sortedByDescending(FeatureTaskRuntimePhaseLedgerEntry::sequenceNumber)
    .take(2)
    .map(FeatureTaskRuntimePhaseLedgerEntry::blockedReason)

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

  internal val latestInFlightReentry: Pair<String, InFlightReentry>?
    get() = inFlightReentries.maxByOrNull { (_, reentry) -> reentry.edgeSequenceNumber }?.toPair()

  fun outputFor(phaseId: String): FeatureTaskRuntimePhaseOutput? =
    outputs.filter { it.phaseId == phaseId }.maxByOrNull { it.iteration }

  fun outputCountFor(phaseId: String): Int = outputs.count { it.phaseId == phaseId }

  fun nextIteration(phaseId: String): Int {
    val latestOutputIteration = outputs.filter { it.phaseId == phaseId }.maxOfOrNull { it.iteration } ?: 0
    val persistedAttempts = persistedAttemptCounts[phaseId] ?: 0
    return maxOf(persistedAttempts, latestOutputIteration) + 1
  }

  fun parsedOutput(output: FeatureTaskRuntimePhaseOutput?): Map<String, Any?>? {
    val payload = output?.payload ?: return null
    return parsedOutputsByPayload.getOrPut(payload) {
      output.normalizedOutput?.envelope
        ?: outputValidator.validatePhaseOutput(payload, sourceLabel = output.phaseId)
          .requireAcceptedOutput(output.phaseId)
          .normalizedOutput
          .envelope
    }
  }

  val auditGapPlanningContextError: String?
    get() = listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
    ).firstNotNullOfOrNull(::planningContextError)

  fun planningContextError(phaseId: String): String? {
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

  fun advanceReviewGeneration(next: Int) {
    if (next > reviewGeneration) reviewGeneration = next
  }

  fun evidenceGeneration(phaseId: String): Int =
    if (phaseId in FeatureTaskRuntimePhaseWorkflowDefinition.GENERATION_SCOPED_PHASE_IDS) reviewGeneration else 0

  fun reserveReviewPass(passNumber: Int?) {
    if (passNumber != null) currentReviewPassNumber = passNumber
  }

  fun verdictFor(phaseId: String): FeatureTaskRuntimeVerdict =
    FeatureTaskRuntimeOutputVerification.verdictFor(phaseId, parsedOutput(outputFor(phaseId)))

  val settledVerdictsByPhaseId: Map<String, FeatureTaskRuntimeVerdict>
    get() = completed.associateWith(::verdictFor)

  fun spanBlockedByEntryGate(span: List<String>): Boolean {
    val settledVerdicts = settledVerdictsByPhaseId
    return span.any { phaseId -> transitions.entryGateViolation(phaseId, settledVerdicts) != null }
  }

  fun unresolvedReviewFindings(phaseId: String): List<FeatureTaskRuntimeReviewFinding> =
    FeatureTaskRuntimeOutputVerification.unresolvedReviewFindings(parsedOutput(outputFor(phaseId)))

  fun durableVerdictFor(phaseId: String): FeatureTaskRuntimeVerdict {
    val record = initialRecords[phaseId] ?: return verdictFor(phaseId)
    return FeatureTaskRuntimeOutputVerification.verdictFor(
      phaseId,
      parsedOutput(validatedRecordToOutput(record)),
    )
  }
}

internal data class FeatureTaskRuntimeNonOutputAttempt(val paused: Boolean, val reason: String)

val NON_OUTPUT_LEDGER_ACTIONS = setOf(
  FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
  FeatureTaskRuntimePhaseLedgerAction.PAUSED,
)

const val REVIEW_INVALIDATION_AGENT_ID: String = "audit-gate-migration"

internal data class InFlightReentry(
  val destinationPhaseId: String,
  val edgeIteration: Int,
  val drivingVerdict: FeatureTaskRuntimeVerdict,
  val span: List<String>,
  val completedAfterEdge: Set<String>,
  val edgeSequenceNumber: Int,
) {
  val resumePhaseId: String
    get() = span.firstOrNull { phaseId -> phaseId !in completedAfterEdge } ?: destinationPhaseId
}
