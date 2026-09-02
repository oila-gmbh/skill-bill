package skillbill.ports.workflow.persistence

import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.random.Random

const val WORKFLOW_ID_SUFFIX_LENGTH: Int = 4
const val SUFFIX_CHARS: String = "abcdefghijklmnopqrstuvwxyz0123456789"

fun generateWorkflowId(prefix: String): String {
  val now = OffsetDateTime.now(ZoneOffset.UTC)
  val suffix = (1..WORKFLOW_ID_SUFFIX_LENGTH).map { SUFFIX_CHARS[Random.nextInt(SUFFIX_CHARS.length)] }
    .joinToString("")
  return "$prefix-${now.year}${now.monthValue.twoDigits()}${now.dayOfMonth.twoDigits()}-" +
    "${now.hour.twoDigits()}${now.minute.twoDigits()}${now.second.twoDigits()}-$suffix"
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')
