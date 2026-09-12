package skillbill.cli.model

import skillbill.contracts.workflow.AuditRepairCycleStatusKeys
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairStatus

internal fun FeatureTaskRuntimeAuditRepairStatus.toAuditRepairCliMap(): Map<String, Any?> = linkedMapOf(
  AuditRepairCycleStatusKeys.FIRST_PASS_CONVERGENCE to firstPassConvergence,
  AuditRepairCycleStatusKeys.AUDIT_GAP_ITERATION_COUNT to auditGapIterationCount,
  AuditRepairCycleStatusKeys.STAGE to stage,
  AuditRepairCycleStatusKeys.UNRESOLVED_CRITERION_REFS to unresolvedCriterionRefs,
  AuditRepairCycleStatusKeys.REPAIR_ROUND_COUNT to repairRoundCount,
  AuditRepairCycleStatusKeys.LAST_CHECKPOINT_ID to lastCheckpointId,
  AuditRepairCycleStatusKeys.EXECUTION_ID to executionId,
  AuditRepairCycleStatusKeys.SESSION_ID to sessionId,
  AuditRepairCycleStatusKeys.OPERATOR_REASON to operatorReason,
)
