package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

@Suppress("TooManyFunctions")
internal class FeatureTaskRuntimeRunState(
  private val initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  private val transitions: FeatureTaskRuntimeTransitionDeclaration,
) {
  // A legacy PLAN completed without its now-required PREPLAN predecessor is invalidated up front so
  // the loop re-runs PLAN rather than honouring a pre-PREPLAN completion.
  private val completed: MutableSet<String> =
    initialRecords.values
      .filter { it.status == STATUS_COMPLETED }
      .map { it.phaseId }
      .toMutableSet()
      .also(::invalidateLegacyPlanWithoutPreplan)
  private val outputs: MutableList<FeatureTaskRuntimePhaseOutput> =
    initialRecords.values
      .mapNotNull(::recordToOutput)
      .filterNot { it.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN && it.phaseId !in completed }
      .toMutableList()
  private val priorRecords: MutableSet<String> = initialRecords.keys.toMutableSet()

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
  private val edgeIterationByLoop: MutableMap<String, Int> = initialRecords.values
    .mapNotNull { record -> record.loopId?.let { loopId -> record.edgeIteration?.let { loopId to it } } }
    .groupBy({ it.first }, { it.second })
    .mapValues { (_, iterations) -> iterations.max() }
    .toMutableMap()

  // Loops the live run has already advanced this run, so the resume-entry cap guard does not
  // re-fire against a stale durable snapshot once the live machine owns the loop.
  private val liveClaimedLoops: MutableSet<String> = mutableSetOf()

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

  fun outputs(): List<FeatureTaskRuntimePhaseOutput> = outputs.toList()

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

  // Clears a nested loop's live per-edge counter so its next fire restarts at iteration 1. Used when a
  // wider backward edge reopens a span containing the nested loop's source: that re-run is a FRESH
  // verification cycle, so the nested cap (e.g. review_fix) counts within the new outer iteration, not
  // across the whole run (AC5 — the review_fix counter resets per audit-gap iteration; the audit_gap
  // counter is independent and never reset).
  fun resetEdgeIteration(loopId: String) {
    edgeIterationByLoop.remove(loopId)
    liveClaimedLoops.remove(loopId)
  }

  fun isLoopLiveClaimed(loopId: String): Boolean = loopId in liveClaimedLoops

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
    return bases
  }

  // The forward span an edge reopens, destination through source inclusive (mirrors the live
  // recordBackwardEdge span); falls back to the destination alone when the indices do not bracket.
  private fun reopenedSpan(edge: FeatureTaskRuntimeBackwardEdge): List<String> {
    val destinationIndex = transitions.forwardPhaseIds.indexOf(edge.destinationPhaseId)
    val sourceIndex = transitions.forwardPhaseIds.indexOf(edge.fromPhaseId)
    return if (destinationIndex in 0..sourceIndex) {
      transitions.forwardPhaseIds.subList(destinationIndex, sourceIndex + 1)
    } else {
      listOf(edge.destinationPhaseId)
    }
  }

  fun verdictFor(phaseId: String): FeatureTaskRuntimeVerdict =
    FeatureTaskRuntimeOutputVerification.verdictFor(phaseId, outputFor(phaseId))

  fun unresolvedReviewFindings(phaseId: String): List<FeatureTaskRuntimeReviewFinding> =
    FeatureTaskRuntimeOutputVerification.unresolvedReviewFindings(outputFor(phaseId))

  fun unmetAuditCriteria(phaseId: String): List<String> =
    FeatureTaskRuntimeOutputVerification.unmetAuditCriteria(outputFor(phaseId))

  // The latest validated output for the phase (highest iteration), or null when none is present.
  fun outputFor(phaseId: String): FeatureTaskRuntimePhaseOutput? =
    outputs.filter { it.phaseId == phaseId }.maxByOrNull { it.iteration }

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
  }

  fun completedPhaseIds(): List<String> =
    FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.filter { it in completed }
}
