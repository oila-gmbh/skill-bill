package skillbill.infrastructure.sqlite.workflow

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.AuditRepairCycleKeys
import skillbill.contracts.workflow.AuditRepairCycleStatusKeys
import skillbill.contracts.workflow.AuditRepairEventKeys
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CYCLE_CONTRACT_VERSION
import skillbill.infrastructure.sqlite.telemetry.TelemetryOutboxStore
import skillbill.workflow.taskruntime.model.AuditRepairCycle
import skillbill.workflow.taskruntime.model.AuditRepairStage
import java.sql.Connection

internal fun Connection.enqueueAuditRepairTransition(cycle: AuditRepairCycle) {
  val previous = cycle.revisions.dropLast(1).lastOrNull()?.stage
  val payload = mapOf(
    AuditRepairCycleKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CYCLE_CONTRACT_VERSION,
    AuditRepairCycleKeys.WORKFLOW_ID to cycle.identity.workflowId,
    AuditRepairCycleKeys.CYCLE_ID to cycle.identity.cycleId,
    AuditRepairCycleKeys.EXECUTION_ID to cycle.identity.executionId,
    AuditRepairCycleKeys.AUDIT_ATTEMPT to cycle.identity.auditAttempt,
    AuditRepairCycleKeys.REVISION to cycle.current.revision,
    AuditRepairCycleKeys.STAGE to cycle.current.stage.wireValue,
    AuditRepairCycleKeys.RECORDED_AT to cycle.current.recordedAt,
    AuditRepairEventKeys.PREVIOUS_STAGE to previous?.wireValue,
    AuditRepairEventKeys.RECOVERY to (previous == AuditRepairStage.PAUSED),
    AuditRepairEventKeys.UNRESOLVED_COUNT to cycle.latestAssessment?.unmetCriterionRefs?.size,
    AuditRepairCycleStatusKeys.REPAIR_ROUND_COUNT to cycle.repairRoundCount,
  )
  TelemetryOutboxStore(this).enqueue(AuditRepairEventKeys.EVENT_NAME, JsonCodec.mapToJsonString(payload))
}
