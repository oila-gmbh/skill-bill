package skillbill.workflow.engine

import skillbill.contracts.JsonCodec
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState

internal fun snapshotViewFrom(record: WorkflowStateSnapshot): WorkflowSnapshotView {
  val steps = decodeSteps(record.stepsJson).map { stepMap ->
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
    workflowId = record.workflowId,
    sessionId = record.sessionId,
    workflowName = record.workflowName,
    mode = record.mode,
    contractVersion = record.contractVersion,
    workflowStatus = record.workflowStatus,
    currentStepId = record.currentStepId.orEmpty(),
    steps = steps,
    artifacts = decodeObject(record.artifactsJson),
    startedAt = record.startedAt.orEmpty(),
    updatedAt = record.updatedAt.orEmpty(),
    finishedAt = record.finishedAt.orEmpty(),
  )
}

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

internal fun jsonString(value: Any?): String = JsonCodec.valueToJsonString(value)
