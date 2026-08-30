package skillbill.db.telemetry

import skillbill.ports.telemetry.FeatureTaskRuntimeLifecycleTelemetryRepository
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import java.sql.Connection

internal class LifecycleTelemetryFeatureTaskSessionAdapter(
  private val connection: Connection,
) : FeatureTaskRuntimeLifecycleTelemetryRepository {
  override fun featureTaskRuntimeStarted(record: FeatureTaskRuntimeStartedRecord, level: String) {
    saveFeatureTaskRuntimeStarted(connection, record)
    emitFeatureTaskRuntimeStarted(connection, record.sessionId, level)
  }

  override fun featureTaskRuntimeFinished(record: FeatureTaskRuntimeFinishedRecord, level: String) {
    if (saveFeatureTaskRuntimeFinished(connection, record) == TerminalSaveOutcome.FIRST_TERMINAL) {
      emitFeatureTaskRuntimeFinished(connection, record.sessionId, level)
    }
  }
}
