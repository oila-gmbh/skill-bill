package skillbill.application.featuretask

import skillbill.application.workflow.WorkflowFamily
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput

internal class FeatureTaskRuntimeGoalContinuationArtifactPatcher(
  private val engine: WorkflowEngine,
) {
  fun save(
    record: WorkflowStateSnapshot,
    workflowStates: WorkflowStateRepository,
    patch: Map<String, Any?>,
  ) {
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = record.currentStepId,
        stepUpdates = null,
        artifactsPatch = patch,
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.TASK_RUNTIME.save(workflowStates, updated)
  }
}
