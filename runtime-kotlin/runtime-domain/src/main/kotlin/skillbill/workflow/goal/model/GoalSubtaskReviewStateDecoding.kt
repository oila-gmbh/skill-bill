package skillbill.workflow.goal.model

// Typed accessors for the durable review-state artifact map. Every one loud-fails through
// reviewStateError so a malformed record is rejected at the parse seam rather than silently coerced.

internal fun Map<String, Any?>.requireOnlyReviewStateKeys(allowed: Set<String>, sourceLabel: String) {
  keys.forEach { key -> if (key !in allowed) reviewStateError("$sourceLabel.$key", "unknown field is not allowed.") }
}

internal fun Map<String, Any?>.requireReviewStateString(key: String, sourceLabel: String): String =
  (this[key] as? String)?.takeIf(String::isNotBlank)
    ?: reviewStateError("$sourceLabel.$key", "must be a non-blank string.")

internal fun Map<String, Any?>.optionalReviewStateString(key: String, sourceLabel: String): String? =
  when (val value = this[key]) {
    null -> null
    is String -> value.takeIf(String::isNotBlank)
      ?: reviewStateError("$sourceLabel.$key", "must be a non-blank string.")
    else -> reviewStateError("$sourceLabel.$key", "must be a string.")
  }

internal fun Map<String, Any?>.requireReviewStateInt(key: String, sourceLabel: String): Int =
  (this[key] as? Number)?.toInt()?.takeIf { value -> value.toDouble() == (this[key] as Number).toDouble() }
    ?: reviewStateError("$sourceLabel.$key", "must be an integer.")

internal fun Map<String, Any?>.optionalReviewStateInt(key: String, sourceLabel: String): Int? =
  if (key in this) requireReviewStateInt(key, sourceLabel) else null

internal fun Map<String, Any?>.requireReviewStateList(key: String, sourceLabel: String): List<*> =
  this[key] as? List<*> ?: reviewStateError("$sourceLabel.$key", "must be a list.")

internal fun Map<String, Any?>.optionalReviewStateList(key: String, sourceLabel: String): List<*>? =
  when (val value = this[key]) {
    null -> null
    is List<*> -> value
    else -> reviewStateError("$sourceLabel.$key", "must be a list.")
  }

internal fun Any?.asReviewStateMap(sourceLabel: String): Map<String, Any?> =
  (this as? Map<*, *>)?.entries?.associate { (key, value) ->
    val stringKey = key as? String ?: reviewStateError(sourceLabel, "map keys must be strings.")
    stringKey to value
  } ?: reviewStateError(sourceLabel, "must be an object.")

internal fun Any?.asGoalReviewArtifactMap(sourceLabel: String): Map<String, Any?> =
  (this as? Map<*, *>)?.entries?.associate { (key, value) ->
    val stringKey = key as? String ?: reviewStateError(sourceLabel, "map keys must be strings.")
    stringKey to value
  } ?: reviewStateError(sourceLabel, "must be an object.")
