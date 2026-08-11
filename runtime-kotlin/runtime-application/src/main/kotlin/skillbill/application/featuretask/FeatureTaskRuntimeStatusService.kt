package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.model.FeatureTaskRuntimeAuditRepairStatus
import skillbill.application.model.FeatureTaskRuntimeDecomposeTerminalStatus
import skillbill.application.model.FeatureTaskRuntimePhaseStatus
import skillbill.application.model.FeatureTaskRuntimeStatusProjection
import skillbill.application.model.FeatureTaskRuntimeStatusRequest
import skillbill.application.model.IdeStatusCurrentPhaseExecution
import skillbill.application.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGenerationHistory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

/**
 * Read-only status service that projects durable per-phase records and the ledger into a typed
 * projection: phases ordered by the definition's `stepIds`, complete/pending/blocked counts, and
 * the first not-yet-complete phase as the current phase. No orchestration, no resume logic.
 */
@Inject
class FeatureTaskRuntimeStatusService(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  private val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
) {
  /**
   * Projects the read-only status. Returns null only when the workflow row is absent,
   * distinguishing "no such workflow" from "workflow exists but no phase has a record yet"
   * (an empty record map projects every phase as pending).
   */
  fun status(request: FeatureTaskRuntimeStatusRequest): FeatureTaskRuntimeStatusProjection? {
    val records = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride) ?: return null
    val decomposeTerminal = decomposeTerminalRecorder.loadDecomposeTerminal(request.workflowId, request.dbPathOverride)
    val cachedAuditRepairProgress =
      recorder.loadAuditRepairState(request.workflowId, request.dbPathOverride)?.progress
    // Blocked-ness is derived primarily from the DURABLE per-phase records (a blocked phase
    // persists a terminal `blocked` record that survives ledger pruning); the append-only ledger
    // is supplementary detail only. A later non-blocked ledger entry from a resumed run can still
    // supersede a stale block, but a durable blocked record on a phase always reports blocked.
    val ledger = recorder.loadPhaseLedger(request.workflowId, request.dbPathOverride).orEmpty()
    val auditRepairProgress = auditRepairProgressFrom(
      recorder.loadAuditGenerationHistory(request.workflowId, request.dbPathOverride),
      ledger,
      cachedAuditRepairProgress,
    )
    val durableBlockedPhaseIds = records.filterValues { it.status == STATUS_BLOCKED }.keys
    val blockedPhaseIds = durableBlockedPhaseIds + ledgerBlockedPhaseIds(ledger, durableBlockedPhaseIds)
    val phases = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.map { phaseId ->
      records[phaseId].toPhaseStatus(
        phaseId,
        blocked = phaseId in blockedPhaseIds,
        continuationKind = latestContinuationKind(ledger, phaseId),
      )
    }
    val terminalDecomposeRecorded = decomposeTerminal != null
    val currentPhaseId =
      if (terminalDecomposeRecorded) {
        null
      } else {
        // Skip a loop-only phase (e.g. implement_fix) only while it is still pending: it is
        // permanently pending on a clean forward run and is reached only as a backward-edge
        // destination, so reporting a never-run one as current would mislead operators. A loop-only
        // phase that is actually running or blocked mid-loop still surfaces. A run with no incomplete
        // non-loop-only phase reports none (a completed run is terminal).
        currentReentryPhaseId(records, ledger) ?: phases.firstOrNull {
          it.status != STATUS_COMPLETED &&
            !(it.phaseId in LOOP_ONLY_PHASE_IDS && it.status == STATUS_PENDING)
        }?.phaseId
      }
    val auditRepair = auditRepairStatus(
      auditRepairProgress,
      cachedCounterDisagreement(auditRepairProgress, cachedAuditRepairProgress),
    )
    val gateRunCount = recorder.loadValidationGateProgress(request.workflowId, request.dbPathOverride)?.gateRunCount
    return FeatureTaskRuntimeStatusProjection(
      workflowId = request.workflowId,
      featureSize = runInvariantsStore.resolve(request.workflowId, request.dbPathOverride)?.featureSize?.name,
      phases = phases,
      completeCount = phases.count { it.status == STATUS_COMPLETED },
      pendingCount = if (terminalDecomposeRecorded) 0 else phases.count { it.status !in TERMINAL_PHASE_STATUSES },
      blockedCount = if (terminalDecomposeRecorded) 0 else phases.count { it.status == STATUS_BLOCKED },
      currentPhaseId = currentPhaseId,
      resolvedBranch = recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)?.branch,
      finalizingAgentId = agentAttributionFromPhaseState(
        recorder,
        request.workflowId,
        request.dbPathOverride,
      ).finalizingAgentId,
      decomposeTerminal = decomposeTerminal?.let {
        FeatureTaskRuntimeDecomposeTerminalStatus(
          reason = it.reason,
          parentSpecPath = it.parentSpecPath,
          decompositionManifestPath = it.decompositionManifestPath,
          subtaskSpecPaths = it.subtaskSpecPaths,
        )
      },
      auditRepair = auditRepair,
      gateRunCount = gateRunCount,
      currentPhaseExecution = deriveCurrentPhaseExecution(
        currentPhaseId = currentPhaseId,
        records = records,
        phases = phases,
        ledger = ledger,
        auditGapIterationCount = auditRepair?.auditGapIterationCount
          ?: ledgerAuditGapIterationCount(ledger),
        gateRunCount = gateRunCount,
      ),
    )
  }

  /**
   * Audit-convergence counters, derived from the append-only generation history rather than read from a
   * stored counter. The audit-loop count comes from the phase ledger's own audit-gap edge trail. The
   * replaceable cache is written before the loop edge it will later reflect and the ledger is pruned while
   * the cache is monotone, so the two disagreeing is an ordinary bookkeeping state: status reports the
   * derived value and names the disagreement as a field instead of failing the operator's only view of the
   * run.
   */
  private fun auditRepairProgressFrom(
    history: FeatureTaskRuntimeAuditGenerationHistory,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    cached: FeatureTaskRuntimeAuditRepairProgress?,
  ): FeatureTaskRuntimeAuditRepairProgress? {
    val ledgerCount = ledgerAuditGapIterationCount(ledger)
    if (history.generations.isEmpty()) {
      // The replaceable cache may hold a nonzero count written before its LOOP_EDGE lands.
      // Without durable audit-gap edge evidence, never expose that cache as a semantic loop.
      return cached?.copy(auditGapIterationCount = ledgerCount)
    }
    return history.deriveProgress(auditGapIterationCount = ledgerCount)
  }

  private fun cachedCounterDisagreement(
    derived: FeatureTaskRuntimeAuditRepairProgress?,
    cached: FeatureTaskRuntimeAuditRepairProgress?,
  ): String? {
    if (derived == null || cached == null || cached === derived) return null
    if (cached.auditGapIterationCount == derived.auditGapIterationCount) return null
    return "audit_gap_iteration_count derived from durable generations is ${derived.auditGapIterationCount}; " +
      "the replaceable audit-repair cache holds ${cached.auditGapIterationCount}. The derived value is reported."
  }

  private fun ledgerAuditGapIterationCount(ledger: List<FeatureTaskRuntimePhaseLedgerEntry>): Int =
    ledger.filter { it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID }
      .mapNotNull { it.edgeIteration }
      .maxOrNull()
      ?: 0

  /**
   * Derives one current-phase execution value from the same durable inputs that selected
   * [currentPhaseId]. Never reads a completed neighbour's historical counter as current.
   */
  private fun deriveCurrentPhaseExecution(
    currentPhaseId: String?,
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    phases: List<FeatureTaskRuntimePhaseStatus>,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    auditGapIterationCount: Int,
    gateRunCount: Int?,
  ): IdeStatusCurrentPhaseExecution? {
    val phaseId = currentPhaseId?.takeIf(String::isNotBlank) ?: return null
    val phaseStatus = phases.firstOrNull { it.phaseId == phaseId } ?: return null
    val record = records[phaseId]
    return when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ->
        if (auditGapIterationCount >= 1) {
          IdeStatusCurrentPhaseExecution(
            phaseId = phaseId,
            kind = IdeStatusCurrentPhaseExecutionKind.SEMANTIC_LOOP,
            count = auditGapIterationCount,
          )
        } else if (phaseStatus.attemptCount >= 1 || record != null) {
          // First audit pass: no audit-gap edge has fired, so this is a pass, not loop 1.
          IdeStatusCurrentPhaseExecution(
            phaseId = phaseId,
            kind = IdeStatusCurrentPhaseExecutionKind.PASS,
            count = 1,
          )
        } else {
          null
        }
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        activeReviewPassNumber(record, ledger)?.let { pass ->
          IdeStatusCurrentPhaseExecution(
            phaseId = phaseId,
            kind = IdeStatusCurrentPhaseExecutionKind.PASS,
            count = pass,
          )
        } ?: attemptExecution(phaseId, phaseStatus.attemptCount)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ->
        if (gateRunCount != null && gateRunCount >= 1) {
          IdeStatusCurrentPhaseExecution(
            phaseId = phaseId,
            kind = IdeStatusCurrentPhaseExecutionKind.GATE_RUN,
            count = gateRunCount,
          )
        } else {
          attemptExecution(phaseId, phaseStatus.attemptCount)
        }
      else ->
        edgeExecution(phaseId, record, ledger) ?: attemptExecution(phaseId, phaseStatus.attemptCount)
    }
  }

  private fun edgeExecution(
    phaseId: String,
    record: FeatureTaskRuntimePhaseRecord?,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): IdeStatusCurrentPhaseExecution? {
    val (loopId, edgeIteration) = activeEdgeContext(phaseId, record, ledger) ?: return null
    val edge = FeatureTaskRuntimePhaseWorkflowDefinition.backwardEdgeForLoop(loopId) ?: return null
    val cap = edge.perEdgeCap
    return if (cap != null) {
      IdeStatusCurrentPhaseExecution(
        phaseId = phaseId,
        kind = IdeStatusCurrentPhaseExecutionKind.BOUNDED_EDGE,
        count = edgeIteration,
        total = cap,
      )
    } else {
      IdeStatusCurrentPhaseExecution(
        phaseId = phaseId,
        kind = IdeStatusCurrentPhaseExecutionKind.SEMANTIC_LOOP,
        count = edgeIteration,
      )
    }
  }

  /**
   * Active review pass for the current review phase. A completed prior review record is omitted
   * when a newer review_fix LOOP_EDGE has reopened review, so a stale pass cannot appear current
   * after the workflow advances or re-enters.
   */
  private fun activeReviewPassNumber(
    record: FeatureTaskRuntimePhaseRecord?,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): Int? {
    val pass = record?.reviewPassNumber ?: return null
    if (record.status != STATUS_COMPLETED) return pass
    val latestReviewFixEdge = ledger
      .filter {
        it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
          it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID &&
          it.edgeIteration != null
      }
      .maxByOrNull { it.sequenceNumber }
      ?: return null
    val recordEdge = record.edgeIteration
    val ledgerEdge = latestReviewFixEdge.edgeIteration!!
    // The completed watermark is still the active pass only when it matches the latest re-entry.
    if (record.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID &&
      recordEdge != null &&
      recordEdge >= ledgerEdge
    ) {
      return pass
    }
    return null
  }

  /**
   * Durable edge context for the current phase: prefer the latest LOOP_EDGE targeting this phase
   * (the active re-entry), falling back to the phase-record watermark only when no targeting edge
   * exists. Preferring a stale record watermark over a newer ledger edge would under-report
   * repeated audit, review, or regeneration iterations.
   */
  private fun activeEdgeContext(
    phaseId: String,
    record: FeatureTaskRuntimePhaseRecord?,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): Pair<String, Int>? {
    val edgeEntry = ledger
      .filter {
        it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
          it.phaseId == phaseId &&
          it.loopId != null &&
          it.edgeIteration != null
      }
      .maxByOrNull { it.sequenceNumber }
    if (edgeEntry != null) {
      return edgeEntry.loopId!! to edgeEntry.edgeIteration!!
    }
    record?.loopId?.let { loopId ->
      record.edgeIteration?.let { return loopId to it }
    }
    return null
  }

  private fun attemptExecution(phaseId: String, attemptCount: Int): IdeStatusCurrentPhaseExecution? =
    attemptCount.takeIf { it >= 1 }?.let { count ->
      IdeStatusCurrentPhaseExecution(
        phaseId = phaseId,
        kind = IdeStatusCurrentPhaseExecutionKind.ATTEMPT,
        count = count,
      )
    }

  // Derived from the generation history alone: a completed audit always appends its generation, so an absent
  // progress projection means no audit has settled, not that one converged on its first pass.
  private fun auditRepairStatus(
    progress: FeatureTaskRuntimeAuditRepairProgress?,
    cachedCounterDisagreement: String?,
  ): FeatureTaskRuntimeAuditRepairStatus? = progress?.let {
    FeatureTaskRuntimeAuditRepairStatus(
      cachedCounterDisagreement = cachedCounterDisagreement,
      firstPassConvergence = it.firstPassConvergence,
      recurringGapCount = it.recurringGapCount,
      newGapCount = it.newGapCount,
      attemptedRepairItemCount = it.attemptedRepairItemCount,
      resolvedRepairItemCount = it.resolvedRepairItemCount,
      auditGapIterationCount = it.auditGapIterationCount,
    )
  }

  // Supplementary ledger-derived blocked-ness: a phase is blocked when its newest ledger entry is
  // BLOCKED and no durable blocked record already covers it; a later entry from a resumed run
  // supersedes the block. Phases with a durable blocked record are excluded (already authoritative).
  private fun ledgerBlockedPhaseIds(
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    durableBlockedPhaseIds: Set<String>,
  ): Set<String> = ledger
    .groupBy { it.phaseId }
    .filterKeys { it !in durableBlockedPhaseIds }
    .filterValues { entries ->
      entries.maxByOrNull { it.sequenceNumber }?.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED
    }
    .keys

  private fun currentReentryPhaseId(
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): String? {
    val edge = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.backwardEdges
      .mapNotNull { declaration ->
        ledger
          .filter {
            it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == declaration.loopId
          }
          .maxByOrNull { it.sequenceNumber }
          ?.let { declaration to it }
      }
      .maxByOrNull { (_, entry) -> entry.sequenceNumber }
      ?: return null
    val (declaration, edgeEntry) = edge
    val destinationIndex = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.forwardPhaseIds
      .indexOf(declaration.destinationPhaseId)
    val sourceIndex = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.forwardPhaseIds
      .indexOf(declaration.fromPhaseId)
    val reopenedSpan = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.forwardPhaseIds
      .subList(destinationIndex, sourceIndex + 1)
    val completedAfterEdge = ledger
      .asSequence()
      .filter { it.sequenceNumber > edgeEntry.sequenceNumber }
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE }
      .mapTo(mutableSetOf()) { it.phaseId }
    records.values
      .filter {
        it.status == STATUS_COMPLETED &&
          it.loopId == declaration.loopId &&
          it.edgeIteration == edgeEntry.edgeIteration
      }
      .mapTo(completedAfterEdge) { it.phaseId }
    return reopenedSpan.firstOrNull { it !in completedAfterEdge }
  }

  // The kind of the phase's most recent re-entry, read from the durable ledger. A phase that never
  // re-entered reports none rather than a default kind that would imply a re-entry that never happened.
  //
  // Every action that can carry a kind is considered, not FIX_LOOP_ITERATION alone: crash resume and
  // process retry are stamped on START/RESUME entries and verifier re-entry on the LOOP_EDGE entry, so
  // filtering to the fix-loop action made three of the five kinds unreportable. The newest such entry
  // across all of them wins, so the reported kind is the phase's actual latest re-entry.
  private fun latestContinuationKind(ledger: List<FeatureTaskRuntimePhaseLedgerEntry>, phaseId: String): String? =
    ledger
      .filter { it.phaseId == phaseId && it.action in CONTINUATION_KIND_ACTIONS }
      .sortedByDescending { it.sequenceNumber }
      .firstNotNullOfOrNull { FeatureTaskRuntimeContinuationKind.fromLedgerDetail(it.blockedReason) }
      ?.wireValue

  private fun FeatureTaskRuntimePhaseRecord?.toPhaseStatus(
    phaseId: String,
    blocked: Boolean,
    continuationKind: String? = null,
  ): FeatureTaskRuntimePhaseStatus = if (this == null) {
    FeatureTaskRuntimePhaseStatus(
      phaseId = phaseId,
      // A phase with no record can still be blocked when the block happened before any `running`
      // record was persisted (e.g. a missing-upstream block at handoff assembly).
      status = if (blocked) STATUS_BLOCKED else STATUS_PENDING,
      attemptCount = 0,
      resolvedAgentId = null,
      finished = false,
      continuationKind = continuationKind,
    )
  } else {
    FeatureTaskRuntimePhaseStatus(
      phaseId = phaseId,
      // The record is left at `running` on a block; the ledger reclassifies it as blocked, but a
      // completed record always wins over a stale block.
      status = if (blocked && status != STATUS_COMPLETED) STATUS_BLOCKED else status,
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId.takeUnless { it == GOAL_PLANNING_IMPORT_AGENT_SENTINEL },
      finished = finishedAt != null,
      executionOrigin = executionOrigin.wireValue,
      continuationKind = continuationKind,
      launchedModel = launchedModel,
      launchedEffort = launchedEffort,
    )
  }

  private companion object {
    const val STATUS_PENDING = "pending"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_BLOCKED = "blocked"
    val TERMINAL_PHASE_STATUSES = setOf(STATUS_COMPLETED, STATUS_BLOCKED)

    // Ledger actions whose detail field may carry a continuation kind. BLOCKED is excluded on purpose:
    // its detail is the operator-facing block reason, not a kind token.
    val CONTINUATION_KIND_ACTIONS = setOf(
      FeatureTaskRuntimePhaseLedgerAction.START,
      FeatureTaskRuntimePhaseLedgerAction.RESUME,
      FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
      FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION,
    )

    // Loop-only phases (backward-edge destinations the forward edge skips) are never the current
    // phase of a forward run; sourced from the workflow definition's transition topology.
    val LOOP_ONLY_PHASE_IDS: Set<String> = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds
  }
}
