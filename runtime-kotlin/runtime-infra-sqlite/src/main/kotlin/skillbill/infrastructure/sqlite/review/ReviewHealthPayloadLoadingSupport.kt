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
    SELECT id, payload_json, synced_at
    FROM telemetry_outbox
    WHERE event_name = 'skillbill_review_finished'
    ORDER BY id
  """.trimIndent(),
).use { statement ->
  statement.executeQuery().use { resultSet ->
    buildList {
      while (resultSet.next()) {
        val rowId = resultSet.getLong("id")
        val raw = resultSet.getString("payload_json")
        val synced = resultSet.getString("synced_at")
        val parsed = parseJsonObject(raw)
        if (parsed.isEmpty() && raw.trim() != "{}") {
          add(ReviewHealthPayload("malformed", emptyMap()))
          continue
        }
        val materialized = materializeReviewFinishedPayload(connection, parsed, rowId, synced)
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

fun materializeReviewFinishedPayload(
  connection: Connection,
  payload: Map<String, Any?>,
  outboxId: Long? = null,
  syncedAt: String? = null,
): Map<String, Any?> {
  if (payload.isEmpty()) return payload
  restampUnsyncedContractVersion(connection, outboxId, syncedAt, payload)
  if (!isLegacyReviewFinished(payload)) return payload
  val reviewRunId = payload.stringHealthValue("review_run_id")
  if (reviewRunId.isBlank()) return payload
  val regenerated = ReviewStatsRuntime.buildReviewFinishedPayload(connection, reviewRunId)
    .toReviewFinishedTelemetryPayload()
    .toPayload()
  val rewritten = LinkedHashMap(payload).apply {
    putAll(regenerated)
    put("contract_version", REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION)
  }
  if (outboxId != null && syncedAt.isNullOrBlank()) {
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
  return rewritten
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

private fun restampUnsyncedContractVersion(
  connection: Connection,
  outboxId: Long?,
  syncedAt: String?,
  payload: Map<String, Any?>,
) {
  if (outboxId == null || !syncedAt.isNullOrBlank()) return
  if (payload["contract_version"]?.toString() != REVIEW_FINISHED_LEGACY_CONTRACT_VERSION) return
  if (payload.containsKey("verification")) {
    val restamped = LinkedHashMap(payload)
    restamped["contract_version"] = REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION
    rewriteOutboxPayload(connection, outboxId, restamped)
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
