package skillbill.workflow.taskruntime

import skillbill.boundary.OpenBoundaryMap

interface FeatureTaskRuntimePlanningProjectionValidator {
  @OpenBoundaryMap("Feature-task-runtime planning projection wire map at the schema-validation seam")
  fun validatePlanningProjection(producedOutputs: Map<String, Any?>, sourceLabel: String)
}
