package skillbill.engine.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.FeatureTaskRuntimeAuditRepairStatus
import skillbill.engine.featuretask.model.FeatureTaskRuntimeDecomposeTerminalStatus
import skillbill.engine.featuretask.model.FeatureTaskRuntimeDegradedDiagnosticStatus
import skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseStatus
import skillbill.engine.featuretask.model.FeatureTaskRuntimeStatusProjection
import skillbill.engine.featuretask.model.FeatureTaskRuntimeStatusRequest
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.orLegacyValidate

@Inject
class FeatureTaskRuntimeStatusService(
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  private val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
) {
  val currentPhaseExecutionDeriver = FeatureTaskRuntimeCurrentPhaseExecutionDeriver()

  fun status(request: FeatureTaskRuntimeStatusRequest): FeatureTaskRuntimeStatusProjection? {
    val records = recorder.loadPhaseRecords(request.workflowId) ?: return null
    val decomposeTerminal = decomposeTerminalRecorder.loadDecomposeTerminal(request.workflowId)
    val ledger = recorder.loadPhaseLedger(request.workflowId).orEmpty()
    return buildStatusProjection(request, records, decomposeTerminal, ledger)
  }

  fun ledgerBlockedPhaseIds(
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

fun FeatureTaskRuntimeStatusService.buildStatusProjection(
  request: FeatureTaskRuntimeStatusRequest,
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  decomposeTerminal: FeatureTaskRuntimeDecomposeTerminal?,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): FeatureTaskRuntimeStatusProjection {
  val auditRepairProgress = auditProgressFrom(records, ledger)
  val durableBlockedPhaseIds =
    records.filterValues { it.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED }.keys
  val blockedPhaseIds = durableBlockedPhaseIds + ledgerBlockedPhaseIds(ledger, durableBlockedPhaseIds)
  val phases = phaseStatuses(records, blockedPhaseIds, ledger)
  val terminalDecomposeRecorded = decomposeTerminal != null
  val qualityGateSelection = recorder
    .loadGoalContinuationQualityGateSelection(request.workflowId)
    .orLegacyValidate()
  val currentPhaseId = resolveCurrentPhaseId(
    terminalDecomposeRecorded,
    records,
    phases,
    ledger,
    qualityGateSelection,
  )
  val auditGapPause = recorder.loadAuditGapPause(request.workflowId)
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
    featureSize = runInvariantsStore.resolve(request.workflowId)?.featureSize?.name,
    phases = phases,
    completeCount = phases.count { it.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED },
    pendingCount = if (terminalDecomposeRecorded) {
      0
    } else {
      phases.count {
        it.status.workflowStepStatus()?.let(PHASE_TERMINAL_STATUSES::contains) != true
      }
    },
    blockedCount = if (terminalDecomposeRecorded) {
      0
    } else {
      phases.count { it.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED }
    },
    currentPhaseId = parts.currentPhaseId,
    resolvedBranch = recorder.loadResolvedBranch(request.workflowId)?.branch,
    finalizingAgentId = agentAttributionFromPhaseState(
      recorder,
      request.workflowId,
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
    degradedDiagnostic = degradedDiagnosticStatus(request.workflowId),
    operatorDecisionPause = operatorDecisionPause(parts.records, parts.auditGapPause),
  )
}

private fun FeatureTaskRuntimeStatusService.gateRunCountFor(
  request: FeatureTaskRuntimeStatusRequest,
  currentPhaseId: String?,
): Int? {
  val validationGateRunCount = recorder.loadValidationGateProgress(request.workflowId)
    ?.gateRunCount
  val buildGateRunCount = recorder.loadBuildGateProgress(request.workflowId)
    ?.gateRunCount
  return when (currentPhaseId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD -> buildGateRunCount
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE -> validationGateRunCount
    else -> validationGateRunCount ?: buildGateRunCount
  }
}

fun FeatureTaskRuntimeStatusService.degradedDiagnosticStatus(
  workflowId: String,
): FeatureTaskRuntimeDegradedDiagnosticStatus? {
  val diagnosticSignals = recorder.loadDiagnosticSignals(workflowId)
  val latest = diagnosticSignals.lastOrNull() ?: return null
  return FeatureTaskRuntimeDegradedDiagnosticStatus(
    count = diagnosticSignals.size,
    failureClass = latest.failureClass.wireValue,
    phaseId = latest.phaseId,
    attempt = latest.attempt,
  )
}

fun FeatureTaskRuntimeStatusService.decomposeTerminalStatus(
  terminal: FeatureTaskRuntimeDecomposeTerminal?,
): FeatureTaskRuntimeDecomposeTerminalStatus? = terminal?.let {
  FeatureTaskRuntimeDecomposeTerminalStatus(
    reason = it.reason,
    parentSpecPath = it.parentSpecPath,
    decompositionManifestPath = it.decompositionManifestPath,
    subtaskSpecPaths = it.subtaskSpecPaths,
  )
}

fun FeatureTaskRuntimeStatusService.auditProgressFrom(
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): FeatureTaskRuntimeAuditProgress? {
  val auditRecord = records[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT] ?: return null
  return FeatureTaskRuntimeAuditConvergence.progressFrom(
    auditRecord = auditRecord,
    auditGapIterationCount = ledgerAuditGapIterationCount(ledger),
  )
}

fun FeatureTaskRuntimeStatusService.auditRepairStatus(
  progress: FeatureTaskRuntimeAuditProgress?,
): FeatureTaskRuntimeAuditRepairStatus? = progress?.let {
  FeatureTaskRuntimeAuditRepairStatus(
    firstPassConvergence = it.firstPassConvergence,
    auditGapIterationCount = it.auditGapIterationCount,
  )
}

fun FeatureTaskRuntimeStatusService.ledgerAuditGapIterationCount(
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): Int = FeatureTaskRuntimeAuditConvergence.auditGapIterationCount(ledger)
