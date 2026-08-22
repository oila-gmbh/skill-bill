package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.model.FeatureTaskRuntimeAuditRepairStatus
import skillbill.application.model.FeatureTaskRuntimeDecomposeTerminalStatus
import skillbill.application.model.FeatureTaskRuntimeDegradedDiagnosticStatus
import skillbill.application.model.FeatureTaskRuntimeOperatorDecisionPause
import skillbill.application.model.FeatureTaskRuntimePhaseStatus
import skillbill.application.model.FeatureTaskRuntimeStatusProjection
import skillbill.application.model.FeatureTaskRuntimeStatusRequest
import skillbill.application.model.IdeStatusCurrentPhaseExecution
import skillbill.application.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

private const val PHASE_STATUS_PENDING = "pending"
private const val PHASE_STATUS_COMPLETED = "completed"
private const val PHASE_STATUS_BLOCKED = "blocked"
private val PHASE_TERMINAL_STATUSES = setOf(PHASE_STATUS_COMPLETED, PHASE_STATUS_BLOCKED)
private val CONTINUATION_KIND_ACTIONS = setOf(
  FeatureTaskRuntimePhaseLedgerAction.START,
  FeatureTaskRuntimePhaseLedgerAction.RESUME,
  FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
  FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION,
)
private val LOOP_ONLY_PHASE_IDS: Set<String> = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds

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
  private val currentPhaseExecutionDeriver = CurrentPhaseExecutionDeriver()

  /**
   * Projects the read-only status. Returns null only when the workflow row is absent,
   * distinguishing "no such workflow" from "workflow exists but no phase has a record yet"
   * (an empty record map projects every phase as pending).
   */
  fun status(request: FeatureTaskRuntimeStatusRequest): FeatureTaskRuntimeStatusProjection? {
    val records = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride) ?: return null
    val decomposeTerminal = decomposeTerminalRecorder.loadDecomposeTerminal(request.workflowId, request.dbPathOverride)
    val ledger = recorder.loadPhaseLedger(request.workflowId, request.dbPathOverride).orEmpty()
    val auditRepairProgress = auditProgressFrom(records, ledger)
    val durableBlockedPhaseIds = records.filterValues { it.status == PHASE_STATUS_BLOCKED }.keys
    val blockedPhaseIds = durableBlockedPhaseIds + ledgerBlockedPhaseIds(ledger, durableBlockedPhaseIds)
    val phases = phaseStatuses(records, blockedPhaseIds, ledger)
    val terminalDecomposeRecorded = decomposeTerminal != null
    val currentPhaseId = resolveCurrentPhaseId(terminalDecomposeRecorded, records, phases, ledger)
    val auditRepair = auditRepairStatus(auditRepairProgress)
    val gateRunCount = recorder.loadValidationGateProgress(request.workflowId, request.dbPathOverride)?.gateRunCount
    return FeatureTaskRuntimeStatusProjection(
      workflowId = request.workflowId,
      featureSize = runInvariantsStore.resolve(request.workflowId, request.dbPathOverride)?.featureSize?.name,
      phases = phases,
      completeCount = phases.count { it.status == PHASE_STATUS_COMPLETED },
      pendingCount = if (terminalDecomposeRecorded) 0 else phases.count { it.status !in PHASE_TERMINAL_STATUSES },
      blockedCount = if (terminalDecomposeRecorded) 0 else phases.count { it.status == PHASE_STATUS_BLOCKED },
      currentPhaseId = currentPhaseId,
      resolvedBranch = recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)?.branch,
      finalizingAgentId = agentAttributionFromPhaseState(
        recorder,
        request.workflowId,
        request.dbPathOverride,
      ).finalizingAgentId,
      decomposeTerminal = decomposeTerminalStatus(decomposeTerminal),
      auditRepair = auditRepair,
      gateRunCount = gateRunCount,
      currentPhaseExecution = currentPhaseExecutionDeriver.derive(
        CurrentPhaseExecutionContext(
          currentPhaseId = currentPhaseId,
          records = records,
          phases = phases,
          ledger = ledger,
          auditGapIterationCount = auditRepair?.auditGapIterationCount
            ?: ledgerAuditGapIterationCount(ledger),
          gateRunCount = gateRunCount,
        ),
      ),
      degradedDiagnostic = degradedDiagnosticStatus(request.workflowId, request.dbPathOverride),
      operatorDecisionPause = operatorDecisionPause(records),
    )
  }

  private fun degradedDiagnosticStatus(
    workflowId: String,
    dbPathOverride: String?,
  ): FeatureTaskRuntimeDegradedDiagnosticStatus? {
    val diagnosticSignals = recorder.loadDiagnosticSignals(workflowId, dbPathOverride)
    val latest = diagnosticSignals.lastOrNull() ?: return null
    return FeatureTaskRuntimeDegradedDiagnosticStatus(
      count = diagnosticSignals.size,
      failureClass = latest.failureClass.wireValue,
      phaseId = latest.phaseId,
      attempt = latest.attempt,
    )
  }

  private fun decomposeTerminalStatus(
    terminal: FeatureTaskRuntimeDecomposeTerminal?,
  ): FeatureTaskRuntimeDecomposeTerminalStatus? = terminal?.let {
    FeatureTaskRuntimeDecomposeTerminalStatus(
      reason = it.reason,
      parentSpecPath = it.parentSpecPath,
      decompositionManifestPath = it.decompositionManifestPath,
      subtaskSpecPaths = it.subtaskSpecPaths,
    )
  }

  /**
   * The audit loop's progress, derived by the shared [FeatureTaskRuntimeAuditConvergence] so this
   * projection and finished telemetry cannot disagree about the same workflow. Absent when no audit
   * record exists, which keeps "never audited" distinct from "audited and found gaps".
   */
  private fun auditProgressFrom(
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): FeatureTaskRuntimeAuditProgress? {
    val auditRecord = records[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT] ?: return null
    return FeatureTaskRuntimeAuditConvergence.progressFrom(
      auditRecord = auditRecord,
      auditGapIterationCount = ledgerAuditGapIterationCount(ledger),
    )
  }

  private fun ledgerAuditGapIterationCount(ledger: List<FeatureTaskRuntimePhaseLedgerEntry>): Int =
    FeatureTaskRuntimeAuditConvergence.auditGapIterationCount(ledger)

  private fun auditRepairStatus(progress: FeatureTaskRuntimeAuditProgress?): FeatureTaskRuntimeAuditRepairStatus? =
    progress?.let {
      FeatureTaskRuntimeAuditRepairStatus(
        firstPassConvergence = it.firstPassConvergence,
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
}

private data class CurrentPhaseExecutionContext(
  val currentPhaseId: String?,
  val records: Map<String, FeatureTaskRuntimePhaseRecord>,
  val phases: List<FeatureTaskRuntimePhaseStatus>,
  val ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  val auditGapIterationCount: Int,
  val gateRunCount: Int?,
)

private class CurrentPhaseExecutionDeriver {
  /**
   * Derives one current-phase execution value from the same durable inputs that selected the
   * current phase. Never reads a completed neighbour's historical counter as current.
   */
  fun derive(context: CurrentPhaseExecutionContext): IdeStatusCurrentPhaseExecution? {
    val phaseId = context.currentPhaseId?.takeIf(String::isNotBlank) ?: return null
    val phaseStatus = context.phases.firstOrNull { it.phaseId == phaseId } ?: return null
    val record = context.records[phaseId]
    return when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ->
        auditExecution(phaseId, phaseStatus, record, context.auditGapIterationCount)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        reviewExecution(phaseId, phaseStatus, record, context.ledger)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ->
        validationExecution(phaseId, phaseStatus, context.gateRunCount)
      else ->
        edgeExecution(phaseId, record, context.ledger) ?: attemptExecution(phaseId, phaseStatus.attemptCount)
    }
  }

  private fun auditExecution(
    phaseId: String,
    phaseStatus: FeatureTaskRuntimePhaseStatus,
    record: FeatureTaskRuntimePhaseRecord?,
    auditGapIterationCount: Int,
  ): IdeStatusCurrentPhaseExecution? = when {
    auditGapIterationCount >= 1 -> IdeStatusCurrentPhaseExecution(
      phaseId = phaseId,
      kind = IdeStatusCurrentPhaseExecutionKind.SEMANTIC_LOOP,
      count = auditGapIterationCount,
    )
    phaseStatus.attemptCount >= 1 || record != null -> IdeStatusCurrentPhaseExecution(
      phaseId = phaseId,
      kind = IdeStatusCurrentPhaseExecutionKind.PASS,
      count = 1,
    )
    else -> null
  }

  private fun reviewExecution(
    phaseId: String,
    phaseStatus: FeatureTaskRuntimePhaseStatus,
    record: FeatureTaskRuntimePhaseRecord?,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): IdeStatusCurrentPhaseExecution? = activeReviewPassNumber(record, ledger)?.let { pass ->
    IdeStatusCurrentPhaseExecution(
      phaseId = phaseId,
      kind = IdeStatusCurrentPhaseExecutionKind.PASS,
      count = pass,
    )
  } ?: attemptExecution(phaseId, phaseStatus.attemptCount)

  private fun validationExecution(
    phaseId: String,
    phaseStatus: FeatureTaskRuntimePhaseStatus,
    gateRunCount: Int?,
  ): IdeStatusCurrentPhaseExecution? = gateRunCount?.takeIf { it >= 1 }?.let { count ->
    IdeStatusCurrentPhaseExecution(
      phaseId = phaseId,
      kind = IdeStatusCurrentPhaseExecutionKind.GATE_RUN,
      count = count,
    )
  } ?: attemptExecution(phaseId, phaseStatus.attemptCount)

  private fun edgeExecution(
    phaseId: String,
    record: FeatureTaskRuntimePhaseRecord?,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): IdeStatusCurrentPhaseExecution? {
    val (loopId, edgeIteration) = activeEdgeContext(phaseId, record, ledger) ?: return null
    val edge = FeatureTaskRuntimePhaseWorkflowDefinition.backwardEdgeForLoop(loopId) ?: return null
    return IdeStatusCurrentPhaseExecution(
      phaseId = phaseId,
      kind = if (edge.perEdgeCap == null) {
        IdeStatusCurrentPhaseExecutionKind.SEMANTIC_LOOP
      } else {
        IdeStatusCurrentPhaseExecutionKind.BOUNDED_EDGE
      },
      count = edgeIteration,
      total = edge.perEdgeCap,
    )
  }

  /**
   * Active review pass for the current review phase. A completed prior review record is omitted
   * when a newer review_fix LOOP_EDGE has reopened review.
   */
  private fun activeReviewPassNumber(
    record: FeatureTaskRuntimePhaseRecord?,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): Int? {
    val pass = record?.reviewPassNumber ?: return null
    if (record.status != PHASE_STATUS_COMPLETED) return pass
    val latestReviewFixEdge = ledger
      .filter {
        it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
          it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID &&
          it.edgeIteration != null
      }
      .maxByOrNull { it.sequenceNumber }
    val ledgerEdge = latestReviewFixEdge?.edgeIteration
    val reenteredReview = record.takeIf {
      it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
    }?.edgeIteration
    return ledgerEdge?.let { edge ->
      reenteredReview?.takeIf { it >= edge }
    }?.let { pass }
  }

  /**
   * Prefer the latest LOOP_EDGE targeting the current phase, falling back to the phase-record
   * watermark when no targeting edge exists.
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
    if (edgeEntry != null) return edgeEntry.loopId!! to edgeEntry.edgeIteration!!
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
}

private fun phaseStatuses(
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  blockedPhaseIds: Set<String>,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): List<FeatureTaskRuntimePhaseStatus> = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.map { phaseId ->
  records[phaseId].toPhaseStatus(
    phaseId = phaseId,
    blocked = phaseId in blockedPhaseIds,
    continuationKind = latestContinuationKind(ledger, phaseId),
  )
}

private fun resolveCurrentPhaseId(
  terminalDecomposeRecorded: Boolean,
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  phases: List<FeatureTaskRuntimePhaseStatus>,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): String? {
  if (terminalDecomposeRecorded) return null
  return currentReentryPhaseId(records, ledger) ?: phases.firstOrNull {
    it.status != PHASE_STATUS_COMPLETED &&
      !(it.phaseId in LOOP_ONLY_PHASE_IDS && it.status == PHASE_STATUS_PENDING)
  }?.phaseId
}

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
    .map { it.phaseId }
    .filter { phaseId -> records[phaseId]?.status == PHASE_STATUS_COMPLETED }
    .toMutableSet()
  records.values
    .filter {
      it.status == PHASE_STATUS_COMPLETED &&
        it.loopId == declaration.loopId &&
        it.edgeIteration == edgeEntry.edgeIteration
    }
    .mapTo(completedAfterEdge) { it.phaseId }
  return reopenedSpan.firstOrNull { it !in completedAfterEdge }
}

private fun operatorDecisionPause(
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
): FeatureTaskRuntimeOperatorDecisionPause? = records.values
  .firstOrNull { record ->
    record.status == FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED &&
      record.failureDisposition == FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION
  }
  ?.let { record ->
    FeatureTaskRuntimeOperatorDecisionPause(
      phaseId = record.phaseId,
      reason = record.blockedReason?.takeIf(String::isNotBlank),
    )
  }

private fun latestContinuationKind(ledger: List<FeatureTaskRuntimePhaseLedgerEntry>, phaseId: String): String? = ledger
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
    status = if (blocked) PHASE_STATUS_BLOCKED else PHASE_STATUS_PENDING,
    attemptCount = 0,
    resolvedAgentId = null,
    finished = false,
    continuationKind = continuationKind,
  )
} else {
  FeatureTaskRuntimePhaseStatus(
    phaseId = phaseId,
    status = if (blocked && status != PHASE_STATUS_COMPLETED) PHASE_STATUS_BLOCKED else status,
    attemptCount = attemptCount,
    resolvedAgentId = resolvedAgentId.takeUnless { it == GOAL_PLANNING_IMPORT_AGENT_SENTINEL },
    finished = finishedAt != null,
    executionOrigin = executionOrigin.wireValue,
    continuationKind = continuationKind,
    launchedModel = launchedModel,
    launchedEffort = launchedEffort,
  )
}
