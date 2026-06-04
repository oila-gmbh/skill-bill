package skillbill.application

import skillbill.application.model.GoalFinishedRequest
import skillbill.application.model.GoalStartedRequest
import skillbill.application.model.GoalSubtaskFinishedRequest
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord

// SKILL-66 Subtask 2: goal telemetry request -> record mappers follow the same
// identity-style `toRecord()` pattern as the lifecycle mappers; kept in their
// own file so neither this nor LifecycleTelemetryRecordMappers grows past the
// per-file function budget.

fun GoalStartedRequest.toRecord(): GoalStartedRecord = GoalStartedRecord(
  issueKey = issueKey,
  featureName = featureName,
  workflowId = workflowId,
  subtaskTotal = subtaskTotal,
  resumed = resumed,
  startedAt = startedAt,
)

fun GoalSubtaskFinishedRequest.toRecord(): GoalSubtaskFinishedRecord = GoalSubtaskFinishedRecord(
  issueKey = issueKey,
  workflowId = workflowId,
  subtaskId = subtaskId,
  subtaskName = subtaskName,
  status = status,
  startedAt = startedAt,
  finishedAt = finishedAt,
  durationMs = durationMs,
  attemptCount = attemptCount,
  blockedReason = blockedReason,
)

fun GoalFinishedRequest.toRecord(): GoalFinishedRecord = GoalFinishedRecord(
  issueKey = issueKey,
  workflowId = workflowId,
  status = status,
  startedAt = startedAt,
  finishedAt = finishedAt,
  durationMs = durationMs,
  subtasksComplete = subtasksComplete,
  subtasksBlocked = subtasksBlocked,
  subtasksSkipped = subtasksSkipped,
)
