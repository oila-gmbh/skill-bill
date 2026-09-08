package skillbill.workflow.engine

import kotlinx.serialization.json.JsonElement
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStepState

internal fun snapshotViewFromMap(map: Map<String, Any?>): WorkflowSnapshotView {
  val rawSteps = requiredStringAnyMapList(map["steps"], "steps")
  val artifacts = requiredStringAnyMap(map["artifacts"], "artifacts")
  val steps = rawSteps.map { stepMap ->
    WorkflowStepState(
      stepId = stepMap["step_id"] as String,
      status = stepMap["status"] as String,
      attemptCount = stepMap["attempt_count"].asExactIntOrNull()
        ?: throw InvalidWorkflowStateSchemaError(
          "Workflow state step attempt_count must decode to an integer.",
        ),
    )
  }
  return WorkflowSnapshotView(
    workflowId = map["workflow_id"] as String,
    sessionId = map["session_id"] as String,
    workflowName = map["workflow_name"] as String,
    mode = map["mode"] as? String,
    contractVersion = map["contract_version"] as String,
    workflowStatus = map["workflow_status"] as String,
    currentStepId = map["current_step_id"] as String,
    steps = steps,
    artifacts = artifacts,
    startedAt = map["started_at"] as String,
    updatedAt = map["updated_at"] as String,
    finishedAt = map["finished_at"] as String,
  )
}

internal fun workflowStepMap(step: WorkflowStepState): Map<String, Any?> = linkedMapOf(
  "step_id" to step.stepId,
  "status" to step.status,
  "attempt_count" to step.attemptCount,
)

internal fun defaultSteps(definition: WorkflowDefinition, initialStepId: String): List<Map<String, Any?>> {
  var seenInitial = false
  return definition.stepIds.map { stepId ->
    when {
      stepId == initialStepId -> {
        seenInitial = true
        workflowStep(stepId, "running", 1)
      }
      definition.openPriorStepsCompleted && !seenInitial -> workflowStep(stepId, "completed", 1)
      else -> workflowStep(stepId, "pending", 0)
    }
  }
}

internal fun mergeStepUpdates(
  definition: WorkflowDefinition,
  existingSteps: List<Map<String, Any?>>,
  stepUpdates: List<Map<String, Any?>>?,
): List<Map<String, Any?>> {
  if (stepUpdates == null) {
    return existingSteps
  }
  val byStepId = existingSteps.associateByTo(LinkedHashMap()) { it["step_id"].toString() }
  stepUpdates.forEach { update ->
    val stepId = update["step_id"].toString()
    val attemptCount = requireNotNull(update["attempt_count"].asExactIntOrNull()) {
      "step_updates.attempt_count must be an integer >= 0."
    }
    require(attemptCount >= 0) {
      "step_updates.attempt_count must be an integer >= 0."
    }
    byStepId[stepId] = workflowStep(stepId, update["status"].toString(), attemptCount)
  }
  return definition.stepIds.mapNotNull(byStepId::get)
}

internal fun workflowStep(stepId: String, status: String, attemptCount: Int): Map<String, Any?> =
  linkedMapOf("step_id" to stepId, "status" to status, "attempt_count" to attemptCount)

internal fun jsonString(value: Any?): String = JsonSupport.json.encodeToString(
  JsonElement.serializer(),
  JsonSupport.valueToJsonElement(value),
)
