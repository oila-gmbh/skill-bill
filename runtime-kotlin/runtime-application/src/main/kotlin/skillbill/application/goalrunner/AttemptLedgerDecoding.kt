package skillbill.application.goalrunner

import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidGoalProgressEventSchemaError
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEvent
import skillbill.workflow.goal.model.GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalProgressEvent
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome

@OpenBoundaryMap("Legacy progress_event artifact decode for goal-runner progress recording")
fun progressEventFrom(artifacts: Map<String, Any?>): GoalRunnerProgressEvent? =
  (artifacts["progress_event"] as? Map<*, *>)
    ?.toGoalRunnerProgressEventOrNull()

@OpenBoundaryMap("Declared goal progress latest-event artifact decode")
fun declaredProgressEventFrom(artifacts: Map<String, Any?>): GoalProgressEvent? =
  when (val raw = artifacts[GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY]) {
    null -> null
    is Map<*, *> -> raw.decodeDeclaredGoalProgressEvent(GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY)
    else -> throw InvalidGoalProgressEventSchemaError(
      GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY,
      "<root>",
      "must be an object.",
    )
  }

fun Map<*, *>.decodeDeclaredGoalProgressEvent(sourceLabel: String): GoalProgressEvent {
  val eventKind = requiredProgressEventKind(sourceLabel)
  val workflowId = requiredNonBlankField(sourceLabel, "workflow_id")
  val workflowPhase = requiredNonBlankField(sourceLabel, "workflow_phase")
  val timestamp = requiredNonBlankField(sourceLabel, "timestamp")
  val sequenceNumber = this["sequence_number"].asDeclaredGoalProgressInt(sourceLabel, "sequence_number")
  val outcome = optionalProgressOutcome(sourceLabel)
  return buildDeclaredGoalProgressEvent(
    BuildDeclaredGoalProgressEventArgs(
      sourceLabel = sourceLabel,
      eventKind = eventKind,
      workflowId = workflowId,
      workflowPhase = workflowPhase,
      sequenceNumber = sequenceNumber,
      timestamp = timestamp,
      outcome = outcome,
    ),
  )
}

private fun Map<*, *>.requiredNonBlankField(sourceLabel: String, field: String): String =
  this[field]?.toString()?.takeIf(String::isNotBlank)
    ?: throw InvalidGoalProgressEventSchemaError(sourceLabel, field, "is required.")

private fun Map<*, *>.requiredProgressEventKind(sourceLabel: String): GoalProgressEventKind {
  val wire = requiredNonBlankField(sourceLabel, "event_kind")
  return GoalProgressEventKind.entries.firstOrNull { it.wireValue == wire }
    ?: throw InvalidGoalProgressEventSchemaError(sourceLabel, "event_kind", "unrecognized value '$wire'.")
}

private fun Map<*, *>.optionalProgressOutcome(sourceLabel: String): GoalProgressOutcome {
  val outcomeWire = this["outcome"]?.toString()?.takeIf(String::isNotBlank) ?: return GoalProgressOutcome.NONE
  return GoalProgressOutcome.entries.firstOrNull { it.wireValue == outcomeWire }
    ?: throw InvalidGoalProgressEventSchemaError(sourceLabel, "outcome", "unrecognized value '$outcomeWire'.")
}

private fun Map<*, *>.buildDeclaredGoalProgressEvent(args: BuildDeclaredGoalProgressEventArgs): GoalProgressEvent =
  try {
    GoalProgressEvent(
      eventKind = args.eventKind,
      workflowId = args.workflowId,
      workflowPhase = args.workflowPhase,
      processAlive = this["process_alive"] == true,
      sequenceNumber = args.sequenceNumber,
      timestamp = args.timestamp,
      stepId = this["step_id"]?.toString()?.takeIf(String::isNotBlank),
      operationName = this["operation_name"]?.toString()?.takeIf(String::isNotBlank),
      operationKind = this["operation_kind"]?.toString()?.takeIf(String::isNotBlank),
      expectedLong = this["expected_long"] == true,
      outcome = args.outcome,
    )
  } catch (error: IllegalArgumentException) {
    throw InvalidGoalProgressEventSchemaError(args.sourceLabel, "<root>", error.message ?: "invalid event.", error)
  }

fun Any?.asDeclaredGoalProgressInt(sourceLabel: String, fieldPath: String): Int {
  val value = parseDeclaredGoalProgressInt(this, sourceLabel, fieldPath)
  requireNonNegativeDeclaredGoalProgressInt(value, sourceLabel, fieldPath)
  return value
}

private fun parseDeclaredGoalProgressInt(raw: Any?, sourceLabel: String, fieldPath: String): Int = when (raw) {
  is Int -> raw
  is Number -> raw.toInt()
  is String -> raw.toIntOrNull()
    ?: throw InvalidGoalProgressEventSchemaError(sourceLabel, fieldPath, "must be an integer.")
  else -> throw InvalidGoalProgressEventSchemaError(sourceLabel, fieldPath, "must be an integer.")
}

private fun requireNonNegativeDeclaredGoalProgressInt(value: Int, sourceLabel: String, fieldPath: String) {
  if (value < 0) {
    throw InvalidGoalProgressEventSchemaError(sourceLabel, fieldPath, "must be non-negative.")
  }
}
