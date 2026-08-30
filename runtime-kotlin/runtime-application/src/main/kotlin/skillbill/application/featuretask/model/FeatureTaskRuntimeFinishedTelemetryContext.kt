package skillbill.application.featuretask.model

import skillbill.application.telemetry.model.FeatureTaskRuntimeFindingVerificationTelemetry
import skillbill.application.telemetry.model.FeatureTaskRuntimeRegenerationTelemetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditProgress

internal data class FeatureTaskRuntimeFinishedTelemetryContext(
  val telemetrySessionId: String,
  val phaseOutcomes: () -> Map<String, String>,
  val reviewFixIterationCount: () -> Int,
  val auditGapIterationCount: () -> Int,
  val auditRepairProgress: () -> FeatureTaskRuntimeAuditProgress? = { null },
  val findingVerificationTelemetry: () -> FeatureTaskRuntimeFindingVerificationTelemetry = {
    FeatureTaskRuntimeFindingVerificationTelemetry()
  },
  val regenerationTelemetry: () -> FeatureTaskRuntimeRegenerationTelemetry = {
    FeatureTaskRuntimeRegenerationTelemetry()
  },
  val dbOverride: String?,
  val phaseTokenData: () -> Pair<String?, Int?> = { null to null },
  val crashReconciliation: () -> FeatureTaskRuntimeCrashReconciliationResult = {
    FeatureTaskRuntimeCrashReconciliationResult.NONE
  },
)
