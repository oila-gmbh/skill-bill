package skillbill.contracts.telemetry

data class TelemetryProxyBatchEvent(
  val event: String,
  val distinctId: String,
  val properties: Map<String, Any?>,
  val timestamp: String,
) {
  fun toPayload(): Map<String, Any?> = mapOf(
    "event" to event,
    "distinct_id" to distinctId,
    "properties" to properties,
    "timestamp" to timestamp,
  )
}

data class TelemetryProxyBatchPayload(
  val batch: List<TelemetryProxyBatchEvent>,
) {
  fun toPayload(): Map<String, Any?> = mapOf("batch" to batch.map { it.toPayload() })
}

data class RemoteStatsQueryPayload(
  val workflow: String,
  val dateFrom: String,
  val dateTo: String,
  val groupBy: String = "",
) {
  fun toPayload(): Map<String, Any?> = buildMap {
    put("workflow", workflow)
    put("date_from", dateFrom)
    put("date_to", dateTo)
    if (groupBy.isNotBlank()) {
      put("group_by", groupBy)
    }
  }
}

fun defaultProxyCapabilities(proxyUrl: String, capabilitiesUrl: String): Map<String, Any?> = mapOf(
  "contract_version" to "0",
  "source" to "remote_proxy",
  "proxy_url" to proxyUrl,
  "capabilities_url" to capabilitiesUrl,
  "supports_ingest" to true,
  "supports_stats" to false,
  "supported_workflows" to emptyList<String>(),
)
