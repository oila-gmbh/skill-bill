package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.featuretask.model.FeatureTaskRuntimeAuditRepairStatus
import skillbill.application.featuretask.model.FeatureTaskRuntimeDecomposeTerminalStatus
import skillbill.application.featuretask.model.FeatureTaskRuntimeDegradedDiagnosticStatus
import skillbill.application.featuretask.model.FeatureTaskRuntimeOperatorDecisionPause
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStatus
import skillbill.application.featuretask.model.FeatureTaskRuntimeStatusProjection
import skillbill.application.featuretask.model.FeatureTaskRuntimeStatusRequest
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.orLegacyValidate

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
  private val currentPhaseExecutionDeriver = FeatureTaskRuntimeCurrentPhaseExecutionDeriver()

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
    val qualityGateSelection = recorder
      .loadGoalContinuationQualityGateSelection(request.workflowId, request.dbPathOverride)
      .orLegacyValidate()
    val currentPhaseId = resolveCurrentPhaseId(
      terminalDecomposeRecorded,
      records,
      phases,
      ledger,
      qualityGateSelection,
    )
    val auditGapPause = recorder.loadAuditGapPause(request.workflowId, request.dbPathOverride)
    // On a paused run the pausing audit_gap edge is not on the ledger, so the ledger-derived count
    // would under-report the honest edge iteration the run reached; the pause artifact is authoritative.
    val effectiveAuditGapIteration = auditGapPause?.edgeIteration
      ?: auditRepairProgress?.auditGapIterationCount
      ?: ledgerAuditGapIterationCount(ledger)
    val auditRepair = auditRepairStatus(
      auditRepairProgress?.copy(auditGapIterationCount = effectiveAuditGapIteration),
    )
    val validationGateRunCount = recorder.loadValidationGateProgress(request.workflowId, request.dbPathOverride)
      ?.gateRunCount
    val buildGateRunCount = recorder.loadBuildGateProgress(request.workflowId, request.dbPathOverride)?.gateRunCount
    val gateRunCount = when (currentPhaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD -> buildGateRunCount
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE -> validationGateRunCount
      else -> validationGateRunCount ?: buildGateRunCount
    }
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
        FeatureTaskRuntimeCurrentPhaseExecutionContext(
          currentPhaseId = currentPhaseId,
          records = records,
          phases = phases,
          ledger = ledger,
          auditGapIterationCount = effectiveAuditGapIteration,
          gateRunCount = gateRunCount,
        ),
      ),
      degradedDiagnostic = degradedDiagnosticStatus(request.workflowId, request.dbPathOverride),
      operatorDecisionPause = operatorDecisionPause(records, auditGapPause),
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
