package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

private val REMOVED_FEATURE_TASK_RUNTIME_PHASE_IDS: Set<String> = setOf("plan_fix")

private val KNOWN_FEATURE_TASK_RUNTIME_PHASE_IDS: Set<String> =
  FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.toSet()

internal fun requireKnownFeatureTaskRuntimePhaseId(phaseId: String, fieldPath: String): String {
  if (phaseId in REMOVED_FEATURE_TASK_RUNTIME_PHASE_IDS) {
    throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$fieldPath' names removed phase '$phaseId'.",
    )
  }
  if (phaseId !in KNOWN_FEATURE_TASK_RUNTIME_PHASE_IDS) {
    throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$fieldPath' has unknown phase '$phaseId'.",
    )
  }
  return phaseId
}
