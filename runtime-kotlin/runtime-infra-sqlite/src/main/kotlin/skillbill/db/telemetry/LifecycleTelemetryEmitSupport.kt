package skillbill.db.telemetry

import skillbill.contracts.JsonSupport
import skillbill.db.PARAM_FOUR
import skillbill.db.PARAM_ONE
import skillbill.db.PARAM_THREE
import skillbill.db.PARAM_TWO
import skillbill.review.model.REVIEW_STAGE_DEGRADATION_EVENT_NAME
import skillbill.review.model.ReviewStageDegradationMeasurement
import java.sql.Connection

fun emitFeatureTaskRuntimeStarted(connection: Connection, sessionId: String, level: String) {
  val row = lifecycleRow(connection, "feature_task_runtime_sessions", sessionId) ?: return
  emitOnce(
    LifecycleEmitRequest(connection, row, "feature_task_runtime_sessions", "started_event_emitted_at"),
    "skillbill_feature_task_runtime_started",
  ) { featureTaskRuntimeStartedPayload(row, level, telemetryRedactionSalt(connection)) }
}

fun emitFeatureTaskRuntimeFinished(connection: Connection, sessionId: String, level: String) {
  val row = lifecycleRow(connection, "feature_task_runtime_sessions", sessionId) ?: return
  emitOnce(
    LifecycleEmitRequest(connection, row, "feature_task_runtime_sessions", "finished_event_emitted_at"),
    "skillbill_feature_task_runtime_finished",
  ) { featureTaskRuntimeFinishedPayload(row, level) }
}

fun emitQualityCheckStarted(connection: Connection, sessionId: String) {
  val row = lifecycleRow(connection, "quality_check_sessions", sessionId) ?: return
  emitOnce(
    LifecycleEmitRequest(connection, row, "quality_check_sessions", "started_event_emitted_at"),
    "skillbill_quality_check_started",
  ) { qualityCheckStartedPayload(row) }
}

fun emitQualityCheckFinished(connection: Connection, sessionId: String, level: String) {
  val row = lifecycleRow(connection, "quality_check_sessions", sessionId) ?: return
  emitOnce(
    LifecycleEmitRequest(connection, row, "quality_check_sessions", "finished_event_emitted_at"),
    "skillbill_quality_check_finished",
  ) { qualityCheckFinishedPayload(row, level) }
}

fun emitFeatureVerifyStarted(connection: Connection, sessionId: String, level: String) {
  val row = lifecycleRow(connection, "feature_verify_sessions", sessionId) ?: return
  emitOnce(
    LifecycleEmitRequest(connection, row, "feature_verify_sessions", "started_event_emitted_at"),
    "skillbill_feature_verify_started",
  ) { featureVerifyStartedPayload(row, level) }
}

fun emitFeatureVerifyFinished(connection: Connection, sessionId: String, level: String) {
  val row = lifecycleRow(connection, "feature_verify_sessions", sessionId) ?: return
  emitOnce(
    LifecycleEmitRequest(connection, row, "feature_verify_sessions", "finished_event_emitted_at"),
    "skillbill_feature_verify_finished",
  ) { featureVerifyFinishedPayload(row, level) }
}

fun enqueueTelemetry(connection: Connection, eventName: String, payload: Map<String, Any?>) {
  TelemetryOutboxStore(connection).enqueue(eventName, JsonSupport.mapToJsonString(payload))
}

fun reviewStageDegradationExists(connection: Connection, record: ReviewStageDegradationMeasurement): Boolean =
  connection.prepareStatement(
    """
  SELECT 1 FROM telemetry_outbox
  WHERE event_name = ?
    AND json_extract(payload_json, '$.review_run_id') = ?
    AND json_extract(payload_json, '$.seam') = ?
    AND json_extract(payload_json, '$.reason') = ?
  LIMIT 1
    """.trimIndent(),
  ).use { statement ->
    statement.setString(PARAM_ONE, REVIEW_STAGE_DEGRADATION_EVENT_NAME)
    statement.setString(PARAM_TWO, record.reviewRunId)
    statement.setString(PARAM_THREE, record.seam)
    statement.setString(PARAM_FOUR, record.reason.wireValue)
    statement.executeQuery().use { it.next() }
  }

private data class LifecycleEmitRequest(
  val connection: Connection,
  val row: Map<String, Any?>,
  val tableName: String,
  val emittedColumn: String,
)

private fun emitOnce(request: LifecycleEmitRequest, eventName: String, payload: () -> Map<String, Any?>) {
  if (request.row.stringOrEmpty(request.emittedColumn).isNotBlank()) {
    return
  }
  enqueueTelemetry(request.connection, eventName, payload())
  markLifecycleEmitted(
    request.connection,
    request.tableName,
    request.emittedColumn,
    request.row.stringOrEmpty("session_id"),
  )
}
