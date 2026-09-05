package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidWorkflowStateSchemaError
import java.math.BigDecimal
import java.math.BigInteger

// Strict field decoders. The optional variants return null only when the key is
// absent; a present-but-malformed value still loud-fails rather than defaulting.

internal fun Map<String, Any?>.requireStringField(key: String): String {
  val value = this[key]
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact map is missing required field '$key'.",
    )
  return (value as? String)?.takeIf(String::isNotBlank)
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$key' must decode to a non-blank string.",
    )
}

internal fun Map<String, Any?>.optionalStringField(key: String): String? {
  if (!containsKey(key) || this[key] == null) {
    return null
  }
  return (this[key] as? String)?.takeIf(String::isNotBlank)
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$key' must decode to a non-blank string when present.",
    )
}

internal fun Map<String, Any?>.requireIntField(key: String): Int {
  if (!containsKey(key) || this[key] == null) {
    throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact map is missing required integer field '$key'.",
    )
  }
  return this[key].asExactIntOrNull()
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$key' must decode to an integer.",
    )
}

internal fun Map<String, Any?>.optionalIntField(key: String): Int? {
  if (!containsKey(key) || this[key] == null) {
    return null
  }
  return this[key].asExactIntOrNull()
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$key' must decode to an integer when present.",
    )
}

internal fun Map<String, Any?>.optionalStringListField(key: String): List<String> {
  if (!containsKey(key) || this[key] == null) {
    return emptyList()
  }
  val list = this[key] as? List<*>
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$key' must decode to a list of strings when present.",
    )
  return list.map { element ->
    (element as? String)?.takeIf(String::isNotBlank)
      ?: throw InvalidWorkflowStateSchemaError(
        "Feature-task-runtime artifact field '$key' must contain only non-blank strings.",
      )
  }
}

internal fun Map<String, Any?>.optionalBooleanField(key: String): Boolean? {
  if (!containsKey(key) || this[key] == null) {
    return null
  }
  return this[key] as? Boolean
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$key' must decode to a boolean when present.",
    )
}

internal fun Map<String, Any?>.optionalLongField(key: String): Long? {
  if (!containsKey(key) || this[key] == null) {
    return null
  }
  return this[key].asExactLongOrNull()
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$key' must decode to a long integer when present.",
    )
}

private fun Any?.asExactIntOrNull(): Int? = asExactLongOrNull()?.let { value ->
  if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
}

private fun Any?.asExactLongOrNull(): Long? = when (this) {
  is Byte -> toLong()
  is Short -> toLong()
  is Int -> toLong()
  is Long -> this
  is BigInteger -> runCatching { longValueExact() }.getOrNull()
  is BigDecimal -> runCatching { longValueExact() }.getOrNull()
  is String -> toLongOrNull()
  else -> null
}
