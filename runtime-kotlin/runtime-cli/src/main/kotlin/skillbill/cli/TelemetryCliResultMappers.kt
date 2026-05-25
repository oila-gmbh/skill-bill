package skillbill.cli

import skillbill.application.model.TelemetryMutationResult
import skillbill.application.model.TelemetryStatusResult
import skillbill.application.model.TelemetrySyncStatusResult
import skillbill.telemetry.model.TelemetryProxyCapabilities
import skillbill.telemetry.model.TelemetryRemoteStatsResult

internal fun TelemetryStatusResult.toCliMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
  "config_path" to configPath,
  "db_path" to dbPath,
  "telemetry_enabled" to telemetryEnabled,
  "telemetry_level" to telemetryLevel,
  "sync_target" to syncTarget,
  "remote_configured" to remoteConfigured,
  "proxy_configured" to proxyConfigured,
  "proxy_url" to proxyUrl,
  "custom_proxy_url" to customProxyUrl,
  "pending_events" to pendingEvents,
).apply {
  installId?.let { put("install_id", it) }
  batchSize?.let { put("batch_size", it) }
  latestError?.let { put("latest_error", it) }
}

internal fun TelemetrySyncStatusResult.toCliMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
  "config_path" to configPath,
  "telemetry_enabled" to telemetryEnabled,
  "telemetry_level" to telemetryLevel,
  "sync_target" to syncTarget,
  "remote_configured" to remoteConfigured,
  "proxy_configured" to proxyConfigured,
  "proxy_url" to proxyUrl,
  "custom_proxy_url" to customProxyUrl,
  "sync_status" to syncStatus,
  "synced_events" to syncedEvents,
  "pending_events" to pendingEvents,
).apply {
  message?.let { put("message", it) }
}

internal fun TelemetryMutationResult.toCliMap(): Map<String, Any?> = linkedMapOf(
  "config_path" to configPath,
  "telemetry_enabled" to telemetryEnabled,
  "telemetry_level" to telemetryLevel,
  "sync_target" to syncTarget,
  "remote_configured" to remoteConfigured,
  "proxy_configured" to proxyConfigured,
  "proxy_url" to proxyUrl,
  "custom_proxy_url" to customProxyUrl,
  "install_id" to installId,
  "cleared_events" to clearedEvents,
)

internal fun TelemetryProxyCapabilities.toCliMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
  "contract_version" to contractVersion,
  "source" to source,
  "proxy_url" to proxyUrl,
  "capabilities_url" to capabilitiesUrl,
  "supports_ingest" to supportsIngest,
  "supports_stats" to supportsStats,
  "supported_workflows" to supportedWorkflows,
).apply {
  additionalFields.forEach { (key, value) -> putIfAbsent(key, value) }
}

internal fun TelemetryRemoteStatsResult.toCliMap(): Map<String, Any?> = LinkedHashMap(metrics).apply {
  putIfAbsent("workflow", workflow)
  putIfAbsent("date_from", dateFrom)
  putIfAbsent("date_to", dateTo)
  putIfAbsent("source", source)
  putIfAbsent("stats_url", statsUrl)
  if (!containsKey("capabilities")) {
    put("capabilities", capabilities.toCliMap())
  }
  groupBy?.takeIf(String::isNotBlank)?.let { putIfAbsent("group_by", it) }
}
