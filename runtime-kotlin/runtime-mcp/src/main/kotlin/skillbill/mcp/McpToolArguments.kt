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

internal fun Map<String, Any?>.optionalInt(name: String): Int? = when (val value = this[name]) {
  null -> null
  is Int -> value
  is Long -> {
    require(value >= Int.MIN_VALUE.toLong() && value <= Int.MAX_VALUE.toLong()) {
      "$name must fit in a 32-bit integer."
    }
    value.toInt()
  }
  is Double -> {
    require(value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) {
      "$name must be an integer."
    }
    value.toInt()
  }
  is String -> value.toIntOrNull() ?: error("$name must be an integer.")
  else -> error("$name must be an integer.")
}

internal fun Map<String, Any?>.stringList(name: String): List<String> =
  (this[name] as? List<*>).orEmpty().map { it.toString() }

internal fun Map<String, Any?>.map(name: String): Map<String, Any?> = optionalMap(name).orEmpty()

internal fun Map<String, Any?>.optionalMap(name: String): Map<String, Any?>? = JsonSupport.anyToStringAnyMap(this[name])

internal fun Map<String, Any?>.optionalListMap(name: String): List<Map<String, Any?>>? =
  (this[name] as? List<*>)?.mapNotNull(JsonSupport::anyToStringAnyMap)
