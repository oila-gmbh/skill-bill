package skillbill.infrastructure.sqlite.review

import skillbill.contracts.JsonSupport
import skillbill.review.model.REVIEW_FINISHED_LEGACY_CONTRACT_VERSION
import skillbill.review.model.REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION
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
        val raw = resultSet.getString("payload_json")
        val parsed = parseHealthJsonObject(raw)
        if (parsed.isEmpty() && raw.trim() != "{}") {
          add(ReviewHealthPayload("malformed", emptyMap()))
          continue
        }
        val materialized = materializeReviewFinishedPayload(connection, parsed)
        if (materialized.isEmpty() && parsed.isNotEmpty()) {
          add(ReviewHealthPayload("malformed", emptyMap()))
        } else {
          add(ReviewHealthPayload("standalone", materialized))
        }
      }
    }
  }
}

fun loadEmbeddedReviewPayloads(connection: Connection): List<ReviewHealthPayload> =
  loadRows(connection, "feature_implement_sessions").flatMap(::embeddedReviewPayloads)

fun restampUnsyncedLegacyTelemetry(connection: Connection) {
  connection.prepareStatement(
    """
    UPDATE telemetry_outbox
    SET payload_json = json_set(payload_json, '$.contract_version', ?)
    WHERE synced_at IS NULL
      AND event_name != 'skillbill_review_finished'
      AND json_extract(payload_json, '$.contract_version') = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION)
    statement.setString(PARAM_TWO, REVIEW_FINISHED_LEGACY_CONTRACT_VERSION)
    statement.executeUpdate()
  }
}

fun Map<String, Any?>.healthInt(key: String): Int = this[key].asHealthInt()

fun Map<String, Any?>.stringHealthValue(key: String): String = this[key]?.toString().orEmpty()

fun persistLegacyTelemetryRewrites(connection: Connection) {
  restampUnsyncedLegacyTelemetry(connection)
  val unsyncedReviewFinished = connection.prepareStatement(
    """
    SELECT id, payload_json
    FROM telemetry_outbox
    WHERE event_name = 'skillbill_review_finished'
      AND synced_at IS NULL
    ORDER BY id
    """.trimIndent(),
  ).use { statement ->
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(resultSet.getLong("id") to resultSet.getString("payload_json"))
        }
      }
    }
  }
  unsyncedReviewFinished.forEach { (outboxId, raw) ->
    persistLegacyReviewFinishedRow(connection, outboxId, raw)
  }
}

internal fun parseHealthJsonObject(rawJson: String): Map<String, Any?> = JsonSupport.parseObjectOrNull(rawJson)
  ?.let { JsonSupport.jsonElementToValue(it) as? Map<*, *> }
  ?.toHealthStringAnyMap()
  ?: emptyMap()

internal fun Map<*, *>.toHealthStringAnyMap(): Map<String, Any?> =
  entries.mapNotNull { (key, value) -> key?.toString()?.let { it to value } }.toMap()

private fun Any?.asHealthInt(): Int = when (this) {
  is Number -> toInt()
  is String -> toIntOrNull() ?: 0
  else -> 0
}
