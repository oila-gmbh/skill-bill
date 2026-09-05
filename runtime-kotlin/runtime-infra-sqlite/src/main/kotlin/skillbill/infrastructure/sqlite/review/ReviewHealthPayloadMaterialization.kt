package skillbill.infrastructure.sqlite.review
import skillbill.contracts.JsonCodec
import skillbill.db.PARAM_ONE
import skillbill.db.PARAM_TWO
import skillbill.db.telemetry.enqueueTelemetry
import skillbill.ports.telemetry.model.toReviewFinishedTelemetryPayload
import skillbill.review.model.REVIEW_FINISHED_LEGACY_CONTRACT_VERSION
import skillbill.review.model.REVIEW_FINISHED_LEGACY_REGENERATED_EVENT_NAME
import skillbill.review.model.REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION
import java.sql.Connection

fun materializeReviewFinishedPayload(connection: Connection, payload: Map<String, Any?>): Map<String, Any?> {
  if (payload.isEmpty() || !isLegacyReviewFinished(payload)) return payload
  val reviewRunId = payload.stringHealthValue("review_run_id")
  if (reviewRunId.isBlank()) return payload
  if (reviewRunRowExists(connection, reviewRunId)) {
    return regenerateReviewFinishedPayload(connection, payload, reviewRunId)
  }
  return if (payload["contract_version"]?.toString() == REVIEW_FINISHED_LEGACY_CONTRACT_VERSION) {
    emptyMap()
  } else {
    payload
  }
}

internal fun persistLegacyReviewFinishedRow(connection: Connection, outboxId: Long, raw: String) {
  val payload = parseHealthJsonObject(raw)
  if (payload.isEmpty() || !isLegacyReviewFinished(payload)) return
  val reviewRunId = payload.stringHealthValue("review_run_id")
  if (reviewRunId.isBlank() || !reviewRunRowExists(connection, reviewRunId)) return
  val rewritten = regenerateReviewFinishedPayload(connection, payload, reviewRunId)
  rewriteOutboxPayload(connection, outboxId, rewritten)
  enqueueTelemetry(
    connection,
    REVIEW_FINISHED_LEGACY_REGENERATED_EVENT_NAME,
    linkedMapOf(
      "event_name" to REVIEW_FINISHED_LEGACY_REGENERATED_EVENT_NAME,
      "contract_version" to REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION,
      "review_run_id" to reviewRunId,
      "from_version" to (payload["contract_version"]?.toString() ?: REVIEW_FINISHED_LEGACY_CONTRACT_VERSION),
      "to_version" to REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION,
    ),
  )
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

private fun regenerateReviewFinishedPayload(
  connection: Connection,
  payload: Map<String, Any?>,
  reviewRunId: String,
): Map<String, Any?> {
  val regenerated = ReviewStatsRuntime.buildReviewFinishedPayload(
    ReviewFinishedPayloadBuildRequest(connection = connection, reviewRunId = reviewRunId),
  )
    .toReviewFinishedTelemetryPayload()
    .toPayload()
  return LinkedHashMap(payload).apply {
    putAll(regenerated)
    put("contract_version", REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION)
  }
}

private fun reviewRunRowExists(connection: Connection, reviewRunId: String): Boolean =
  connection.prepareStatement("SELECT 1 FROM review_runs WHERE review_run_id = ?").use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.executeQuery().use { resultSet -> resultSet.next() }
  }

private fun rewriteOutboxPayload(connection: Connection, outboxId: Long, payload: Map<String, Any?>) {
  connection.prepareStatement(
    "UPDATE telemetry_outbox SET payload_json = ? WHERE id = ?",
  ).use { statement ->
    statement.setString(PARAM_ONE, JsonCodec.mapToJsonString(payload))
    statement.setLong(PARAM_TWO, outboxId)
    statement.executeUpdate()
  }
}
