package skillbill.db

import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.telemetry.model.FeatureImplementFinishedRecord
import skillbill.telemetry.model.FeatureImplementStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import skillbill.telemetry.model.PrDescriptionGeneratedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord
import java.sql.Connection

class LifecycleTelemetryStore(
  private val connection: Connection,
) : LifecycleTelemetryRepository {
  override fun featureImplementStarted(record: FeatureImplementStartedRecord, level: String) {
    saveFeatureImplementStarted(connection, record)
    emitFeatureImplementStarted(connection, record.sessionId, level)
  }

  override fun featureImplementFinished(record: FeatureImplementFinishedRecord, level: String) {
    saveFeatureImplementFinished(connection, record)
    emitFeatureImplementFinished(connection, record.sessionId, level)
  }

  override fun qualityCheckStarted(record: QualityCheckStartedRecord, level: String) {
    saveQualityCheckStarted(connection, record)
    emitQualityCheckStarted(connection, record.sessionId)
  }

  override fun qualityCheckFinished(record: QualityCheckFinishedRecord, level: String) {
    saveQualityCheckFinished(connection, record)
    emitQualityCheckFinished(connection, record.sessionId, level)
  }

  override fun featureVerifyStarted(record: FeatureVerifyStartedRecord, level: String) {
    saveFeatureVerifyStarted(connection, record)
    emitFeatureVerifyStarted(connection, record.sessionId, level)
  }

  override fun featureVerifyFinished(record: FeatureVerifyFinishedRecord, level: String) {
    saveFeatureVerifyFinished(connection, record)
    emitFeatureVerifyFinished(connection, record.sessionId, level)
  }

  override fun prDescriptionGenerated(record: PrDescriptionGeneratedRecord, level: String) {
    enqueueTelemetry(connection, "skillbill_pr_description_generated", prDescriptionPayload(record, level))
  }
}
