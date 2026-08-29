package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.WorkflowFamily
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact

internal class FeatureTaskRuntimeGoalContinuationStateRecorder(
  private val database: DatabaseSessionFactory,
  private val engine: WorkflowEngine,
  private val patcher: FeatureTaskRuntimeGoalContinuationArtifactPatcher,
) {
  fun recordGoalContinuationState(
    request: GoalContinuationStateRecordRequest,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, request.workflowId)
      ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    val existingContinuation = continuationFromArtifacts(artifacts)
    val supplied = request.continuation?.let { continuation ->
      continuation.copy(subtaskName = continuation.subtaskName ?: existingContinuation?.subtaskName)
    }
    check(existingContinuation.compatibleWith(supplied)) {
      "Goal continuation is immutable for workflow '${request.workflowId}'; " +
        "parent, subtask, branch, and review mode cannot change on resume."
    }
    val continuationPatch = continuationPatch(supplied, existingContinuation)
    val reviewStatePatch = reviewStatePatch(request.copy(continuation = supplied), artifacts, existingContinuation)
    val outcomePatch = request.outcome?.let {
      mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY to it.toArtifactMap())
    }.orEmpty()
    val adoptionPatch = request.fieldAdoption?.let {
      mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY to it.toArtifactMap())
    }.orEmpty()
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = request.workflowStatus ?: record.workflowStatus,
        currentStepId = request.outcome?.lastResumableStep ?: record.currentStepId,
        stepUpdates = null,
        artifactsPatch = continuationPatch + reviewStatePatch + outcomePatch + adoptionPatch,
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.TASK_RUNTIME.save(unitOfWork.workflowStates, updated)
    true
  }

  fun reviewState(workflowId: String, dbOverride: String? = null): GoalSubtaskReviewState? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      reviewStateFromArtifacts(decodeArtifacts(record.artifactsJson))
    }

  fun continuation(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeGoalContinuationArtifact? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      continuationFromArtifacts(decodeArtifacts(record.artifactsJson))
    }
}
