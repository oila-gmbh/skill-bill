package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.WorkflowFamily
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome

@Inject
class FeatureTaskRuntimeGoalContinuationRecorder(
  private val database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
) {
  private val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)

  fun recordGoalContinuationState(
    workflowId: String,
    continuation: FeatureTaskRuntimeGoalContinuationArtifact? = null,
    outcome: FeatureTaskRuntimeGoalContinuationOutcome? = null,
    workflowStatus: String? = null,
    dbOverride: String? = null,
  ): Boolean = database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
      ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    val continuationPatch = if (
      continuation != null &&
      artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY] == null
    ) {
      mapOf(
        FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to continuation.toArtifactMap(),
        "install_sync_result" to mapOf(
          "status" to "skipped",
          "reason" to "goal-continuation forbids installer, uninstall, and install-sync flows",
        ),
      )
    } else {
      emptyMap()
    }
    val outcomePatch = outcome?.let {
      mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY to it.toArtifactMap())
    }.orEmpty()
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = workflowStatus ?: record.workflowStatus,
        currentStepId = outcome?.lastResumableStep ?: record.currentStepId,
        stepUpdates = null,
        artifactsPatch = continuationPatch + outcomePatch,
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.TASK_RUNTIME.save(unitOfWork.workflowStates, updated)
    true
  }
}
