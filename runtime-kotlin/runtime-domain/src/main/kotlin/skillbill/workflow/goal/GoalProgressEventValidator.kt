package skillbill.workflow.goal

import skillbill.boundary.OpenBoundaryMap

interface GoalProgressEventValidator {
  @OpenBoundaryMap("Goal progress event wire map at the schema-validation seam")
  fun validate(event: Map<String, Any?>, sourceLabel: String)
}
