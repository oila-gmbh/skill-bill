package skillbill.telemetry

import java.nio.file.Path

typealias HttpResponse = skillbill.ports.telemetry.HttpResponse

typealias HttpRequester = skillbill.ports.telemetry.HttpRequester

data class TelemetrySettings(
  val configPath: Path,
  val level: String,
  val enabled: Boolean,
  val installId: String,
  val proxyUrl: String,
  val customProxyUrl: String?,
  val batchSize: Int,
)

data class SyncResult(
  val status: String,
  val syncedEvents: Int,
  val pendingEvents: Int,
  val configPath: Path,
  val telemetryEnabled: Boolean,
  val telemetryLevel: String,
  val remoteConfigured: Boolean,
  val proxyConfigured: Boolean,
  val syncTarget: String,
  val proxyUrl: String,
  val customProxyUrl: String? = null,
  val message: String? = null,
)
