package skillbill.telemetry.http

import skillbill.ports.persistence.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.TelemetryClient
import skillbill.telemetry.model.RemoteStatsRequest
import skillbill.telemetry.model.TelemetryProxyCapabilities
import skillbill.telemetry.model.TelemetryRemoteStatsResult
import skillbill.telemetry.model.TelemetrySettings

object TelemetryHttpRuntime {
  fun fetchProxyCapabilities(settings: TelemetrySettings, client: TelemetryClient): TelemetryProxyCapabilities =
    client.fetchProxyCapabilities(settings)

  fun fetchRemoteStats(
    settings: TelemetrySettings,
    request: RemoteStatsRequest,
    client: TelemetryClient,
  ): TelemetryRemoteStatsResult = client.fetchRemoteStats(settings, request)

  fun sendProxyBatch(settings: TelemetrySettings, rows: List<TelemetryOutboxRecord>, client: TelemetryClient) =
    client.sendBatch(settings, rows)
}
