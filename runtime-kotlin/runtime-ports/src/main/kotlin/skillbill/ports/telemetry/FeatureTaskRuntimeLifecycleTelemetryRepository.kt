package skillbill.ports.telemetry

import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord

interface FeatureTaskRuntimeLifecycleTelemetryRepository {
  fun featureTaskRuntimeStarted(record: FeatureTaskRuntimeStartedRecord, level: String)

  fun featureTaskRuntimeFinished(record: FeatureTaskRuntimeFinishedRecord, level: String)
}
