package skillbill.db.telemetry

import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.telemetry.model.FeatureImplementFinishedRecord
import skillbill.telemetry.model.FeatureImplementStartedRecord
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
import java.sql.Connection

// SKILL-66 Subtask 2: implements the full lifecycle telemetry contract
// (implement/verify/quality/pr plus the goal family); the override surface
// mirrors the interface and legitimately exceeds the per-type function budget.
@Suppress("TooManyFunctions")
class LifecycleTelemetryStore(
  private val connection: Connection,
) : LifecycleTelemetryRepository {
  override fun featureImplementStarted(record: FeatureImplementStartedRecord, level: String) {
    saveFeatureImplementStarted(connection, record)
    emitFeatureImplementStarted(connection, record.sessionId, level)
  }

  override fun featureImplementFinished(record: FeatureImplementFinishedRecord, level: String) {
    if (saveFeatureImplementFinished(connection, record) == TerminalSaveOutcome.FIRST_TERMINAL) {
      emitFeatureImplementFinished(connection, record.sessionId, level)
    }
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
    if (outcome == GoalStartedSaveOutcome.INSERTED) record.parentWorkflowId
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
    emitGoalFinished(connection, record.workflowId)
  }

  override fun goalIssueFinished(record: GoalIssueFinishedRecord, level: String) {
    if (saveGoalIssueFinished(connection, record).persisted) {
      emitGoalIssueFinished(connection, record.parentWorkflowId, record.issueKey)
    }
  }
}
