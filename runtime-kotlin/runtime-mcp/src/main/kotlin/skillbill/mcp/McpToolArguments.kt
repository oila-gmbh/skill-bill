package skillbill.mcp

import skillbill.contracts.JsonSupport

internal fun Map<String, Any?>.string(name: String): String = optionalString(name).orEmpty()

internal fun Map<String, Any?>.optionalString(name: String): String? = this[name]?.toString()

internal fun Map<String, Any?>.boolean(name: String): Boolean = when (val value = this[name]) {
  is Boolean -> value
  is String -> value.equals("true", ignoreCase = true)
  else -> false
}

internal fun Map<String, Any?>.int(name: String, default: Int): Int = when (val value = this[name]) {
  is Int -> value
  is Long -> value.toInt()
  is Double -> value.toInt()
  is String -> value.toIntOrNull() ?: default
  else -> default
}

internal fun Map<String, Any?>.stringList(name: String): List<String> =
  (this[name] as? List<*>).orEmpty().map { it.toString() }

internal fun Map<String, Any?>.map(name: String): Map<String, Any?> = optionalMap(name).orEmpty()

internal fun Map<String, Any?>.optionalMap(name: String): Map<String, Any?>? = JsonSupport.anyToStringAnyMap(this[name])

internal fun Map<String, Any?>.optionalListMap(name: String): List<Map<String, Any?>>? =
  (this[name] as? List<*>)?.mapNotNull(JsonSupport::anyToStringAnyMap)
