package skillbill.infrastructure.sqlite.review

import skillbill.contracts.JsonSupport
import java.sql.Connection

fun loadStandaloneReviewPayloads(connection: Connection): List<ReviewHealthPayload> = connection.prepareStatement(
  """
    SELECT payload_json
    FROM telemetry_outbox
    WHERE event_name = 'skillbill_review_finished'
    ORDER BY id
  """.trimIndent(),
).use { statement ->
  statement.executeQuery().use { resultSet ->
    buildList {
      while (resultSet.next()) {
        add(ReviewHealthPayload("standalone", parseJsonObject(resultSet.getString("payload_json"))))
      }
    }
  }
}

fun loadEmbeddedReviewPayloads(connection: Connection): List<ReviewHealthPayload> =
  loadRows(connection, "feature_implement_sessions").flatMap(::embeddedReviewPayloads)

fun Map<String, Any?>.healthInt(key: String): Int = this[key].asInt()

fun Map<String, Any?>.stringHealthValue(key: String): String = this[key]?.toString().orEmpty()

private fun embeddedReviewPayloads(row: Map<String, Any?>): List<ReviewHealthPayload> {
  val rawChildSteps = row.stringValue("child_steps_json")
  if (rawChildSteps.isBlank()) {
    return emptyList()
  }
  val parsed = JsonSupport.parseArrayOrEmpty(rawChildSteps)
  return if (parsed.isEmpty() && rawChildSteps.trim() != "[]") {
    listOf(ReviewHealthPayload("malformed", emptyMap()))
  } else {
    parsed.mapNotNull(::childStepToReviewPayload)
  }
}

private fun childStepToReviewPayload(childStep: Any?): ReviewHealthPayload? {
  val payload = childStep as? Map<*, *> ?: return ReviewHealthPayload("malformed", emptyMap())
  val normalized = payload.toStringAnyMap()
  return if (isReviewChildStep(normalized)) {
    ReviewHealthPayload("embedded", normalized)
  } else {
    null
  }
}

private fun parseJsonObject(rawJson: String): Map<String, Any?> = JsonSupport.parseObjectOrNull(rawJson)
  ?.let { JsonSupport.jsonElementToValue(it) as? Map<*, *> }
  ?.toStringAnyMap()
  ?: emptyMap()

private fun isReviewChildStep(payload: Map<String, Any?>): Boolean {
  val skill = payload.stringHealthValue("skill")
  return skill.endsWith("code-review") || "-code-review-" in skill
}

private fun Map<*, *>.toStringAnyMap(): Map<String, Any?> =
  entries.mapNotNull { (key, value) -> key?.toString()?.let { it to value } }.toMap()

private fun Any?.asInt(): Int = when (this) {
  is Number -> toInt()
  is String -> toIntOrNull() ?: 0
  else -> 0
}
