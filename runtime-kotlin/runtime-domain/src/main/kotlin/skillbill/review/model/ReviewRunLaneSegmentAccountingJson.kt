package skillbill.review.model

import skillbill.review.context.model.ReviewLaneSegmentAccounting

/** Minimal JSON codec for persisting segment accounting on [ReviewRunLane] rows. */
object ReviewRunLaneSegmentAccountingJson {
  fun encode(segments: List<ReviewLaneSegmentAccounting>): String? {
    if (segments.isEmpty()) return null
    return buildString {
      append('[')
      segments.forEachIndexed { index, segment ->
        if (index > 0) append(',')
        append('{')
        append("\"segment_id\":\"").append(escape(segment.segmentId)).append('"')
        append(",\"measured_bytes\":").append(segment.measuredBytes)
        append(",\"entry_count\":").append(segment.entryCount)
        append(",\"composition_digest\":\"").append(segment.compositionDigest).append('"')
        append('}')
      }
      append(']')
    }
  }

  fun decode(raw: String?): List<ReviewLaneSegmentAccounting> {
    if (raw.isNullOrBlank()) return emptyList()
    val trimmed = raw.trim()
    if (trimmed == "[]") return emptyList()
    require(trimmed.startsWith('[') && trimmed.endsWith(']')) {
      "Segment accounting JSON must be an array."
    }
    val body = trimmed.substring(1, trimmed.length - 1).trim()
    if (body.isEmpty()) return emptyList()
    return body.split("},{").map { fragment ->
      val normalized = fragment.trim().trimStart('{').trimEnd('}')
      ReviewLaneSegmentAccounting(
        segmentId = stringField(normalized, "segment_id"),
        measuredBytes = longField(normalized, "measured_bytes"),
        entryCount = intField(normalized, "entry_count"),
        compositionDigest = stringField(normalized, "composition_digest"),
      )
    }
  }

  private fun stringField(fragment: String, key: String): String {
    val pattern = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    val match = pattern.find(fragment) ?: error("Missing '$key' in segment accounting JSON.")
    return decodeString(match.groupValues[1])
  }

  private fun longField(fragment: String, key: String): Long {
    val pattern = Regex("\"$key\"\\s*:\\s*(\\d+)")
    val match = pattern.find(fragment) ?: error("Missing '$key' in segment accounting JSON.")
    return match.groupValues[1].toLong()
  }

  private fun intField(fragment: String, key: String): Int = longField(fragment, key).toInt()

  private fun escape(value: String): String = buildString {
    value.forEach { char ->
      when (char) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        else -> append(char)
      }
    }
  }

  private fun decodeString(value: String): String = buildString {
    var index = 0
    while (index < value.length) {
      if (value[index] != '\\') {
        append(value[index++])
        continue
      }
      require(++index < value.length) { "Malformed segment accounting JSON escape." }
      when (val escaped = value[index++]) {
        '\\', '"' -> append(escaped)
        else -> error("Unsupported segment accounting JSON escape '$escaped'.")
      }
    }
  }
}

fun List<String>.toStoredSegmentIdList(): String = joinToString(",")

fun String.toStoredSegmentIdList(): List<String> =
  split(',').map { it.trim() }.filter { it.isNotEmpty() }
