package skillbill.telemetry

import skillbill.contracts.JsonSupport
import java.net.http.HttpRequest

internal const val HTTP_NOT_FOUND: Int = 404
internal const val HTTP_METHOD_NOT_ALLOWED: Int = 405

internal fun telemetryProperties(payloadJson: String, installId: String): MutableMap<String, Any?> = (
  JsonSupport.parseObjectOrNull(payloadJson)?.let {
    JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(it))
  } ?: emptyMap()
  ).toMutableMap().apply {
  this["install_id"] = installId
  this["\$process_person_profile"] = false
}

internal fun defaultProxyCapabilities(proxyUrl: String, capabilitiesUrl: String): Map<String, Any?> = mapOf(
  "contract_version" to "0",
  "source" to "remote_proxy",
  "proxy_url" to proxyUrl,
  "capabilities_url" to capabilitiesUrl,
  "supports_ingest" to true,
  "supports_stats" to false,
  "supported_workflows" to emptyList<String>(),
)

internal fun bodyPublisher(bodyJson: String?): HttpRequest.BodyPublisher = if (bodyJson == null) {
  HttpRequest.BodyPublishers.noBody()
} else {
  HttpRequest.BodyPublishers.ofString(bodyJson)
}
