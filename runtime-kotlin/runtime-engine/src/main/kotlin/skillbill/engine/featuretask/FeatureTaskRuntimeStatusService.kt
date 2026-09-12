package skillbill.engine.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.FeatureTaskRuntimeDecomposeTerminalStatus
import skillbill.engine.featuretask.model.FeatureTaskRuntimeDegradedDiagnosticStatus
import skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseStatus
import skillbill.engine.featuretask.model.FeatureTaskRuntimeStatusProjection
import skillbill.engine.featuretask.model.FeatureTaskRuntimeStatusRequest
import skillbill.ports.featuretask.AuditRepairCycleRepository
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AuditRepairCycle
import skillbill.workflow.taskruntime.model.AuditRepairStage
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairStatus
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
  val auditRepairCycles: AuditRepairCycleRepository? = null,
) {
  val currentPhaseExecutionDeriver = FeatureTaskRuntimeCurrentPhaseExecutionDeriver()

  fun status(request: FeatureTaskRuntimeStatusRequest): FeatureTaskRuntimeStatusProjection? {
    val snapshot = auditRepairCycles?.statusSnapshot(request.workflowId)?.let(::FeatureTaskRuntimeStatusArtifacts)
    if (snapshot != null) {
      return buildStatusProjection(request, snapshot.records, snapshot.decomposeTerminal, snapshot.ledger, snapshot)
    }
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
  snapshot: FeatureTaskRuntimeStatusArtifacts? = null,
): FeatureTaskRuntimeStatusProjection {
  val auditRepairProgress = auditProgressFrom(records, ledger)
  val durableBlockedPhaseIds =
    records.filterValues { it.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED }.keys
  val blockedPhaseIds = durableBlockedPhaseIds + ledgerBlockedPhaseIds(ledger, durableBlockedPhaseIds)
  val phases = phaseStatuses(records, blockedPhaseIds, ledger)
  val terminalDecomposeRecorded = decomposeTerminal != null
  val qualityGateSelection = (
    if (snapshot == null) {
      recorder.loadGoalContinuationQualityGateSelection(request.workflowId)
    } else {
      snapshot.qualityGateSelection
    }
    ).orLegacyValidate()
  val currentPhaseId = resolveCurrentPhaseId(
    terminalDecomposeRecorded,
    records,
    phases,
    ledger,
    qualityGateSelection,
  )
  val auditGapPause = if (snapshot == null) recorder.loadAuditGapPause(request.workflowId) else snapshot.pause
  val auditCycle = if (snapshot != null) {
    snapshot.cycle
  } else {
    records[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT]
      ?.let { auditRepairCycles?.findForAttempt(request.workflowId, it.attemptCount) }
      ?: auditRepairCycles?.findActive(request.workflowId)
  }
  val effectiveAuditGapIteration = auditCycle?.repairRoundCount
    ?: auditGapPause?.edgeIteration
    ?: auditRepairProgress?.auditGapIterationCount
    ?: ledgerAuditGapIterationCount(ledger)
  val auditRepair = auditRepairStatus(
    auditRepairProgress?.copy(auditGapIterationCount = effectiveAuditGapIteration),
    auditCycle,
  )
  val gateRunCount = gateRunCountFor(request, currentPhaseId, snapshot)
  return statusProjectionFrom(
    StatusProjectionParts(
      request = request,
      snapshot = snapshot,
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
  val snapshot: FeatureTaskRuntimeStatusArtifacts?,
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
    featureSize = if (parts.snapshot == null) {
      runInvariantsStore.resolve(request.workflowId)?.featureSize?.name
    } else {
      parts.snapshot.featureSize
    },
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
    resolvedBranch = if (parts.snapshot == null) {
      recorder.loadResolvedBranch(request.workflowId)?.branch
    } else {
      parts.snapshot.branch
    },
    finalizingAgentId = agentAttributionFromPhaseState(
      parts.ledger,
      parts.records,
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
    degradedDiagnostic = degradedDiagnosticStatus(request.workflowId, parts.snapshot),
    operatorDecisionPause = operatorDecisionPause(parts.records, parts.auditGapPause),
  )
}

private fun FeatureTaskRuntimeStatusService.gateRunCountFor(
  request: FeatureTaskRuntimeStatusRequest,
  currentPhaseId: String?,
  snapshot: FeatureTaskRuntimeStatusArtifacts?,
): Int? {
  val validationGateRunCount = if (snapshot == null) {
    recorder.loadValidationGateProgress(request.workflowId)?.gateRunCount
  } else {
    snapshot.validationGate?.gateRunCount
  }
  val buildGateRunCount = if (snapshot == null) {
    recorder.loadBuildGateProgress(request.workflowId)?.gateRunCount
  } else {
    snapshot.buildGate?.gateRunCount
  }
  return when (currentPhaseId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD -> buildGateRunCount
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE -> validationGateRunCount
    else -> validationGateRunCount ?: buildGateRunCount
  }
}

fun FeatureTaskRuntimeStatusService.degradedDiagnosticStatus(
  workflowId: String,
  snapshot: FeatureTaskRuntimeStatusArtifacts? = null,
): FeatureTaskRuntimeDegradedDiagnosticStatus? {
  val diagnosticSignals = if (snapshot == null) {
    recorder.loadDiagnosticSignals(
      workflowId,
    )
  } else {
    snapshot.diagnosticSignals
  }
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
  cycle: AuditRepairCycle? = null,
): FeatureTaskRuntimeAuditRepairStatus? = progress?.let {
  FeatureTaskRuntimeAuditRepairStatus(
    firstPassConvergence = cycle?.let {
      it.current.stage == AuditRepairStage.SATISFIED && it.repairRoundCount == 0
    } ?: it.firstPassConvergence,
    auditGapIterationCount = cycle?.repairRoundCount ?: it.auditGapIterationCount,
    stage = cycle?.current?.stage?.wireValue,
    unresolvedCriterionRefs = cycle?.latestAssessment?.unmetCriterionRefs?.toList().orEmpty(),
    repairRoundCount = cycle?.repairRoundCount ?: 0,
    lastCheckpointId = cycle?.latestCheckpoint?.checkpointId,
    executionId = cycle?.identity?.executionId,
    sessionId = cycle?.identity?.sessionId,
    operatorReason = cycle?.current?.reason,
  )
} ?: cycle?.let {
  FeatureTaskRuntimeAuditRepairStatus(
    firstPassConvergence = it.current.stage == AuditRepairStage.SATISFIED && it.repairRoundCount == 0,
    auditGapIterationCount = it.repairRoundCount,
    stage = it.current.stage.wireValue,
    unresolvedCriterionRefs = it.latestAssessment?.unmetCriterionRefs?.toList().orEmpty(),
    repairRoundCount = it.repairRoundCount,
    lastCheckpointId = it.latestCheckpoint?.checkpointId,
    executionId = it.identity.executionId,
    sessionId = it.identity.sessionId,
    operatorReason = it.current.reason,
  )
}

fun FeatureTaskRuntimeStatusService.ledgerAuditGapIterationCount(
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): Int = FeatureTaskRuntimeAuditConvergence.auditGapIterationCount(ledger)
