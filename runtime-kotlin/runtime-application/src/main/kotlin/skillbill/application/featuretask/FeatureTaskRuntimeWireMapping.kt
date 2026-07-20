package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import java.math.BigDecimal

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

internal fun Map<String, Any?>.requiredInt(field: String, source: String): Int {
  val value = this[field] ?: invalidWire("$source.$field", "is required and must be an integer")
  if (value !is Number) invalidWire("$source.$field", "must be an integer")
  return try {
    BigDecimal(value.toString()).toBigIntegerExact().intValueExact()
  } catch (_: ArithmeticException) {
    invalidWire("$source.$field", "must be an exact 32-bit integer")
  } catch (_: NumberFormatException) {
    invalidWire("$source.$field", "must be an exact 32-bit integer")
  }
}

internal fun Map<String, Any?>.requiredBoolean(field: String, source: String): Boolean =
  this[field] as? Boolean ?: invalidWire("$source.$field", "is required and must be a boolean")

internal fun Map<String, Any?>.optionalString(field: String, source: String): String? = when (val value = this[field]) {
  null -> null
  is String -> value.takeIf(String::isNotBlank) ?: invalidWire("$source.$field", "must be nonblank when present")
  else -> invalidWire("$source.$field", "must be a string when present")
}

internal fun invalidWire(source: String, reason: String): Nothing =
  throw InvalidWorkflowStateSchemaError("$source $reason")
