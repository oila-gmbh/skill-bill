package skillbill.workflow.model

import skillbill.workflow.invalidGoalObservabilityEvent

internal fun Map<*, *>.requireOnlyKeys(allowedKeys: Set<String>, sourceLabel: String) {
  keys.forEach { key ->
    val stringKey = key as? String
      ?: throw invalidGoalObservabilityEvent(sourceLabel, "", "event keys must be strings.")
    if (stringKey !in allowedKeys) {
      throw invalidGoalObservabilityEvent(sourceLabel, stringKey, "unknown field is not allowed.")
    }
  }
}

internal fun Map<String, Any?>.optionalMap(field: String, sourceLabel: String): Map<*, *>? =
  this[field]?.asRequiredMap("$sourceLabel.$field")

internal fun Any?.asRequiredMap(sourceLabel: String): Map<*, *> = this as? Map<*, *>
  ?: throw invalidGoalObservabilityEvent(sourceLabel, "", "field must be an object.")

internal fun Map<String, Any?>.optionalList(field: String, sourceLabel: String): List<*>? = this[field]?.let { raw ->
  raw as? List<*>
    ?: throw invalidGoalObservabilityEvent("$sourceLabel.$field", "", "field must be an array.")
}

internal fun Map<String, Any?>.optionalStringList(field: String, sourceLabel: String): List<String> =
  optionalList(field, sourceLabel)?.mapIndexed { index, raw ->
    raw.asRequiredString("$sourceLabel.$field[$index]", "")
      ?: throw invalidGoalObservabilityEvent("$sourceLabel.$field[$index]", "", "array entries must be strings.")
  }.orEmpty()

internal fun Map<*, *>.optionalProjectedStringList(field: String, sourceLabel: String): List<String> =
  this[field]?.let { raw ->
    val list = raw as? List<*>
      ?: throw invalidGoalObservabilityEvent("$sourceLabel.$field", "", "field must be an array.")
    list.mapIndexed { index, item ->
      item.asRequiredString("$sourceLabel.$field[$index]", "")
        ?: throw invalidGoalObservabilityEvent("$sourceLabel.$field[$index]", "", "array entries must be strings.")
    }
  }.orEmpty()

internal fun Any?.asRequiredString(sourceLabel: String, fieldPath: String): String? =
  (this as? String)?.takeIf(String::isNotBlank)
    ?: throw invalidGoalObservabilityEvent(sourceLabel, fieldPath, "field must be a non-empty string.")
