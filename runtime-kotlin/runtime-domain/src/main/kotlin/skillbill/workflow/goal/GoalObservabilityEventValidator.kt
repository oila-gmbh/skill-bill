package skillbill.workflow.goal

import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidGoalObservabilityEventSchemaError

interface GoalObservabilityEventValidator {
  @OpenBoundaryMap("Goal observability event wire map at the schema-validation seam")
  fun validate(event: Map<String, Any?>, sourceLabel: String)
}

fun invalidGoalObservabilityEvent(
  sourceLabel: String,
  fieldPath: String,
  reason: String,
  cause: Throwable? = null,
): InvalidGoalObservabilityEventSchemaError = InvalidGoalObservabilityEventSchemaError(
  sourceLabel = sourceLabel,
  fieldPath = fieldPath,
  reason = reason,
  cause = cause,
)
