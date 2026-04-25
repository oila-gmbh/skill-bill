package skillbill.telemetry

import skillbill.ports.persistence.TelemetryOutboxRecord
import skillbill.ports.telemetry.TelemetryClient

object TelemetryHttpRuntime {
  fun fetchProxyCapabilities(settings: TelemetrySettings, client: TelemetryClient): Map<String, Any?> =
    client.fetchProxyCapabilities(settings)

  fun fetchRemoteStats(
    settings: TelemetrySettings,
    request: RemoteStatsRequest,
    client: TelemetryClient,
  ): Map<String, Any?> = client.fetchRemoteStats(settings, request)

  fun sendProxyBatch(settings: TelemetrySettings, rows: List<TelemetryOutboxRecord>, client: TelemetryClient) =
    client.sendBatch(settings, rows)
}
