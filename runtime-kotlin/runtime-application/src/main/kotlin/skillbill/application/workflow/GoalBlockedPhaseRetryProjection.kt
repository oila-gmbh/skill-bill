package skillbill.application.workflow

import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.encodeDecompositionManifestMap
import skillbill.application.decomposition.withRetriedSubtask
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact

internal fun WorkflowEngine.updateGoalParentForBlockedPhaseRetry(
  unitOfWork: UnitOfWork,
  childWorkflowId: String,
  childArtifacts: Map<String, Any?>,
  phaseId: String,
  validator: DecompositionManifestValidator,
): String? {
  val rawContinuation = childArtifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY]
    ?: return null
  val continuationMap = JsonSupport.anyToStringAnyMap(rawContinuation)
    ?: invalidGoalRetryProjection(
      "Workflow artifact '$FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY' must be an object.",
    )
  val continuation = FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(continuationMap)
  val parentWorkflowId = continuation.parentWorkflowId ?: return null
  val parent = WorkflowFamily.IMPLEMENT.get(unitOfWork.workflowStates, parentWorkflowId)
    ?: invalidGoalRetryProjection(
      "Goal child '$childWorkflowId' references unknown parent workflow '$parentWorkflowId'.",
    )
  val parentManifest = parent.decompositionRuntime(validator)
    ?: invalidGoalRetryProjection(
      "Goal parent '$parentWorkflowId' has no decomposition runtime artifact.",
    )
  if (parentManifest.issueKey != continuation.issueKey) {
    invalidGoalRetryProjection(
      "Goal child '$childWorkflowId' issue '${continuation.issueKey}' does not match parent " +
        "issue '${parentManifest.issueKey}'.",
    )
  }
  val retriedManifest = parentManifest.withRetriedSubtask(
    subtaskId = continuation.subtaskId,
    workflowId = childWorkflowId,
    lastResumableStep = phaseId,
  )
  val parentInput = WorkflowUpdateInput(
    workflowStatus = parent.workflowStatus,
    currentStepId = parent.currentStepId.orEmpty(),
    stepUpdates = null,
    artifactsPatch = mapOf(
      DECOMPOSITION_RUNTIME_ARTIFACT_KEY to encodeDecompositionManifestMap(
        retriedManifest,
        validator,
        DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
      ),
    ),
    sessionId = parent.sessionId.orEmpty(),
  )
  val updatedParent = updateRecord(WorkflowFamily.IMPLEMENT.definition, parent, parentInput)
  WorkflowFamily.IMPLEMENT.save(unitOfWork.workflowStates, updatedParent)
  return updatedParent.artifactsJson
}

private fun invalidGoalRetryProjection(reason: String): Nothing = throw InvalidWorkflowStateSchemaError(reason)
