package skillbill.review

import skillbill.contracts.JsonSupport
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale

fun rate(count: Int, total: Int): Double = if (total == 0) {
  0.0
} else {
  String.format(Locale.US, "%.3f", count.toDouble() / total).toDouble()
}

fun average(values: List<Int>): Double = if (values.isEmpty()) {
  0.0
} else {
  String.format(Locale.US, "%.2f", values.average()).toDouble()
}

fun parseJsonList(rawValue: Any?): List<Any?> =
  if (rawValue is String) JsonSupport.parseArrayOrEmpty(rawValue) else emptyList()

fun durationSeconds(row: Map<String, Any?>): Int {
  val startedAt = row.stringValue("started_at")
  val finishedAt = row.stringValue("finished_at")
  if (startedAt.isEmpty() || finishedAt.isEmpty()) {
    return 0
  }
  return runCatching {
    val start = LocalDateTime.parse(startedAt.replace(" ", "T"))
    val end = LocalDateTime.parse(finishedAt.replace(" ", "T"))
    Duration.between(start, end).seconds.coerceAtLeast(0).toInt()
  }.getOrDefault(0)
}

fun collectRows(resultSet: java.sql.ResultSet): List<Map<String, Any?>> {
  val metadata = resultSet.metaData
  val columnNames = (1..metadata.columnCount).map(metadata::getColumnLabel)
  return buildList {
    while (resultSet.next()) {
      add(columnNames.associateWith(resultSet::getObject))
    }
  }
}

fun Map<String, Any?>.booleanValue(key: String): Boolean = when (val value = this[key]) {
  is Boolean -> value
  is Number -> value.toInt() != 0
  is String -> value == "1" || value.equals("true", ignoreCase = true)
  else -> false
}

fun Map<String, Any?>.intValue(key: String): Int = when (val value = this[key]) {
  is Number -> value.toInt()
  is String -> value.toIntOrNull() ?: 0
  else -> 0
}

fun Map<String, Any?>.stringValue(key: String): String = this[key]?.toString().orEmpty()
