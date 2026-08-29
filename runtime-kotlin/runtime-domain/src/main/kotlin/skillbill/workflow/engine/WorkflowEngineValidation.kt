package skillbill.workflow.engine

import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowUpdateInput

internal fun validateWorkflowOpen(definition: WorkflowDefinition, currentStepId: String): String? =
  validateWorkflowEnum(currentStepId, definition.stepIds, "current_step_id")

internal fun validateWorkflowUpdate(definition: WorkflowDefinition, input: WorkflowUpdateInput): String? {
  validateWorkflowEnum(input.workflowStatus, definition.workflowStatuses, "workflow_status")?.let { return it }
  if (input.currentStepId.isNotBlank()) {
    validateWorkflowEnum(input.currentStepId, definition.stepIds, "current_step_id")?.let { return it }
  }
  input.stepUpdates?.let { updates ->
    val seenStepIds = mutableSetOf<String>()
    updates.forEachIndexed { index, update ->
      val stepId = update["step_id"] as? String
      if (stepId.isNullOrBlank()) {
        return "step_updates[$index].step_id must be a non-empty string."
      }
      validateWorkflowEnum(stepId, definition.stepIds, "step_updates.step_id")?.let { return it }
      if (!seenStepIds.add(stepId)) {
        return "Duplicate step_id '$stepId' in step_updates."
      }
      val status = update["status"] as? String
      if (status.isNullOrBlank()) {
        return "step_updates[$index].status must be a non-empty string."
      }
      validateWorkflowEnum(status, definition.stepStatuses, "step_updates.status")?.let { return it }
      val attemptCount = update["attempt_count"].asExactIntOrNull()
      if (attemptCount == null || attemptCount < 0) {
        return "step_updates[$index].attempt_count must be an integer >= 0."
      }
    }
  }
  return null
}

private fun validateWorkflowEnum(value: String, allowed: Collection<String>, fieldName: String): String? =
  if (value !in allowed) {
    "Invalid $fieldName '$value'. Allowed: ${allowed.joinToString()}"
  } else {
    null
  }
