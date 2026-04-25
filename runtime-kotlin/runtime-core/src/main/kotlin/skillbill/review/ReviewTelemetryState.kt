package skillbill.review

import skillbill.contracts.JsonSupport
import skillbill.db.TelemetryOutboxStore
import skillbill.telemetry.TelemetryConfigRuntime
import java.sql.Connection

data class ReviewTelemetryState(
  val enabled: Boolean,
  val level: String,
)

fun resolveTelemetryState(enabled: Boolean?, level: String?): ReviewTelemetryState {
  val settings =
    if (enabled == null || level == null) {
      runCatching { TelemetryConfigRuntime.loadTelemetrySettings() }.getOrNull()
    } else {
      null
    }
  return ReviewTelemetryState(
    enabled = enabled ?: settings?.enabled ?: false,
    level = level ?: settings?.level ?: "off",
  )
}

fun reviewAlreadyEmittedForSession(connection: Connection, sessionId: String, reviewRunId: String): Boolean {
  if (sessionId.isEmpty()) {
    return false
  }
  return connection.prepareStatement(
    """
    SELECT 1 FROM review_runs
    WHERE review_session_id = ?
      AND review_run_id != ?
      AND review_finished_event_emitted_at IS NOT NULL
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, sessionId)
    statement.setString(PARAM_TWO, reviewRunId)
    statement.executeQuery().use { resultSet -> resultSet.next() }
  }
}

fun ensureReviewFinishedTimestamp(
  connection: Connection,
  reviewRunId: String,
  reviewSummary: ReviewSummary,
): ReviewSummary {
  if (!reviewSummary.reviewFinishedAt.isNullOrEmpty()) {
    return reviewSummary
  }
  connection.prepareStatement(
    """
    UPDATE review_runs
    SET review_finished_at = CURRENT_TIMESTAMP
    WHERE review_run_id = ? AND review_finished_at IS NULL
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.executeUpdate()
  }
  return ReviewRuntime.fetchReviewSummary(connection, reviewRunId)
}

fun finalizeReviewFinishedTelemetry(
  connection: Connection,
  reviewRunId: String,
  reviewSummary: ReviewSummary,
  payload: Map<String, Any?>,
  telemetryEnabled: Boolean,
): Map<String, Any?>? = when {
  reviewSummary.orchestratedRun -> payload
  !reviewSummary.reviewFinishedEventEmittedAt.isNullOrEmpty() -> {
    if (telemetryEnabled) {
      updatePendingReviewFinishedEvent(connection, reviewSummary.reviewSessionId.orEmpty(), payload)
    }
    payload
  }
  else -> {
    enqueueTelemetryEvent(connection, "skillbill_review_finished", payload, telemetryEnabled)
    if (telemetryEnabled) {
      markReviewFinishedEventEmitted(connection, reviewRunId)
    }
    payload
  }
}

fun enqueueTelemetryEvent(connection: Connection, eventName: String, payload: Map<String, Any?>, enabled: Boolean) {
  if (enabled) {
    TelemetryOutboxStore(connection).enqueue(eventName, JsonSupport.mapToJsonString(payload))
  }
}

fun updatePendingReviewFinishedEvent(connection: Connection, reviewSessionId: String, payload: Map<String, Any?>) {
  connection.prepareStatement(
    """
    UPDATE telemetry_outbox
    SET payload_json = ?
    WHERE event_name = 'skillbill_review_finished'
      AND synced_at IS NULL
      AND json_extract(payload_json, '$.review_session_id') = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, JsonSupport.mapToJsonString(payload))
    statement.setString(PARAM_TWO, reviewSessionId)
    statement.executeUpdate()
  }
}

fun markReviewFinishedEventEmitted(connection: Connection, reviewRunId: String) {
  connection.prepareStatement(
    """
    UPDATE review_runs
    SET review_finished_event_emitted_at = CURRENT_TIMESTAMP
    WHERE review_run_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, reviewRunId)
    statement.executeUpdate()
  }
}
