package skillbill.db.telemetry

import skillbill.ports.telemetry.FeatureTaskRuntimeLifecycleTelemetryRepository
import skillbill.ports.telemetry.FeatureTaskRuntimeTelemetryMeasurementRepository
import skillbill.ports.telemetry.FeatureVerifyLifecycleTelemetryRepository
import skillbill.ports.telemetry.GoalLifecycleTelemetryRepository
import skillbill.ports.telemetry.LifecycleTelemetryRepository
import skillbill.ports.telemetry.PrDescriptionLifecycleTelemetryRepository
import skillbill.ports.telemetry.QualityCheckLifecycleTelemetryRepository
import skillbill.ports.telemetry.ReviewStageTelemetryMeasurementRepository
import java.sql.Connection

class LifecycleTelemetryStore private constructor(
  adapters: LifecycleTelemetryStoreAdapters,
) : LifecycleTelemetryRepository,
  FeatureTaskRuntimeTelemetryMeasurementRepository by adapters.measurements,
  ReviewStageTelemetryMeasurementRepository by adapters.measurements,
  FeatureTaskRuntimeLifecycleTelemetryRepository by adapters.featureTaskSessions,
  QualityCheckLifecycleTelemetryRepository by adapters.qualityCheckSessions,
  FeatureVerifyLifecycleTelemetryRepository by adapters.featureVerifySessions,
  PrDescriptionLifecycleTelemetryRepository by adapters.prDescriptionSessions,
  GoalLifecycleTelemetryRepository by adapters.goalSessions {
  companion object {
    operator fun invoke(connection: Connection): LifecycleTelemetryStore =
      LifecycleTelemetryStore(LifecycleTelemetryStoreAdapters(connection))
  }
}
