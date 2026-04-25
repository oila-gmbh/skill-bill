package skillbill.telemetry

import skillbill.contracts.telemetry.telemetryProxyBatchPayload
import skillbill.infrastructure.http.HttpTelemetryClient
import skillbill.infrastructure.http.JdkHttpRequester
import skillbill.ports.persistence.TelemetryOutboxRecord

object TelemetryHttpRuntime {
  val defaultHttpRequester: HttpRequester = JdkHttpRequester

  fun buildTelemetryBatch(settings: TelemetrySettings, rows: List<TelemetryOutboxRecord>): List<Map<String, Any?>> =
    telemetryProxyBatchPayload(settings, rows).batch.map { it.toPayload() }

  fun fetchProxyCapabilities(
    settings: TelemetrySettings = TelemetryConfigRuntime.loadTelemetrySettings(),
    requester: HttpRequester = defaultHttpRequester,
    environment: Map<String, String> = System.getenv(),
  ): Map<String, Any?> = HttpTelemetryClient(requester, environment).fetchProxyCapabilities(settings)

  fun sendProxyBatch(
    settings: TelemetrySettings,
    rows: List<TelemetryOutboxRecord>,
    requester: HttpRequester = defaultHttpRequester,
  ) {
    HttpTelemetryClient(requester).sendBatch(settings, rows)
  }
}
