package skillbill.db.telemetry

import skillbill.ports.telemetry.GoalLifecycleTelemetryRepository
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.sql.Connection

internal class LifecycleTelemetryGoalSessionAdapter(
  private val connection: Connection,
) : GoalLifecycleTelemetryRepository {
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
        recordGoalIssueSegmentEnd(
          connection,
          parentWorkflowId,
          record.issueKey,
          record.workflowId,
          record.status,
        )
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
