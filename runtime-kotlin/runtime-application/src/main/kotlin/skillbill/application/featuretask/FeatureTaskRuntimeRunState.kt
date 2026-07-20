package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

@Suppress("TooManyFunctions")
internal class FeatureTaskRuntimeRunState(
  private val initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  private val transitions: FeatureTaskRuntimeTransitionDeclaration,
  private val initialLedger: List<FeatureTaskRuntimePhaseLedgerEntry> = emptyList(),
  private val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
) {
  private val hasDurableReviewInvalidationTombstone: Boolean = initialRecords[
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
  ]?.resolvedAgentId == REVIEW_INVALIDATION_AGENT_ID
  private val inFlightReentries: MutableMap<String, InFlightReentry> = reconstructInFlightReentries()
    .filterKeys { loopId ->
      !hasDurableReviewInvalidationTombstone ||
        loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
    }.toMutableMap()

  // Phases whose durable completion was invalidated because they sit at or past a gated phase whose
  // gate is unsatisfied. Recomputing a re-entry span from the live topology can only SHRINK the
  // invalidation set, so a phase that left the span (a review completed before audit under the
  // pre-reorder ordering) would otherwise stay marked complete and never relaunch.
  private val gateInvalidatedPhases: MutableSet<String> = mutableSetOf()

  private val parsedOutputsByPayload: MutableMap<String, Map<String, Any?>> = mutableMapOf()

  // A legacy PLAN completed without its now-required PREPLAN predecessor is invalidated up front so
  // the loop re-runs PLAN rather than honouring a pre-PREPLAN completion.
  private val completed: MutableSet<String> =
    initialRecords.values
      .filter { it.status == STATUS_COMPLETED }
      .map { it.phaseId }
      .toMutableSet()
      .also(::invalidateLegacyPlanWithoutPreplan)
      .also(::invalidateIncompleteReentrySpans)
      .also(::invalidateUnsatisfiedGateSuccessors)
  private val outputs: MutableList<FeatureTaskRuntimePhaseOutput> =
    initialRecords.values
      .mapNotNull(::validatedRecordToOutput)
      .filterNot { it.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN && it.phaseId !in completed }
      .filterNot { it.phaseId in gateInvalidatedPhases }
      .toMutableList()

  private fun validatedRecordToOutput(record: FeatureTaskRuntimePhaseRecord): FeatureTaskRuntimePhaseOutput? =
    record.outputArtifact?.let { artifact ->
      val normalized = outputValidator.normalizePhaseOutput(artifact, record.phaseId)
      FeatureTaskRuntimePhaseOutput(
        phaseId = record.phaseId,
        iteration = record.attemptCount,
        payload = normalized.canonicalJson,
        normalizedOutput = normalized,
      )
    }
  private val priorRecords: MutableSet<String> = initialRecords.keys.toMutableSet()
  private val initialReviewRecord = initialRecords[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW]
    ?.takeIf { FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW !in gateInvalidatedPhases }
  private var currentReviewPassNumber: Int? = initialReviewRecord?.reviewPassNumber
    ?: initialReviewRecord?.let { 1 }
  private var completedReviewPassNumber: Int? = currentReviewPassNumber
    ?.takeIf { initialReviewRecord?.status == STATUS_COMPLETED }

  // Durable per-phase attempt count from the loaded record (0 when no record exists).
  private val persistedAttemptCounts: MutableMap<String, Int> =
    initialRecords.mapValues { (_, record) -> record.attemptCount }.toMutableMap()

  // Phases already persisted with a durable genuine-phase-agent blocked record. Branch-setup-origin
  // blocked records (resolvedAgentId == BRANCH_SETUP_AGENT_ID) are deliberately excluded: branch
  // setup is re-attemptable on resume, so a recoverable branch-setup failure must never short-circuit
  // a real phase launch once setup succeeds. Genuine non-fix-loop phase-agent blocks still re-block
  // on resume; fix-loop phase blocks relaunch through the bounded attempt policy.
  private val blockedRecords: MutableMap<String, String> = initialRecords
    .filterValues { it.status == STATUS_BLOCKED && it.resolvedAgentId != BRANCH_SETUP_AGENT_ID }
    .mapValues { (_, record) -> record.blockedReason.orEmpty() }
    .toMutableMap()

  // Phases carrying a durable branch-setup-origin blocked record from a prior run. Tracked
  // separately from blockedRecords (which only holds genuine phase-agent blocks) so the runner
  // can supersede the stale durable record once branch setup recovers on resume.
  private val branchSetupBlockedPhases: MutableSet<String> = initialRecords
    .filterValues { it.status == STATUS_BLOCKED && it.resolvedAgentId == BRANCH_SETUP_AGENT_ID }
    .keys
    .toMutableSet()

  // The per-edge iteration counter, keyed by loop id, DISTINCT from persistedAttemptCounts. Seeded
  // from the durable per-phase records' edge context (the resume watermark) so the cap survives
  // crashes; advanced as the runtime mints each backward-edge re-entry within the live run.
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

  // Loops the live run has already advanced this run, so the resume-entry cap guard does not
  // re-fire against a stale durable snapshot once the live machine owns the loop.
  private val liveClaimedLoops: MutableSet<String> = inFlightReentries.keys.toMutableSet()

  // Per-phase same-phase fix-loop budget baseline: the attempt watermark a backward-edge re-visit
  // starts from, so the schema-retry budget restarts each visit (the cross-visit bound is the
  // per-edge backward cap). 0 for a phase that has never been re-entered.
  //
  // Reconstructed from durable state on resume: live reopenForReentry resets this base on every
  // edge fire, but that map is in-memory only, so after a crash a re-entered phase would otherwise
  // start from an empty base and the per-visit budget index would degrade to the absolute monotonic
  // attempt_count — prematurely re-blocking a loop (e.g. a re-entered review whose attempt_count
  // already accrued across review_fix visits) instead of resuming to convergence (AC5). For every
  // fired loop (a durable per-edge watermark exists and the live machine has not yet claimed it),
  // seed each non-completed phase in the reopened span (destination through source) from its durable
  // attempt watermark, mirroring reopenForReentry's base = nextIteration - 1. The within-visit
  // schema-retry budget still bounds at MAX_FIX_LOOP_ITERATIONS; attempt_count stays monotonic.
  private val fixLoopBudgetBaseByPhase: MutableMap<String, Int> = reconstructFixLoopBudgetBases()

  // The generation reset is re-applied on every load that sees a durable tombstone, not just on the
  // load that minted it. The tombstone is a non-completed record, so it never re-enters the
  // gate-invalidation set that drives the mint-time reset, and that reset is in-memory only: without
  // this, the legacy review attempt watermark survives into the fresh generation and re-blocks the
  // first post-audit review as "fix loop exhausted" before it is ever launched.
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

  // The durable per-phase record as loaded at resume (null when none); carries the latest edge
  // context for the cap-on-resume guard.
  fun recordFor(phaseId: String): FeatureTaskRuntimePhaseRecord? = initialRecords[phaseId]

  // The current per-edge iteration count for the loop (0 when the edge has not yet fired).
  fun edgeIterationCount(loopId: String): Int = edgeIterationByLoop[loopId] ?: 0

  // Records the runtime-minted per-edge iteration for the loop and claims it for the live run.
  fun recordEdgeIteration(loopId: String, edgeIteration: Int) {
    edgeIterationByLoop[loopId] = edgeIteration
    liveClaimedLoops += loopId
  }

  fun isLoopLiveClaimed(loopId: String): Boolean = loopId in liveClaimedLoops

  // A durable re-entry the live topology cannot legally complete is dropped, so its per-edge
  // watermark goes with it. Left behind, the watermark charges the fresh generation of the gated
  // phase for edge fires the dropped span never used, so the first verdict under the new ordering
  // would hit the per-edge cap and take the cap-exhaustion advance with its findings unaddressed.
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

  // A backward edge re-enters the phase: drop its completed marker so the driver relaunches it.
  // Its prior validated output stays in `outputs` so latest-iteration resolution still works.
  // Reset the same-phase fix-loop budget baseline to the current attempt watermark: a backward-edge
  // re-visit is a FRESH verification (e.g. a re-review), not a schema-retry of the prior visit, so it
  // must restart the per-visit schema-retry budget. The durable attempt_count stays monotonic for
  // telemetry; the cross-visit bound is the per-edge backward cap, not the same-phase budget.
  fun reopenForReentry(phaseId: String) {
    completed.remove(phaseId)
    fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
  }

  // The 1-based same-phase fix-loop iteration relative to the current visit's budget baseline, so the
  // schema-retry budget counts only attempts within this visit, not backward-edge re-visits.
  fun fixLoopIterationFor(phaseId: String, absoluteIteration: Int): Int =
    absoluteIteration - (fixLoopBudgetBaseByPhase[phaseId] ?: 0)

  fun restartAttemptBudget(phaseId: String) {
    fixLoopBudgetBaseByPhase[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
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

  // Resume reconstruction of the per-visit budget baselines (see fixLoopBudgetBaseByPhase). For every
  // backward edge whose loop durably fired (a per-edge watermark exists), seed each non-completed
  // phase in the reopened span (destination through source) from its durable attempt watermark,
  // mirroring reopenForReentry. A completed phase is excluded: it does not re-enter runPhaseAttempts,
  // so its baseline is irrelevant and a live edge fire will reset it through reopenForReentry anyway.
  private fun reconstructFixLoopBudgetBases(): MutableMap<String, Int> {
    val bases = mutableMapOf<String, Int>()
    transitions.backwardEdges.forEach { edge ->
      if ((edgeIterationByLoop[edge.loopId] ?: 0) <= 0) {
        return@forEach
      }
      reopenedSpan(edge).forEach { phaseId ->
        if (phaseId !in completed) {
          bases[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
        }
      }
    }
    seedBudgetBasesOutsideLiveSpans(bases)
    return bases
  }

  // A phase can carry a durable attempt watermark accrued under a topology whose reopened span no
  // longer covers it: an incomplete phase left mid-loop by an earlier ordering, or a phase this load
  // invalidated behind an unsatisfied gate. Both re-enter runPhaseAttempts as a FRESH visit, so
  // without a baseline the per-visit schema-retry budget would degrade to the absolute monotonic
  // attempt count and re-block a phase that still has budget. Seeding mirrors reopenForReentry.
  private fun seedBudgetBasesOutsideLiveSpans(bases: MutableMap<String, Int>) {
    val staleLoopPhases = initialRecords.values
      .filter { it.status != STATUS_COMPLETED && it.loopId != null }
      .map { it.phaseId }
    (staleLoopPhases + gateInvalidatedPhases).forEach { phaseId ->
      if (phaseId !in completed && phaseId !in bases) {
        bases[phaseId] = maxOf(nextIteration(phaseId) - 1, 0)
      }
    }
  }

  // The forward span an edge reopens, recomputed from the live topology on every load so a resume
  // never invalidates the phase set some earlier ordering implied.
  private fun reopenedSpan(edge: FeatureTaskRuntimeBackwardEdge): List<String> =
    transitions.spanBetween(edge.destinationPhaseId, edge.fromPhaseId)

  private fun reconstructInFlightReentries(): Map<String, InFlightReentry> = buildMap {
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
        .toMutableSet()
      initialRecords.values
        .filter { record ->
          record.status == STATUS_COMPLETED &&
            record.loopId == edge.loopId &&
            record.edgeIteration == latestEdge.edgeIteration
        }
        .mapTo(completedAfterEdge) { it.phaseId }
      val span = reopenedSpan(edge)
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

  private fun invalidateIncompleteReentrySpans(completedPhases: MutableSet<String>) {
    inFlightReentries.values.forEach { reentry ->
      reentry.span
        .filterNot(reentry.completedAfterEdge::contains)
        .forEach(completedPhases::remove)
    }
  }

  // Drop every durable completion at or past a gated phase whose gate the durable record does not
  // satisfy. A record minted under an ordering that ran the gated phase first (review before audit)
  // would otherwise resume, run the gating phase, and then skip the gated one because it is still
  // marked complete — committing a tree the gated phase never saw in its settled form.
  private fun invalidateUnsatisfiedGateSuccessors(completedPhases: MutableSet<String>) {
    val durableVerdicts = completedPhases.associateWith(::durableVerdictFor)
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

  // The verdict a durable record settled with, read straight from the loaded record rather than from
  // `outputs`, which is not initialised while `completed` is still being reduced.
  private fun durableVerdictFor(phaseId: String): FeatureTaskRuntimeVerdict =
    FeatureTaskRuntimeOutputVerification.verdictFor(
      phaseId,
      parsedOutput(initialRecords[phaseId]?.let(::validatedRecordToOutput)),
    )

  // The phase-output contract admits YAML and fenced or prose-trailed JSON, so a bare JSON parse of
  // the payload reads NOTHING for those shapes and would report every audit as unverified. The JSON
  // fast path covers the common case without a second validation pass; anything else falls back to
  // the validator that admitted the payload in the first place. Memoised per payload because the
  // verdict of a settled phase is re-read on every gate evaluation and every transition.
  private fun parsedOutput(output: FeatureTaskRuntimePhaseOutput?): Map<String, Any?>? {
    val payload = output?.payload ?: return null
    return parsedOutputsByPayload.getOrPut(payload) {
      JsonSupport.parseObjectOrNull(payload)
        ?.let(JsonSupport::jsonElementToValue)
        ?.let(JsonSupport::anyToStringAnyMap)
        ?: runCatching {
          outputValidator.validateAndReadPhaseOutput(payload, sourceLabel = output.phaseId)
        }.getOrNull()
        ?: emptyMap()
    }
  }

  fun verdictFor(phaseId: String): FeatureTaskRuntimeVerdict =
    FeatureTaskRuntimeOutputVerification.verdictFor(phaseId, parsedOutput(outputFor(phaseId)))

  // The settled verdict per completed phase, the input the declared phase-entry gates evaluate. A
  // phase that is not complete is absent rather than defaulted, so a gate can never read a stale or
  // invented verdict for a phase the run has not settled.
  fun settledVerdictsByPhaseId(): Map<String, FeatureTaskRuntimeVerdict> = completed.associateWith(::verdictFor)

  // True when any phase in a durable re-entry span is unreachable under the live entry gates, so the
  // span itself cannot be completed and the re-entry must not be honoured on resume.
  fun spanBlockedByEntryGate(span: List<String>): Boolean {
    val settledVerdicts = settledVerdictsByPhaseId()
    return span.any { phaseId -> transitions.entryGateViolation(phaseId, settledVerdicts) != null }
  }

  fun unresolvedReviewFindings(phaseId: String): List<FeatureTaskRuntimeReviewFinding> =
    FeatureTaskRuntimeOutputVerification.unresolvedReviewFindings(parsedOutput(outputFor(phaseId)))

  fun unmetAuditCriteria(phaseId: String): List<String> =
    FeatureTaskRuntimeOutputVerification.unmetAuditCriteria(parsedOutput(outputFor(phaseId)))

  fun auditRepairPlan(phaseId: String): FeatureTaskRuntimeAuditRepairPlan? = outputFor(phaseId)
    ?.normalizedOutput
    ?.envelope
    ?.get("produced_outputs")
    ?.let(JsonSupport::anyToStringAnyMap)
    ?.get("audit_repair_plan")
    ?.let { auditRepairPlanFromWire(it, "$phaseId.produced_outputs.audit_repair_plan") }

  // The latest validated output for the phase (highest iteration), or null when none is present.
  fun outputFor(phaseId: String): FeatureTaskRuntimePhaseOutput? =
    outputs.filter { it.phaseId == phaseId }.maxByOrNull { it.iteration }

  fun outputCountFor(phaseId: String): Int = outputs.count { it.phaseId == phaseId }

  fun currentReviewPassNumber(): Int? = currentReviewPassNumber

  fun completedReviewPassNumber(): Int? = completedReviewPassNumber

  fun reserveReviewPass(passNumber: Int?) {
    if (passNumber != null) currentReviewPassNumber = passNumber
  }

  fun isComplete(phaseId: String): Boolean = phaseId in completed

  fun hasPriorRecord(phaseId: String): Boolean = phaseId in priorRecords

  // The durable per-phase record's blocked reason. The runner decides whether it is retryable;
  // this map preserves the prior attempt count and reason across resume.
  fun persistedBlockedReason(phaseId: String): String? = blockedRecords[phaseId]

  // True when the phase carries a stale branch-setup-origin blocked record from a prior run that
  // must be superseded now that branch setup has recovered.
  fun hasBranchSetupBlock(phaseId: String): Boolean = phaseId in branchSetupBlockedPhases

  // Branch setup succeeded for the phase this run, so any prior branch-setup-origin block is
  // recovered: forget it in-memory so the phase resumes normally. (The durable record is
  // superseded back to a running state by the runner before the phase launches.)
  fun clearBranchSetupBlock(phaseId: String) {
    branchSetupBlockedPhases.remove(phaseId)
    // The branch-setup block recorded an attemptCount but the phase agent never launched, so the
    // phase must resume at iteration 1, not be charged for the branch-setup attempt.
    persistedAttemptCounts.remove(phaseId)
  }

  // Resume the bounded fix loop from durable state: the next attempt is one past the greater
  // of the persisted record's attempt count and the latest validated output iteration. A phase
  // that already burned N attempts resumes at attempt N+1; the budget is never reset by resume.
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

internal const val REVIEW_INVALIDATION_AGENT_ID: String = "audit-gate-migration"

internal data class InFlightReentry(
  val destinationPhaseId: String,
  val edgeIteration: Int,
  val drivingVerdict: FeatureTaskRuntimeVerdict,
  val span: List<String>,
  val completedAfterEdge: Set<String>,
  val edgeSequenceNumber: Int,
)
