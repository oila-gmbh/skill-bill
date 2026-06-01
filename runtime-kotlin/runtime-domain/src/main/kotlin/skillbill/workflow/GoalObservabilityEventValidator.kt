package skillbill.workflow

import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidGoalObservabilityEventSchemaError

/**
 * Domain-owned validator port for durable goal-observability event maps.
 * Concrete JSON-Schema validation lives in infra-fs; application storage
 * helpers call this port at both write and read seams.
 */
interface GoalObservabilityEventValidator {
  @OpenBoundaryMap("Goal observability event wire map at the schema-validation seam")
  fun validate(event: Map<String, Any?>, sourceLabel: String)
}

object NoopGoalObservabilityEventValidator : GoalObservabilityEventValidator {
  override fun validate(event: Map<String, Any?>, sourceLabel: String) = Unit
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
