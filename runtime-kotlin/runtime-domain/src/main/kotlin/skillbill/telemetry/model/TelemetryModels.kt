package skillbill.telemetry.model

import skillbill.model.FileLocation

data class TelemetrySettings(
  val configPath: FileLocation,
  val level: String,
  val enabled: Boolean,
  val installId: String,
  val proxyUrl: String,
  val customProxyUrl: String?,
  val batchSize: Int,
)

data class SyncResult(
  val status: TelemetrySyncStatus,
  val syncedEvents: Int,
  val pendingEvents: Int,
  val configPath: FileLocation,
  val telemetryEnabled: Boolean,
  val telemetryLevel: String,
  val remoteConfigured: Boolean,
  val proxyConfigured: Boolean,
  val syncTarget: String,
  val proxyUrl: String,
  val customProxyUrl: String? = null,
  val message: String? = null,
)
