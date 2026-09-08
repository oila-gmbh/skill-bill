package skillbill.ports.goalrunner.persistence
import skillbill.ports.goalrunner.runner.model.GoalObservabilityProgressEvent
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEvent
import skillbill.workflow.goal.model.GoalObservabilityEvent

fun Map<*, *>.toGoalRunnerProgressEventOrNull(): GoalRunnerProgressEvent? {
  val stepId = this["step_id"]?.toString()?.takeIf(String::isNotBlank)
  val kind = this["kind"]?.toString()?.takeIf(String::isNotBlank)
  val timestamp = this["timestamp"]?.toString()?.takeIf(String::isNotBlank)
  return if (stepId != null && kind != null && timestamp != null) {
    GoalRunnerProgressEvent(
      stepId = stepId,
      attemptCount = this["attempt_count"].asGoalRunnerIntOrNull() ?: 0,
      kind = kind,
      message = this["message"]?.toString().orEmpty(),
      sequence = this["sequence"].asGoalRunnerIntOrNull() ?: 0,
      timestamp = timestamp,
    )
  } else {
    null
  }
}

fun GoalRunnerProgressEvent.summary(): String = buildString {
  append("durable_progress step=")
  append(stepId)
  append(" attempt=")
  append(attemptCount)
  append(" kind=")
  append(kind)
  append(" sequence=")
  append(sequence)
  append(" at=")
  append(timestamp)
  if (message.isNotBlank()) {
    append(" message=")
    append(message)
  }
}

fun GoalObservabilityEvent.toProgressEvent(): GoalObservabilityProgressEvent = GoalObservabilityProgressEvent(
  issueKey = issueKey,
  subtaskId = subtaskId,
  workflowPhase = workflowPhase,
  workerRole = workerRole,
  livenessClass = livenessClass,
  activitySummary = activitySummary,
  sequenceNumber = sequenceNumber,
  timestamp = timestamp,
)
