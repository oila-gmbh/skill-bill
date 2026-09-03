package skillbill.workflow.taskruntime

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics

interface FeatureTaskRuntimePlanningProjectionValidator {
  @OpenBoundaryMap("Feature-task-runtime planning projection wire map at the schema-validation seam")
  fun validatePlanningProjection(producedOutputs: Map<String, Any?>, sourceLabel: String)
}

object NoopFeatureTaskRuntimePlanningProjectionValidator : FeatureTaskRuntimePlanningProjectionValidator {
  override fun validatePlanningProjection(producedOutputs: Map<String, Any?>, sourceLabel: String) {
    RecordingNullObjectDiagnostics.recordSwallow(
      "NoopFeatureTaskRuntimePlanningProjectionValidator",
      "validatePlanningProjection(sourceLabel=$sourceLabel)",
    )
  }
}
