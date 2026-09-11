package skillbill.ports.workflow.model

import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition

enum class WorkflowFamily(
  val definition: WorkflowDefinition,
  val humanName: String,
  val loopOnlyStepIds: Set<String> = emptySet(),
) {
  VERIFY(FeatureVerifyWorkflowDefinition.definition, "feature-verify"),
  TASK_RUNTIME(
    FeatureTaskRuntimePhaseWorkflowDefinition.definition,
    "feature-task-runtime",
    FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds,
  ),
  ;

  init {
    require(loopOnlyStepIds.all { it in definition.stepIds }) {
      "WorkflowFamily $humanName declares loop-only steps absent from its definition: " +
        "${loopOnlyStepIds - definition.stepIds.toSet()}"
    }
  }
}
