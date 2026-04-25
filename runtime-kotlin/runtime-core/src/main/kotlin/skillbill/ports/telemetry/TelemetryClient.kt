package skillbill.ports.telemetry

import skillbill.ports.persistence.TelemetryOutboxRecord
import skillbill.telemetry.RemoteStatsRequest
import skillbill.telemetry.TelemetrySettings

interface TelemetryClient {
  fun sendBatch(settings: TelemetrySettings, rows: List<TelemetryOutboxRecord>)

  fun fetchProxyCapabilities(settings: TelemetrySettings): Map<String, Any?>

  fun fetchRemoteStats(settings: TelemetrySettings, request: RemoteStatsRequest): Map<String, Any?>
}
