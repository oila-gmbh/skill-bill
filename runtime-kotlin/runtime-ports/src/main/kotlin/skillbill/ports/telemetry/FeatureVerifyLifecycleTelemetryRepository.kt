package skillbill.ports.telemetry

import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord

interface FeatureVerifyLifecycleTelemetryRepository {
  fun featureVerifyStarted(record: FeatureVerifyStartedRecord, level: String)

  fun featureVerifyFinished(record: FeatureVerifyFinishedRecord, level: String)
}
