package skillbill.db.telemetry

import skillbill.ports.telemetry.FeatureVerifyLifecycleTelemetryRepository
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import java.sql.Connection

internal class LifecycleTelemetryFeatureVerifySessionAdapter(
  private val connection: Connection,
) : FeatureVerifyLifecycleTelemetryRepository {
  override fun featureVerifyStarted(record: FeatureVerifyStartedRecord, level: String) {
    saveFeatureVerifyStarted(connection, record)
    emitFeatureVerifyStarted(connection, record.sessionId, level)
  }

  override fun featureVerifyFinished(record: FeatureVerifyFinishedRecord, level: String) {
    if (saveFeatureVerifyFinished(connection, record) == TerminalSaveOutcome.FIRST_TERMINAL) {
      emitFeatureVerifyFinished(connection, record.sessionId, level)
    }
  }
}
