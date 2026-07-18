package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError

internal inline fun <T> wireMapping(source: String, block: () -> T): T = try {
  block()
} catch (error: InvalidWorkflowStateSchemaError) {
  throw error
} catch (error: IllegalArgumentException) {
  throw InvalidWorkflowStateSchemaError("$source: ${error.message}", error)
}

internal fun Any?.requiredMap(source: String): Map<String, Any?> =
  JsonSupport.anyToStringAnyMap(this) ?: invalidWire(source, "must be an object")

internal fun Map<String, Any?>.requiredString(field: String, source: String): String =
  (this[field] as? String)?.takeIf(String::isNotBlank) ?: invalidWire("$source.$field", "must be nonblank")

internal fun Map<String, Any?>.requiredList(field: String, source: String): List<Any?> =
  (this[field] as? List<*>)?.takeIf { it.isNotEmpty() }?.toList()
    ?: invalidWire("$source.$field", "must be a non-empty array")

internal fun Map<String, Any?>.optionalList(field: String, source: String): List<Any?> =
  this[field]?.let { value -> (value as? List<*>)?.toList() ?: invalidWire("$source.$field", "must be an array") }
    ?: emptyList()

internal fun Map<String, Any?>.stringList(field: String, source: String, required: Boolean = false): List<String> {
  val values = optionalList(field, source)
  if (required && values.isEmpty()) invalidWire("$source.$field", "must be a non-empty array")
  return values.mapIndexed { index, value ->
    (value as? String)?.takeIf(String::isNotBlank)
      ?: invalidWire("$source.$field[$index]", "must be nonblank")
  }
}

internal fun Map<String, Any?>.int(field: String): Int? = (this[field] as? Number)?.toInt()

internal fun invalidWire(source: String, reason: String): Nothing =
  throw InvalidWorkflowStateSchemaError("$source $reason")
