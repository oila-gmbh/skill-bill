package skillbill.engine.featuretask.model

import skillbill.application.telemetry.model.FeatureTaskRuntimeFindingVerificationTelemetry
import skillbill.application.telemetry.model.FeatureTaskRuntimeRegenerationTelemetry
import skillbill.workflow.taskruntime.model.AuditRepairCycle
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditProgress

data class FeatureTaskRuntimeFinishedTelemetryContext(
  val telemetrySessionId: String,
  val phaseOutcomes: () -> Map<String, String>,
  val reviewFixIterationCount: () -> Int,
  val auditGapIterationCount: () -> Int,
  val auditRepairProgress: () -> FeatureTaskRuntimeAuditProgress? = { null },
  val auditRepairCycle: () -> AuditRepairCycle? = { null },
  val findingVerificationTelemetry: () -> FeatureTaskRuntimeFindingVerificationTelemetry = {
    FeatureTaskRuntimeFindingVerificationTelemetry()
  },
  val regenerationTelemetry: () -> FeatureTaskRuntimeRegenerationTelemetry = {
    FeatureTaskRuntimeRegenerationTelemetry()
  },
  val phaseTokenData: () -> Pair<String?, Int?> = { null to null },
  val crashReconciliation: () -> FeatureTaskRuntimeCrashReconciliationResult = {
    FeatureTaskRuntimeCrashReconciliationResult.NONE
  },
)
