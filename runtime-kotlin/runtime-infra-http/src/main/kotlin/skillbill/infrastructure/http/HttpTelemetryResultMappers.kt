package skillbill.infrastructure.http

import skillbill.telemetry.model.TelemetryProxyCapabilities
import skillbill.telemetry.model.TelemetryRemoteStatsResult

internal fun Map<String, Any?>.toTelemetryProxyCapabilities(): TelemetryProxyCapabilities {
  val supportedWorkflows =
    (this["supported_workflows"] as? List<*>)
      ?.filterIsInstance<String>()
      .orEmpty()
  val knownKeys =
    setOf(
      "contract_version",
      "source",
      "proxy_url",
      "capabilities_url",
      "supports_ingest",
      "supports_stats",
      "supported_workflows",
    )
  return TelemetryProxyCapabilities(
    contractVersion = this["contract_version"]?.toString().orEmpty(),
    source = this["source"]?.toString().orEmpty(),
    proxyUrl = this["proxy_url"]?.toString().orEmpty(),
    capabilitiesUrl = this["capabilities_url"]?.toString().orEmpty(),
    supportsIngest = this["supports_ingest"] == true,
    supportsStats = this["supports_stats"] == true,
    supportedWorkflows = supportedWorkflows,
    additionalFields = filterKeys { key -> key !in knownKeys },
  )
}

internal fun Map<String, Any?>.toTelemetryRemoteStatsResult(
  capabilities: TelemetryProxyCapabilities,
  preserveResponseCapabilities: Boolean,
): TelemetryRemoteStatsResult {
  val knownKeys =
    setOf("workflow", "date_from", "date_to", "source", "stats_url", "group_by")
      .let { keys -> if (preserveResponseCapabilities) keys else keys + "capabilities" }
  return TelemetryRemoteStatsResult(
    workflow = this["workflow"]?.toString().orEmpty(),
    dateFrom = this["date_from"]?.toString().orEmpty(),
    dateTo = this["date_to"]?.toString().orEmpty(),
    source = this["source"]?.toString().orEmpty(),
    statsUrl = this["stats_url"]?.toString().orEmpty(),
    groupBy = this["group_by"]?.toString(),
    capabilities = capabilities,
    metrics = filterKeys { key -> key !in knownKeys },
  )
}
