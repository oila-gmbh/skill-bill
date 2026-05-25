package skillbill.telemetry

import skillbill.application.model.TelemetryStatusResult
import skillbill.telemetry.model.SyncResult
import skillbill.telemetry.model.TelemetrySettings
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

internal fun baseStatusResult(dbPath: Path, settings: TelemetrySettings): TelemetryStatusResult = TelemetryStatusResult(
  configPath = settings.configPath.toString(),
  dbPath = dbPath.toString(),
  telemetryEnabled = settings.enabled,
  telemetryLevel = settings.level,
  syncTarget = telemetrySyncTarget(settings),
  remoteConfigured = settings.proxyUrl.isNotBlank(),
  proxyConfigured = settings.customProxyUrl != null,
  proxyUrl = settings.proxyUrl,
  customProxyUrl = settings.customProxyUrl,
  pendingEvents = 0,
  installId = settings.installId.takeIf { settings.enabled },
  batchSize = settings.batchSize.takeIf { settings.enabled },
)

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
