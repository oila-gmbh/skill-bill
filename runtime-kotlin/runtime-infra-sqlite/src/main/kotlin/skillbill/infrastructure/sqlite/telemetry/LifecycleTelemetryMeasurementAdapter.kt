package skillbill.infrastructure.sqlite.telemetry

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.ports.telemetry.FeatureTaskRuntimeTelemetryMeasurementRepository
import skillbill.ports.telemetry.ReviewStageTelemetryMeasurementRepository
import skillbill.review.model.REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION
import skillbill.review.model.REVIEW_STAGE_DEGRADATION_EVENT_NAME
import skillbill.review.model.ReviewStageDegradationMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticDegradationMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRejectionMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement
import java.sql.Connection

internal class LifecycleTelemetryMeasurementAdapter(
  private val connection: Connection,
) : FeatureTaskRuntimeTelemetryMeasurementRepository,
  ReviewStageTelemetryMeasurementRepository {
  override fun featureTaskRuntimeProjectionMeasurement(record: FeatureTaskRuntimeProjectionMeasurement) {
    enqueueTelemetry(connection, "skillbill_feature_task_runtime_projection_measurement", record.toTelemetryMap())
  }

  override fun featureTaskRuntimeSharedEvidence(record: FeatureTaskRuntimeSharedEvidenceMeasurement) {
    enqueueTelemetry(connection, "skillbill_feature_task_runtime_shared_evidence", record.toTelemetryMap())
  }

  override fun featureTaskRuntimeRejection(record: FeatureTaskRuntimeRejectionMeasurement) {
    enqueueTelemetry(connection, "skillbill_feature_task_runtime_rejection", record.toTelemetryMap())
  }

  override fun featureTaskRuntimeDiagnosticDegradation(record: FeatureTaskRuntimeDiagnosticDegradationMeasurement) {
    enqueueTelemetry(
      connection,
      "skillbill_feature_task_runtime_diagnostic_degradation",
      record.toTelemetryMap(),
    )
  }

  override fun reviewStageDegradation(record: ReviewStageDegradationMeasurement) {
    if (reviewStageDegradationExists(connection, record)) return
    enqueueTelemetry(connection, REVIEW_STAGE_DEGRADATION_EVENT_NAME, record.toStageDegradationPayload())
  }
}

private fun ReviewStageDegradationMeasurement.toStageDegradationPayload(): Map<String, Any?> = linkedMapOf(
  "event_name" to REVIEW_STAGE_DEGRADATION_EVENT_NAME,
  SharedPayloadKeys.CONTRACT_VERSION to REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION,
  ReviewVerificationSignalKeys.REVIEW_RUN_ID to reviewRunId,
  "seam" to seam,
  "expected" to expected,
  "actual" to actual,
  "reason" to reason.wireValue,
)
