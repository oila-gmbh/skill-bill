package skillbill.mcp

import skillbill.telemetry.model.TelemetryProxyCapabilities
import skillbill.telemetry.model.TelemetryRemoteStatsResult

internal fun TelemetryProxyCapabilities.toMcpMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
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

internal fun TelemetryRemoteStatsResult.toMcpMap(): Map<String, Any?> = LinkedHashMap(metrics).apply {
  putIfAbsent("workflow", workflow)
  putIfAbsent("date_from", dateFrom)
  putIfAbsent("date_to", dateTo)
  putIfAbsent("source", source)
  putIfAbsent("stats_url", statsUrl)
  if (!containsKey("capabilities")) {
    put("capabilities", capabilities.toMcpMap())
  }
  groupBy?.takeIf(String::isNotBlank)?.let { putIfAbsent("group_by", it) }
}
