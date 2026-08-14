package skillbill.infrastructure.sqlite.review

import skillbill.contracts.JsonSupport
import skillbill.db.telemetry.enqueueTelemetry
import skillbill.ports.telemetry.model.toReviewFinishedTelemetryPayload
import skillbill.review.model.REVIEW_FINISHED_LEGACY_CONTRACT_VERSION
import skillbill.review.model.REVIEW_FINISHED_LEGACY_REGENERATED_EVENT_NAME
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
        val parsed = parseJsonObject(raw)
        if (parsed.isEmpty() && raw.trim() != "{}") {
          add(ReviewHealthPayload("malformed", emptyMap()))
          continue
        }
        val materialized = materializeReviewFinishedPayload(connection, parsed)
        add(ReviewHealthPayload("standalone", materialized))
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

fun Map<String, Any?>.healthInt(key: String): Int = this[key].asInt()

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

fun materializeReviewFinishedPayload(
  connection: Connection,
  payload: Map<String, Any?>,
): Map<String, Any?> {
  if (payload.isEmpty()) return payload
  if (!isLegacyReviewFinished(payload)) return payload
  val reviewRunId = payload.stringHealthValue("review_run_id")
  if (reviewRunId.isBlank()) return payload
  return regenerateReviewFinishedPayload(connection, payload, reviewRunId)
}

private fun isLegacyReviewFinished(payload: Map<String, Any?>): Boolean {
  val version = payload["contract_version"]?.toString()
  return version == REVIEW_FINISHED_LEGACY_CONTRACT_VERSION ||
    !payload.containsKey("verification") ||
    !payload.containsKey("adjudication") ||
    !payload.containsKey("refutation_rate_by_stage") ||
    !payload.containsKey("rejected_verdict_counts") ||
    !payload.containsKey("severity_adjustment_counts") ||
    !payload.containsKey("resolved_tier")
}

private fun persistLegacyReviewFinishedRow(connection: Connection, outboxId: Long, raw: String) {
  val payload = parseJsonObject(raw)
  if (payload.isEmpty() || !isLegacyReviewFinished(payload)) return
  val reviewRunId = payload.stringHealthValue("review_run_id")
  if (reviewRunId.isBlank()) return
  val rewritten = regenerateReviewFinishedPayload(connection, payload, reviewRunId)
  rewriteOutboxPayload(connection, outboxId, rewritten)
  enqueueTelemetry(
    connection,
    REVIEW_FINISHED_LEGACY_REGENERATED_EVENT_NAME,
    linkedMapOf(
      "review_run_id" to reviewRunId,
      "from_version" to (payload["contract_version"]?.toString() ?: REVIEW_FINISHED_LEGACY_CONTRACT_VERSION),
      "to_version" to REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION,
    ),
  )
}

private fun regenerateReviewFinishedPayload(
  connection: Connection,
  payload: Map<String, Any?>,
  reviewRunId: String,
): Map<String, Any?> {
  val regenerated = ReviewStatsRuntime.buildReviewFinishedPayload(connection, reviewRunId)
    .toReviewFinishedTelemetryPayload()
    .toPayload()
  return LinkedHashMap(payload).apply {
    putAll(regenerated)
    put("contract_version", REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION)
  }
}

private fun rewriteOutboxPayload(connection: Connection, outboxId: Long, payload: Map<String, Any?>) {
  connection.prepareStatement(
    "UPDATE telemetry_outbox SET payload_json = ? WHERE id = ?",
  ).use { statement ->
    statement.setString(PARAM_ONE, JsonSupport.mapToJsonString(payload))
    statement.setLong(PARAM_TWO, outboxId)
    statement.executeUpdate()
  }
}

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
