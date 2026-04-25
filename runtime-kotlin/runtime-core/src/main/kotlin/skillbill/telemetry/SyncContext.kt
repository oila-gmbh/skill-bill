package skillbill.telemetry

import java.nio.file.Path

internal data class SyncContext(
  val settings: TelemetrySettings,
  val pendingEvents: Int,
  val remoteConfigured: Boolean,
  val proxyConfigured: Boolean,
  val syncTarget: String,
)

internal fun syncContext(settings: TelemetrySettings, pendingEvents: Int): SyncContext = SyncContext(
  settings = settings,
  pendingEvents = pendingEvents,
  remoteConfigured = settings.proxyUrl.isNotBlank(),
  proxyConfigured = settings.customProxyUrl != null,
  syncTarget = telemetrySyncTarget(settings),
)

internal fun telemetrySyncTarget(result: SyncResult): String = when {
  !result.telemetryEnabled -> "disabled"
  result.customProxyUrl != null -> "custom_proxy"
  else -> "hosted_relay"
}

internal fun syncResult(
  status: String,
  syncedEvents: Int,
  pendingEvents: Int,
  syncContext: SyncContext,
  message: String? = null,
): SyncResult = SyncResult(
  status = status,
  syncedEvents = syncedEvents,
  pendingEvents = pendingEvents,
  configPath = syncContext.settings.configPath,
  telemetryEnabled = syncContext.settings.enabled,
  telemetryLevel = syncContext.settings.level,
  remoteConfigured = syncContext.remoteConfigured,
  proxyConfigured = syncContext.proxyConfigured,
  syncTarget = syncContext.syncTarget,
  proxyUrl = syncContext.settings.proxyUrl,
  customProxyUrl = syncContext.settings.customProxyUrl,
  message = message,
)

internal fun baseStatusPayload(dbPath: Path, settings: TelemetrySettings): MutableMap<String, Any?> =
  mutableMapOf<String, Any?>(
    "config_path" to settings.configPath.toString(),
    "db_path" to dbPath.toString(),
    "telemetry_enabled" to settings.enabled,
    "telemetry_level" to settings.level,
    "sync_target" to telemetrySyncTarget(settings),
    "remote_configured" to settings.proxyUrl.isNotBlank(),
    "proxy_configured" to (settings.customProxyUrl != null),
    "proxy_url" to settings.proxyUrl,
    "custom_proxy_url" to settings.customProxyUrl,
    "pending_events" to 0,
  ).apply {
    if (settings.enabled) {
      this["install_id"] = settings.installId
      this["batch_size"] = settings.batchSize
    }
  }

internal fun disabledSyncResult(settings: TelemetrySettings): SyncResult = syncResult(
  status = "disabled",
  syncedEvents = 0,
  pendingEvents = 0,
  syncContext = syncContext(settings, pendingEvents = 0),
  message = "Telemetry is disabled.",
)

internal fun unconfiguredSyncResult(syncContext: SyncContext): SyncResult = syncResult(
  status = "unconfigured",
  syncedEvents = 0,
  pendingEvents = syncContext.pendingEvents,
  syncContext = syncContext,
  message = "Telemetry relay URL is not configured.",
)

internal fun noopSyncResult(syncContext: SyncContext): SyncResult = syncResult(
  status = "noop",
  syncedEvents = 0,
  pendingEvents = 0,
  syncContext = syncContext,
  message = "No pending telemetry events.",
)

internal fun completedSyncResult(syncContext: SyncContext, syncedTotal: Int, pendingEvents: Int): SyncResult =
  syncResult(
    status = "synced",
    syncedEvents = syncedTotal,
    pendingEvents = pendingEvents,
    syncContext = syncContext,
  )
