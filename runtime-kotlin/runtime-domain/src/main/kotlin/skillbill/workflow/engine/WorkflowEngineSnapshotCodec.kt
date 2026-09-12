package skillbill.workflow.engine

import skillbill.contracts.SharedPayloadKeys

import skillbill.contracts.JsonCodec
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState

internal fun snapshotViewFrom(record: WorkflowStateSnapshot): WorkflowSnapshotView {
  val steps = decodeSteps(record.stepsJson).map { stepMap ->
    WorkflowStepState(
      stepId = stepMap[SharedPayloadKeys.STEP_ID] as String,
      status = stepMap[SharedPayloadKeys.STATUS] as String,
      attemptCount = stepMap["attempt_count"].asExactIntOrNull()
        ?: throw InvalidWorkflowStateSchemaError(
          "Workflow state step attempt_count must decode to an integer.",
        ),
    )
  }
  return WorkflowSnapshotView(
    workflowId = record.workflowId,
    sessionId = record.sessionId.orEmpty(),
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
  val byStepId = existingSteps.associateByTo(LinkedHashMap()) { it[SharedPayloadKeys.STEP_ID].toString() }
  stepUpdates.forEach { update ->
    val stepId = update[SharedPayloadKeys.STEP_ID].toString()
    val attemptCount = requireNotNull(update["attempt_count"].asExactIntOrNull()) {
      "step_updates.attempt_count must be an integer >= 0."
    }
    require(attemptCount >= 0) {
      "step_updates.attempt_count must be an integer >= 0."
    }
    byStepId[stepId] = workflowStep(stepId, update[SharedPayloadKeys.STATUS].toString(), attemptCount)
  }
  return definition.stepIds.mapNotNull(byStepId::get)
}

internal fun workflowStep(stepId: String, status: String, attemptCount: Int): Map<String, Any?> =
  linkedMapOf(SharedPayloadKeys.STEP_ID to stepId, SharedPayloadKeys.STATUS to status, "attempt_count" to attemptCount)

internal fun jsonString(value: Any?): String = JsonCodec.valueToJsonString(value)
