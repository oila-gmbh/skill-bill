package skillbill.workflow.model

import skillbill.workflow.invalidGoalObservabilityEvent

internal fun Map<String, Any?>.optionalInt(field: String, sourceLabel: String): Int? {
  val raw = this[field] ?: return null
  return raw.asGoalObservabilityIntOrNull()
    ?: throw invalidGoalObservabilityEvent(sourceLabel, field, "field must be an integer when present.")
}

internal fun Map<String, Any?>.optionalPositiveInt(field: String, sourceLabel: String): Int? =
  optionalInt(field, sourceLabel)?.also { value ->
    if (value < 1) {
      throw invalidGoalObservabilityEvent(sourceLabel, field, "field must be a positive integer when present.")
    }
  }
