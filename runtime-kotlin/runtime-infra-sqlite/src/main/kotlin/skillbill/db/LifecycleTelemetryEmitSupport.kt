package skillbill.db

import skillbill.contracts.JsonSupport
import java.sql.Connection

fun emitFeatureImplementStarted(connection: Connection, sessionId: String, level: String) {
  val row = lifecycleRow(connection, "feature_implement_sessions", sessionId) ?: return
  emitOnce(
    LifecycleEmitRequest(connection, row, "feature_implement_sessions", "started_event_emitted_at"),
    "skillbill_feature_implement_started",
  ) { featureImplementStartedPayload(row, level) }
}

fun emitFeatureImplementFinished(connection: Connection, sessionId: String, level: String) {
  val row = lifecycleRow(connection, "feature_implement_sessions", sessionId) ?: return
  emitOnce(
    LifecycleEmitRequest(connection, row, "feature_implement_sessions", "finished_event_emitted_at"),
    "skillbill_feature_implement_finished",
  ) { featureImplementFinishedPayload(row, level) }
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
