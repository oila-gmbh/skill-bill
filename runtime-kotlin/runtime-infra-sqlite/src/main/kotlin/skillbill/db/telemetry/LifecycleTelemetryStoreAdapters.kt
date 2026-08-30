package skillbill.db.telemetry

import java.sql.Connection

internal class LifecycleTelemetryStoreAdapters(connection: Connection) {
  val measurements = LifecycleTelemetryMeasurementAdapter(connection)
  val featureTaskSessions = LifecycleTelemetryFeatureTaskSessionAdapter(connection)
  val qualityCheckSessions = LifecycleTelemetryQualityCheckSessionAdapter(connection)
  val featureVerifySessions = LifecycleTelemetryFeatureVerifySessionAdapter(connection)
  val prDescriptionSessions = LifecycleTelemetryPrDescriptionSessionAdapter(connection)
  val goalSessions = LifecycleTelemetryGoalSessionAdapter(connection)
}
