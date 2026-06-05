package skillbill.db

fun Map<String, Any?>.stringOrEmpty(name: String): String = this[name]?.toString().orEmpty()

fun Map<String, Any?>.intOrZero(name: String): Int = when (val value = this[name]) {
  is Number -> value.toInt()
  is String -> value.toIntOrNull() ?: 0
  else -> 0
}

fun Map<String, Any?>.longOrZero(name: String): Long = when (val value = this[name]) {
  is Number -> value.toLong()
  is String -> value.toLongOrNull() ?: 0L
  else -> 0L
}

fun Map<String, Any?>.booleanFromInt(name: String): Boolean = intOrZero(name) != 0
