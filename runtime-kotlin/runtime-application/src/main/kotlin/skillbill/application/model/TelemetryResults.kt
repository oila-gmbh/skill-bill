package skillbill.application.model

data class TelemetryStatusResult(
  val configPath: String,
  val dbPath: String,
  val telemetryEnabled: Boolean,
  val telemetryLevel: String,
  val syncTarget: String,
  val remoteConfigured: Boolean,
  val proxyConfigured: Boolean,
  val proxyUrl: String,
  val customProxyUrl: String?,
  val pendingEvents: Int,
  val installId: String? = null,
  val batchSize: Int? = null,
  val latestError: String? = null,
)

data class TelemetrySyncStatusResult(
  val configPath: String,
  val telemetryEnabled: Boolean,
  val telemetryLevel: String,
  val syncTarget: String,
  val remoteConfigured: Boolean,
  val proxyConfigured: Boolean,
  val proxyUrl: String,
  val customProxyUrl: String?,
  val syncStatus: String,
  val syncedEvents: Int,
  val pendingEvents: Int,
  val message: String? = null,
)

data class TelemetryMutationResult(
  val configPath: String,
  val telemetryEnabled: Boolean,
  val telemetryLevel: String,
  val syncTarget: String,
  val remoteConfigured: Boolean,
  val proxyConfigured: Boolean,
  val proxyUrl: String,
  val customProxyUrl: String?,
  val installId: String,
  val clearedEvents: Int,
)
