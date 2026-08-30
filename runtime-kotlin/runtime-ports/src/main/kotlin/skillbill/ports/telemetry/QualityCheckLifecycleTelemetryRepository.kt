package skillbill.ports.telemetry

import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord

interface QualityCheckLifecycleTelemetryRepository {
  fun qualityCheckStarted(record: QualityCheckStartedRecord, level: String)

  fun qualityCheckFinished(record: QualityCheckFinishedRecord, level: String)
}
