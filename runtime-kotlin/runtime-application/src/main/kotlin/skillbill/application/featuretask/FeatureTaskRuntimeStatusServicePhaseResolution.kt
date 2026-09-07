package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeOperatorDecisionPause
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStatus
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection

const val PHASE_STATUS_PENDING = "pending"
const val PHASE_STATUS_COMPLETED = "completed"
const val PHASE_STATUS_BLOCKED = "blocked"
val PHASE_TERMINAL_STATUSES = setOf(PHASE_STATUS_COMPLETED, PHASE_STATUS_BLOCKED)
val CONTINUATION_KIND_ACTIONS = setOf(
  FeatureTaskRuntimePhaseLedgerAction.START,
  FeatureTaskRuntimePhaseLedgerAction.RESUME,
  FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
  FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION,
)
val LOOP_ONLY_PHASE_IDS: Set<String> = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds
val OPERATOR_DECISION_QUALITY_GATE_PHASE_IDS: Set<String> = setOf(
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
)

fun phaseStatuses(
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

fun resolveCurrentPhaseId(
  terminalDecomposeRecorded: Boolean,
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  phases: List<FeatureTaskRuntimePhaseStatus>,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  qualityGateSelection: FeatureTaskRuntimeQualityGateSelection,
): String? {
  if (terminalDecomposeRecorded) return null
  return currentReentryPhaseId(records, ledger) ?: phases.firstOrNull {
    it.status != PHASE_STATUS_COMPLETED &&
      !shouldSkipPendingLoopOnlyPhase(it.phaseId, it.status, records, qualityGateSelection)
  }?.phaseId
}

fun shouldSkipPendingLoopOnlyPhase(
  phaseId: String,
  status: String,
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  qualityGateSelection: FeatureTaskRuntimeQualityGateSelection,
): Boolean {
  if (status != PHASE_STATUS_PENDING || phaseId !in LOOP_ONLY_PHASE_IDS) {
    return false
  }
  val buildStampedCurrent =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD &&
      qualityGateSelection == FeatureTaskRuntimeQualityGateSelection.BUILD &&
      records[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW]?.status == PHASE_STATUS_COMPLETED &&
      records[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD]?.status != PHASE_STATUS_COMPLETED
  if (buildStampedCurrent) {
    return false
  }
  return true
}

fun currentReentryPhaseId(
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
  val reopenedSpan = when {
    destinationIndex <= sourceIndex ->
      FeatureTaskRuntimePhaseWorkflowDefinition.transitions.forwardPhaseIds
        .subList(destinationIndex, sourceIndex + 1)
    else ->
      listOf(FeatureTaskRuntimePhaseWorkflowDefinition.transitions.forwardPhaseIds[destinationIndex])
  }
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

fun operatorDecisionPause(
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  auditGapPause: FeatureTaskRuntimeAuditGapPause?,
): FeatureTaskRuntimeOperatorDecisionPause? {
  auditGapPause?.takeIf { !it.grantConsumed }?.let { pause ->
    return FeatureTaskRuntimeOperatorDecisionPause(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      reason = pause.reason,
    )
  }
  return records.values
    .firstOrNull { record ->
      record.failureDisposition == FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION &&
        when (record.status) {
          FEATURE_TASK_RUNTIME_PHASE_STATUS_PAUSED -> true
          FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED ->
            record.phaseId in OPERATOR_DECISION_QUALITY_GATE_PHASE_IDS &&
              !record.blockedReason.isNullOrBlank()
          else -> false
        }
    }
    ?.let { record ->
      FeatureTaskRuntimeOperatorDecisionPause(
        phaseId = record.phaseId,
        reason = record.blockedReason?.takeIf(String::isNotBlank),
      )
    }
}

fun latestContinuationKind(ledger: List<FeatureTaskRuntimePhaseLedgerEntry>, phaseId: String): String? = ledger
  .filter { it.phaseId == phaseId && it.action in CONTINUATION_KIND_ACTIONS }
  .sortedByDescending { it.sequenceNumber }
  .firstNotNullOfOrNull { FeatureTaskRuntimeContinuationKind.fromLedgerDetail(it.blockedReason) }
  ?.wireValue

fun FeatureTaskRuntimePhaseRecord?.toPhaseStatus(
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
