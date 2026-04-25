package skillbill.db

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

fun durationSeconds(row: Map<String, Any?>): Int {
  val startedAt = row.stringOrEmpty("started_at")
  val finishedAt = row.stringOrEmpty("finished_at")
  return if (startedAt.isBlank() || finishedAt.isBlank()) {
    0
  } else {
    parseDurationSeconds(startedAt, finishedAt)
  }
}

private fun parseDurationSeconds(startedAt: String, finishedAt: String): Int = runCatching {
  val start = LocalDateTime.parse(startedAt.replace(' ', 'T'))
  val end = LocalDateTime.parse(finishedAt.replace(' ', 'T'))
  maxOf(0, ChronoUnit.SECONDS.between(start, end).toInt())
}.getOrDefault(0)
