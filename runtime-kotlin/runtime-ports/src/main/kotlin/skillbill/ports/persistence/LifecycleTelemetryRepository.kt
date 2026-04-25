package skillbill.ports.persistence

import skillbill.telemetry.model.FeatureImplementFinishedRecord
import skillbill.telemetry.model.FeatureImplementStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import skillbill.telemetry.model.PrDescriptionGeneratedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord

interface LifecycleTelemetryRepository {
  fun featureImplementStarted(record: FeatureImplementStartedRecord, level: String)

  fun featureImplementFinished(record: FeatureImplementFinishedRecord, level: String)

  fun qualityCheckStarted(record: QualityCheckStartedRecord, level: String)

  fun qualityCheckFinished(record: QualityCheckFinishedRecord, level: String)

  fun featureVerifyStarted(record: FeatureVerifyStartedRecord, level: String)

  fun featureVerifyFinished(record: FeatureVerifyFinishedRecord, level: String)

  fun prDescriptionGenerated(record: PrDescriptionGeneratedRecord, level: String)
}
