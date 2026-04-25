package skillbill.telemetry

import skillbill.ports.persistence.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.TelemetryClient
import skillbill.telemetry.model.RemoteStatsRequest
import skillbill.telemetry.model.TelemetrySettings

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
