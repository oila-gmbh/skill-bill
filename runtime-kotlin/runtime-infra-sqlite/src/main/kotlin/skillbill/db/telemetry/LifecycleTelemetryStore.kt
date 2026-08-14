package skillbill.db.telemetry

import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.review.model.REVIEW_STAGE_DEGRADATION_EVENT_NAME
import skillbill.review.model.ReviewStageDegradationMeasurement
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import skillbill.telemetry.model.PrDescriptionGeneratedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticDegradationMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRejectionMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement
import java.sql.Connection

// SKILL-66 Subtask 2: implements the full lifecycle telemetry contract
// (implement/verify/quality/pr plus the goal family); the override surface
// mirrors the interface and legitimately exceeds the per-type function budget.
@Suppress("TooManyFunctions")
class LifecycleTelemetryStore(
  private val connection: Connection,
) : LifecycleTelemetryRepository {
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
    enqueueTelemetry(connection, REVIEW_STAGE_DEGRADATION_EVENT_NAME, record.toTelemetryMap())
  }

  override fun featureTaskRuntimeStarted(record: FeatureTaskRuntimeStartedRecord, level: String) {
    saveFeatureTaskRuntimeStarted(connection, record)
    emitFeatureTaskRuntimeStarted(connection, record.sessionId, level)
  }

  override fun featureTaskRuntimeFinished(record: FeatureTaskRuntimeFinishedRecord, level: String) {
    if (saveFeatureTaskRuntimeFinished(connection, record) == TerminalSaveOutcome.FIRST_TERMINAL) {
      emitFeatureTaskRuntimeFinished(connection, record.sessionId, level)
    }
  }

  override fun qualityCheckStarted(record: QualityCheckStartedRecord, level: String) {
    saveQualityCheckStarted(connection, record)
    emitQualityCheckStarted(connection, record.sessionId)
  }

  override fun qualityCheckFinished(record: QualityCheckFinishedRecord, level: String) {
    if (saveQualityCheckFinished(connection, record) == TerminalSaveOutcome.FIRST_TERMINAL) {
      emitQualityCheckFinished(connection, record.sessionId, level)
    }
  }

  override fun featureVerifyStarted(record: FeatureVerifyStartedRecord, level: String) {
    saveFeatureVerifyStarted(connection, record)
    emitFeatureVerifyStarted(connection, record.sessionId, level)
  }

  override fun featureVerifyFinished(record: FeatureVerifyFinishedRecord, level: String) {
    if (saveFeatureVerifyFinished(connection, record) == TerminalSaveOutcome.FIRST_TERMINAL) {
      emitFeatureVerifyFinished(connection, record.sessionId, level)
    }
  }

  override fun prDescriptionGenerated(record: PrDescriptionGeneratedRecord, level: String) {
    enqueueTelemetry(connection, "skillbill_pr_description_generated", prDescriptionPayload(record, level))
  }

  override fun goalStarted(record: GoalStartedRecord, level: String) {
    val outcome = saveGoalStarted(connection, record)
    if (outcome == GoalStartedSaveOutcome.INSERTED) {
      record.parentWorkflowId
        ?.takeIf(String::isNotBlank)?.let { parentWorkflowId ->
          recordGoalIssueSegmentStarted(
            connection = connection,
            segment = GoalIssueSegmentStart(
              parentWorkflowId = parentWorkflowId,
              issueKey = record.issueKey,
              workflowId = record.workflowId,
              startedAt = record.startedAt,
              resumed = record.resumed,
              mode = record.mode,
            ),
          )
        }
    }
    emitGoalStarted(connection, record.workflowId, level)
  }

  override fun goalSubtaskFinished(record: GoalSubtaskFinishedRecord, level: String) {
    saveGoalSubtaskFinished(connection, record)
    emitGoalSubtaskFinished(connection, record, level)
  }

  override fun goalFinished(record: GoalFinishedRecord, level: String) {
    val outcome = saveGoalFinished(connection, record)
    if (outcome == GoalFinishedSaveOutcome.FIRST_TERMINAL && record.status != "completed") {
      record.parentWorkflowId?.takeIf(String::isNotBlank)?.let { parentWorkflowId ->
        recordGoalIssueBlockedSegment(connection, parentWorkflowId, record.issueKey, record.workflowId)
      }
    }
    emitGoalFinished(connection, record.workflowId, level)
  }

  override fun goalIssueFinished(record: GoalIssueFinishedRecord, level: String) {
    if (saveGoalIssueFinished(connection, record).persisted) {
      emitGoalIssueFinished(connection, record.parentWorkflowId, record.issueKey, level)
    }
  }
}
