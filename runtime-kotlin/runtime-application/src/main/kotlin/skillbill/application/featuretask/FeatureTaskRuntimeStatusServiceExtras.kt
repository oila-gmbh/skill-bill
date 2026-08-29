package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeAuditRepairStatus
import skillbill.application.featuretask.model.FeatureTaskRuntimeDecomposeTerminalStatus
import skillbill.application.featuretask.model.FeatureTaskRuntimeDegradedDiagnosticStatus
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStatus
import skillbill.application.featuretask.model.FeatureTaskRuntimeStatusProjection
import skillbill.application.featuretask.model.FeatureTaskRuntimeStatusRequest
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.orLegacyValidate

internal fun FeatureTaskRuntimeStatusService.buildStatusProjection(
  request: FeatureTaskRuntimeStatusRequest,
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  decomposeTerminal: FeatureTaskRuntimeDecomposeTerminal?,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): FeatureTaskRuntimeStatusProjection {
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
  val effectiveAuditGapIteration = auditGapPause?.edgeIteration
    ?: auditRepairProgress?.auditGapIterationCount
    ?: ledgerAuditGapIterationCount(ledger)
  val auditRepair = auditRepairStatus(
    auditRepairProgress?.copy(auditGapIterationCount = effectiveAuditGapIteration),
  )
  val gateRunCount = gateRunCountFor(request, currentPhaseId)
  return statusProjectionFrom(
    StatusProjectionParts(
      request = request,
      phases = phases,
      terminalDecomposeRecorded = terminalDecomposeRecorded,
      currentPhaseId = currentPhaseId,
      auditRepair = auditRepair,
      gateRunCount = gateRunCount,
      effectiveAuditGapIteration = effectiveAuditGapIteration,
      records = records,
      ledger = ledger,
      auditGapPause = auditGapPause,
      decomposeTerminal = decomposeTerminal,
    ),
  )
}

private data class StatusProjectionParts(
  val request: FeatureTaskRuntimeStatusRequest,
  val phases: List<FeatureTaskRuntimePhaseStatus>,
  val terminalDecomposeRecorded: Boolean,
  val currentPhaseId: String?,
  val auditRepair: FeatureTaskRuntimeAuditRepairStatus?,
  val gateRunCount: Int?,
  val effectiveAuditGapIteration: Int,
  val records: Map<String, FeatureTaskRuntimePhaseRecord>,
  val ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  val auditGapPause: FeatureTaskRuntimeAuditGapPause?,
  val decomposeTerminal: FeatureTaskRuntimeDecomposeTerminal?,
)

private fun FeatureTaskRuntimeStatusService.statusProjectionFrom(
  parts: StatusProjectionParts,
): FeatureTaskRuntimeStatusProjection {
  val request = parts.request
  val phases = parts.phases
  val terminalDecomposeRecorded = parts.terminalDecomposeRecorded
  return FeatureTaskRuntimeStatusProjection(
    workflowId = request.workflowId,
    featureSize = runInvariantsStore.resolve(request.workflowId, request.dbPathOverride)?.featureSize?.name,
    phases = phases,
    completeCount = phases.count { it.status == PHASE_STATUS_COMPLETED },
    pendingCount = if (terminalDecomposeRecorded) 0 else phases.count { it.status !in PHASE_TERMINAL_STATUSES },
    blockedCount = if (terminalDecomposeRecorded) 0 else phases.count { it.status == PHASE_STATUS_BLOCKED },
    currentPhaseId = parts.currentPhaseId,
    resolvedBranch = recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)?.branch,
    finalizingAgentId = agentAttributionFromPhaseState(
      recorder,
      request.workflowId,
      request.dbPathOverride,
    ).finalizingAgentId,
    decomposeTerminal = decomposeTerminalStatus(parts.decomposeTerminal),
    auditRepair = parts.auditRepair,
    gateRunCount = parts.gateRunCount,
    currentPhaseExecution = currentPhaseExecutionDeriver.derive(
      FeatureTaskRuntimeCurrentPhaseExecutionContext(
        currentPhaseId = parts.currentPhaseId,
        records = parts.records,
        phases = phases,
        ledger = parts.ledger,
        auditGapIterationCount = parts.effectiveAuditGapIteration,
        gateRunCount = parts.gateRunCount,
      ),
    ),
    degradedDiagnostic = degradedDiagnosticStatus(request.workflowId, request.dbPathOverride),
    operatorDecisionPause = operatorDecisionPause(parts.records, parts.auditGapPause),
  )
}

private fun FeatureTaskRuntimeStatusService.gateRunCountFor(
  request: FeatureTaskRuntimeStatusRequest,
  currentPhaseId: String?,
): Int? {
  val validationGateRunCount = recorder.loadValidationGateProgress(request.workflowId, request.dbPathOverride)
    ?.gateRunCount
  val buildGateRunCount = recorder.loadBuildGateProgress(request.workflowId, request.dbPathOverride)
    ?.gateRunCount
  return when (currentPhaseId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD -> buildGateRunCount
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE -> validationGateRunCount
    else -> validationGateRunCount ?: buildGateRunCount
  }
}

internal fun FeatureTaskRuntimeStatusService.degradedDiagnosticStatus(
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

internal fun FeatureTaskRuntimeStatusService.decomposeTerminalStatus(
  terminal: FeatureTaskRuntimeDecomposeTerminal?,
): FeatureTaskRuntimeDecomposeTerminalStatus? = terminal?.let {
  FeatureTaskRuntimeDecomposeTerminalStatus(
    reason = it.reason,
    parentSpecPath = it.parentSpecPath,
    decompositionManifestPath = it.decompositionManifestPath,
    subtaskSpecPaths = it.subtaskSpecPaths,
  )
}

internal fun FeatureTaskRuntimeStatusService.auditProgressFrom(
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): FeatureTaskRuntimeAuditProgress? {
  val auditRecord = records[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT] ?: return null
  return FeatureTaskRuntimeAuditConvergence.progressFrom(
    auditRecord = auditRecord,
    auditGapIterationCount = ledgerAuditGapIterationCount(ledger),
  )
}

internal fun FeatureTaskRuntimeStatusService.auditRepairStatus(
  progress: FeatureTaskRuntimeAuditProgress?,
): FeatureTaskRuntimeAuditRepairStatus? = progress?.let {
  FeatureTaskRuntimeAuditRepairStatus(
    firstPassConvergence = it.firstPassConvergence,
    auditGapIterationCount = it.auditGapIterationCount,
  )
}

internal fun FeatureTaskRuntimeStatusService.ledgerAuditGapIterationCount(
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): Int = FeatureTaskRuntimeAuditConvergence.auditGapIterationCount(ledger)
