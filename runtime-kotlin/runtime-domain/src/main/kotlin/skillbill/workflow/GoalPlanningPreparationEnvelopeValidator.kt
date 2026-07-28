package skillbill.workflow

import skillbill.boundary.OpenBoundaryMap

interface GoalPlanningPreparationEnvelopeValidator {
  @OpenBoundaryMap("Goal planning preparation wire map at the schema-validation seam")
  fun validate(envelope: Map<String, Any?>, sourceLabel: String)
}
