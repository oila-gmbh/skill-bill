package skillbill.workflow.taskruntime

import skillbill.boundary.OpenBoundaryMap

interface FeatureTaskRuntimeQuarantineValidator {
  @OpenBoundaryMap("Feature-task-runtime quarantine record wire map at the schema-validation seam")
  fun validateQuarantineRecord(quarantineRecord: Map<String, Any?>, sourceLabel: String)
}
