package skillbill.ports.telemetry

import skillbill.ports.persistence.model.TelemetryOutboxRecord
import skillbill.telemetry.model.RemoteStatsRequest
import skillbill.telemetry.model.TelemetrySettings

interface TelemetryClient {
  fun sendBatch(settings: TelemetrySettings, rows: List<TelemetryOutboxRecord>)

  fun fetchProxyCapabilities(settings: TelemetrySettings): Map<String, Any?>

  fun fetchRemoteStats(settings: TelemetrySettings, request: RemoteStatsRequest): Map<String, Any?>
}
