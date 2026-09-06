package skillbill.workflow.engine

import skillbill.contracts.JsonCodec
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.MalformedJsonTextError
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState

fun WorkflowStateSnapshot.progressToken(): String = listOf(
  workflowId,
  workflowStatus,
  currentStepId,
  stepsJson,
  artifactsFingerprint(artifactsJson),
  updatedAt.orEmpty(),
  finishedAt.orEmpty(),
).joinToString("\n")

fun artifactsFingerprint(artifactsJson: String): String = "${artifactsJson.length}:${artifactsJson.hashCode()}"

fun decodeWorkflowSteps(stepsJson: String): List<WorkflowStepState> =
  parseWorkflowStepsArray(stepsJson).mapIndexed(::decodeWorkflowStepAt)

private fun parseWorkflowStepsArray(stepsJson: String): List<*> = try {
  JsonCodec.parseValue(stepsJson) as? List<*>
    ?: throw InvalidWorkflowStateSchemaError("Workflow steps JSON must be an array.")
} catch (error: MalformedJsonTextError) {
  throw InvalidWorkflowStateSchemaError("Workflow steps JSON is malformed: ${error.cause?.message}", error)
}

private fun decodeWorkflowStepAt(index: Int, raw: Any?): WorkflowStepState {
  val item = raw as? Map<*, *>
    ?: throw InvalidWorkflowStateSchemaError("Workflow steps[$index] must be an object.")
  return WorkflowStepState(
    stepId = item["step_id"]?.toString().orEmpty(),
    status = item["status"]?.toString().orEmpty(),
    attemptCount = item["attempt_count"].asLenientIntOrNull() ?: 0,
  )
}

private fun Any?.asLenientIntOrNull(): Int? = when (this) {
  is Int -> this
  is Number -> toInt()
  is String -> toIntOrNull()
  else -> null
}

fun blockedStepId(
  record: WorkflowStateSnapshot,
  steps: List<WorkflowStepState>,
  requestedStepId: String,
  definitionStepIds: List<String>,
): String = requestedStepId.takeIf { stepId ->
  stepId.isNotBlank() && steps.firstOrNull { step -> step.stepId == stepId }?.status == "running"
}
  ?: steps.firstOrNull { step -> step.status == "running" }?.stepId
  ?: firstUnfinishedStepId(steps, definitionStepIds)
  ?: record.currentStepId.takeIf(String::isNotBlank)
  ?: requestedStepId.takeIf(String::isNotBlank)
  ?: "preplan"

fun firstUnfinishedStepId(steps: List<WorkflowStepState>, definitionStepIds: List<String>): String? {
  val statusByStepId = steps.associate { step -> step.stepId to step.status }
  return definitionStepIds.firstOrNull { stepId ->
    statusByStepId[stepId]?.let { status -> status != "completed" && status != "skipped" } ?: true
  }
}
