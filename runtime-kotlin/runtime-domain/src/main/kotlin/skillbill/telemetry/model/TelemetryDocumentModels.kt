package skillbill.telemetry.model

import skillbill.boundary.OpenBoundaryMap

/**
 * Open JSON document for the local telemetry config file. The config is an extension point
 * because operators may add non-runtime keys; runtime code reads only the documented telemetry
 * keys from [payload].
 */
data class TelemetryConfigDocument(
  @OpenBoundaryMap("Open local telemetry config JSON document")
  val payload: Map<String, Any?>,
)

data class TelemetryProxyCapabilities(
  val contractVersion: String,
  val source: String,
  val proxyUrl: String,
  val capabilitiesUrl: String,
  val supportsIngest: Boolean,
  val supportsStats: Boolean,
  val supportedWorkflows: List<String>,
  @OpenBoundaryMap("Additional remote telemetry proxy capability fields")
  val additionalFields: Map<String, Any?> = emptyMap(),
)

data class TelemetryRemoteStatsResult(
  val workflow: String,
  val dateFrom: String,
  val dateTo: String,
  val source: String,
  val statsUrl: String,
  val groupBy: String?,
  val capabilities: TelemetryProxyCapabilities,
  @OpenBoundaryMap("Remote telemetry stats response metrics document")
  val metrics: Map<String, Any?>,
)
