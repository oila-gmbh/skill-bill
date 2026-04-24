package skillbill.telemetry

import skillbill.contracts.JsonSupport
import skillbill.db.TelemetryOutboxRow
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers

object TelemetryHttpRuntime {
  val defaultHttpRequester: HttpRequester =
    HttpRequester { method, url, bodyJson, headers ->
      val requestBuilder =
        HttpRequest
          .newBuilder(URI.create(url))
          .method(method, bodyPublisher(bodyJson))
      headers.forEach(requestBuilder::header)
      val response = HttpClient.newHttpClient().send(requestBuilder.build(), BodyHandlers.ofString())
      HttpResponse(statusCode = response.statusCode(), body = response.body().orEmpty())
    }

  fun buildTelemetryBatch(settings: TelemetrySettings, rows: List<TelemetryOutboxRow>): List<Map<String, Any?>> =
    rows.map { row ->
      val properties = telemetryProperties(row.payloadJson, settings.installId)
      mapOf(
        "event" to row.eventName,
        "distinct_id" to settings.installId,
        "properties" to properties,
        "timestamp" to row.createdAt,
      )
    }

  fun fetchProxyCapabilities(
    settings: TelemetrySettings = TelemetryConfigRuntime.loadTelemetrySettings(),
    requester: HttpRequester = defaultHttpRequester,
    environment: Map<String, String> = System.getenv(),
  ): Map<String, Any?> {
    require(settings.proxyUrl.isNotBlank()) { "Telemetry relay URL is not configured." }
    val capabilitiesUrl = settings.proxyUrl.trimEnd('/') + "/capabilities"
    return try {
      requestJsonGet(
        url = capabilitiesUrl,
        errorContext = "Telemetry proxy capabilities request",
        headers = proxyAuthHeaders(environment),
        requester = requester,
      ).toMutableMap().apply {
        putIfAbsent("contract_version", TELEMETRY_PROXY_CONTRACT_VERSION)
        putIfAbsent("source", "remote_proxy")
        putIfAbsent("proxy_url", settings.proxyUrl)
        putIfAbsent("capabilities_url", capabilitiesUrl)
        putIfAbsent("supports_ingest", true)
        putIfAbsent("supports_stats", false)
        putIfAbsent("supported_workflows", emptyList<String>())
      }
    } catch (error: HttpFailureException) {
      if (error.statusCode == HTTP_NOT_FOUND || error.statusCode == HTTP_METHOD_NOT_ALLOWED) {
        defaultProxyCapabilities(settings.proxyUrl, capabilitiesUrl)
      } else {
        throw IllegalArgumentException(
          error.message ?: "Telemetry proxy capabilities request failed.",
          error,
        )
      }
    }
  }

  fun sendProxyBatch(
    settings: TelemetrySettings,
    rows: List<TelemetryOutboxRow>,
    requester: HttpRequester = defaultHttpRequester,
  ) {
    require(settings.proxyUrl.isNotBlank()) { "Telemetry relay URL is not configured." }
    val payload = mapOf("batch" to buildTelemetryBatch(settings, rows))
    val errorContext =
      if (settings.customProxyUrl != null) {
        "Telemetry custom proxy sync"
      } else {
        "Telemetry relay sync"
      }
    postJson(
      url = settings.proxyUrl,
      payload = payload,
      errorContext = errorContext,
      requester = requester,
    )
  }
}

internal fun requestJson(
  url: String,
  payload: Map<String, Any?>,
  errorContext: String,
  headers: Map<String, String>,
  requester: HttpRequester,
): Map<String, Any?> {
  val response =
    requester.execute(
      "POST",
      url,
      JsonSupport.mapToJsonString(payload),
      defaultJsonHeaders() + headers,
    )
  ensureSuccessfulResponse(response, errorContext)
  return decodeJsonObject(response.body, errorContext)
}

internal fun requestJsonGet(
  url: String,
  errorContext: String,
  headers: Map<String, String>,
  requester: HttpRequester,
): Map<String, Any?> {
  val response =
    requester.execute(
      "GET",
      url,
      null,
      mapOf("User-Agent" to "skill-bill-telemetry/1.0") + headers,
    )
  ensureSuccessfulResponse(response, errorContext)
  return decodeJsonObject(response.body, errorContext)
}

private fun postJson(url: String, payload: Map<String, Any?>, errorContext: String, requester: HttpRequester) {
  val response =
    requester.execute(
      "POST",
      url,
      JsonSupport.mapToJsonString(payload),
      defaultJsonHeaders(),
    )
  ensureSuccessfulResponse(response, errorContext)
}

private fun ensureSuccessfulResponse(response: HttpResponse, errorContext: String) {
  if (response.statusCode !in HTTP_OK_MIN..HTTP_OK_MAX) {
    throw HttpFailureException(
      response.statusCode,
      httpFailureMessage(response, errorContext),
    )
  }
}

private fun decodeJsonObject(body: String, errorContext: String): Map<String, Any?> {
  if (body.isBlank()) {
    return emptyMap()
  }
  val decoded =
    JsonSupport.parseObjectOrNull(body)
      ?: throw IllegalArgumentException("$errorContext returned invalid JSON.")
  return JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(decoded))
    ?: throw IllegalArgumentException(
      "$errorContext returned a non-object JSON payload.",
    )
}

private fun httpFailureMessage(response: HttpResponse, errorContext: String): String = if (response.body.isBlank()) {
  "$errorContext failed with HTTP ${response.statusCode}."
} else {
  "$errorContext failed with HTTP ${response.statusCode}. ${response.body.trim()}"
}

internal fun proxyAuthHeaders(environment: Map<String, String>): Map<String, String> =
  environment[TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let { mapOf("Authorization" to "Bearer $it") }
    ?: emptyMap()

private fun defaultJsonHeaders(): Map<String, String> = mapOf(
  "Content-Type" to "application/json",
  "User-Agent" to "skill-bill-telemetry/1.0",
)

internal class HttpFailureException(
  val statusCode: Int,
  message: String,
) : IllegalArgumentException(message)
