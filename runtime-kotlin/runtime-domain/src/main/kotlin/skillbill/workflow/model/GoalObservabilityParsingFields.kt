package skillbill.workflow.model

import skillbill.contracts.workflow.GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION
import skillbill.workflow.invalidGoalObservabilityEvent

internal fun List<*>?.toStringList(): List<String> =
  this?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }.orEmpty()

internal fun Map<String, Any?>.requiredString(field: String, sourceLabel: String): String = optionalString(field)
  ?: throw invalidGoalObservabilityEvent(sourceLabel, field, "field is required and must be a non-empty string.")

internal fun Map<String, Any?>.optionalString(field: String): String? =
  (this[field] as? String)?.takeIf(String::isNotBlank)

internal fun Map<String, Any?>.requiredInt(field: String, sourceLabel: String): Int =
  this[field].asGoalObservabilityIntOrNull()
    ?: throw invalidGoalObservabilityEvent(sourceLabel, field, "field is required and must be an integer.")

internal fun Map<String, Any?>.requiredPositiveInt(field: String, sourceLabel: String): Int =
  requiredInt(field, sourceLabel).also { value ->
    if (value < 1) {
      throw invalidGoalObservabilityEvent(sourceLabel, field, "field must be a positive integer.")
    }
  }

internal fun Map<String, Any?>.requiredNonNegativeInt(field: String, sourceLabel: String): Int =
  requiredInt(field, sourceLabel).also { value ->
    if (value < 0) {
      throw invalidGoalObservabilityEvent(sourceLabel, field, "field must be a non-negative integer.")
    }
  }

internal fun Map<String, Any?>.requiredContractVersion(sourceLabel: String): String =
  requiredString("contract_version", sourceLabel).also { value ->
    if (value != GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION) {
      throw invalidGoalObservabilityEvent(
        sourceLabel,
        "contract_version",
        "field must equal $GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION.",
      )
    }
  }

internal fun Map<*, *>.requiredProjectedInt(field: String, sourceLabel: String): Int =
  this[field].asGoalObservabilityIntOrNull()
    ?: throw invalidGoalObservabilityEvent(sourceLabel, field, "field is required and must be an integer.")

internal fun Map<*, *>.requiredProjectedNonNegativeInt(field: String, sourceLabel: String): Int =
  requiredProjectedInt(field, sourceLabel).also { value ->
    if (value < 0) {
      throw invalidGoalObservabilityEvent(sourceLabel, field, "field must be a non-negative integer.")
    }
  }

internal fun Any?.asGoalObservabilityIntOrNull(): Int? = when (this) {
  is Int -> this
  is Short -> toInt()
  is Byte -> toInt()
  is Long -> takeIf { value -> value in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
  else -> null
}
