package skillbill.ports.goalrunner.persistence
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import kotlin.coroutines.cancellation.CancellationException

fun WorkflowStateSnapshot.progressToken(): String = listOf(
  workflowId,
  workflowStatus,
  currentStepId,
  stepsJson,
  artifactsJson,
  updatedAt.orEmpty(),
  finishedAt.orEmpty(),
).joinToString("\n")

fun decodeWorkflowSteps(stepsJson: String): List<WorkflowStepState> =
  parseWorkflowStepsArray(stepsJson).mapIndexed(::decodeWorkflowStepAt)

private fun parseWorkflowStepsArray(stepsJson: String): List<*> =
  requireWorkflowStepsList(parseWorkflowStepsJsonElement(stepsJson))

private fun parseWorkflowStepsJsonElement(stepsJson: String): JsonElement = try {
  JsonSupport.json.parseToJsonElement(stepsJson)
} catch (error: CancellationException) {
  throw error
} catch (error: SerializationException) {
  throw InvalidWorkflowStateSchemaError("Workflow steps JSON is malformed: ${error.message}", error)
}

private fun requireWorkflowStepsList(element: JsonElement): List<*> =
  JsonSupport.jsonElementToValue(element) as? List<*>
    ?: throw InvalidWorkflowStateSchemaError("Workflow steps JSON must be an array.")

private fun decodeWorkflowStepAt(index: Int, raw: Any?): WorkflowStepState {
  val item = raw as? Map<*, *>
    ?: throw InvalidWorkflowStateSchemaError("Workflow steps[$index] must be an object.")
  return WorkflowStepState(
    stepId = item["step_id"]?.toString().orEmpty(),
    status = item["status"]?.toString().orEmpty(),
    attemptCount = item["attempt_count"].asGoalRunnerIntOrNull() ?: 0,
  )
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
