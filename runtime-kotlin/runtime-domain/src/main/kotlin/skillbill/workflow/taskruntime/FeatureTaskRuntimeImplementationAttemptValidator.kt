package skillbill.workflow.taskruntime

import skillbill.boundary.OpenBoundaryMap

interface FeatureTaskRuntimeImplementationAttemptValidator {
  @OpenBoundaryMap("Feature-task-runtime implementation-attempt wire map at the schema-validation seam")
  fun validateImplementationAttemptRecord(attemptRecord: Map<String, Any?>, sourceLabel: String)
}
