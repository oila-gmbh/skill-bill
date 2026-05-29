package skillbill.infrastructure.http

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonSupport
import skillbill.contracts.telemetry.RemoteStatsQueryPayload
import skillbill.contracts.telemetry.defaultProxyCapabilities
import skillbill.model.RuntimeContext
import skillbill.ports.persistence.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.TelemetryClient
import skillbill.ports.telemetry.UnconfiguredHttpRequester
import skillbill.ports.telemetry.model.HttpResponse
import skillbill.telemetry.TELEMETRY_PROXY_CONTRACT_VERSION
import skillbill.telemetry.TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY
import skillbill.telemetry.model.RemoteStatsRequest
import skillbill.telemetry.model.TelemetryProxyCapabilities
import skillbill.telemetry.model.TelemetryRemoteStatsResult
import skillbill.telemetry.model.TelemetrySettings
import skillbill.telemetry.parseRemoteStatsWindow
import skillbill.telemetry.validateRemoteStatsCapabilities
import skillbill.telemetry.validateRemoteStatsRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

object JdkHttpRequester : HttpRequester {
  override fun execute(method: String, url: String, bodyJson: String?, headers: Map<String, String>): HttpResponse {
    val requestBuilder =
      HttpRequest
        .newBuilder(URI.create(url))
        .method(method, bodyPublisher(bodyJson))
    headers.forEach(requestBuilder::header)
    val response = HttpClient.newHttpClient().send(requestBuilder.build(), BodyHandlers.ofString())
    return HttpResponse(statusCode = response.statusCode(), body = response.body().orEmpty())
  }
}

@Inject
class HttpTelemetryClient(
  private val context: RuntimeContext,
) : TelemetryClient {
  private val resolvedContext = context.withProcessDefaults()

  constructor(
    requester: HttpRequester,
    environment: Map<String, String> = System.getenv(),
  ) : this(requester, environment, Path.of(System.getProperty("user.home")))

  constructor(
    requester: HttpRequester,
    environment: Map<String, String>,
    userHome: Path,
  ) : this(RuntimeContext(environment = environment, userHome = userHome, requester = requester))

  override fun sendBatch(settings: TelemetrySettings, rows: List<TelemetryOutboxRecord>) {
    require(settings.proxyUrl.isNotBlank()) { "Telemetry relay URL is not configured." }
    val errorContext =
      if (settings.customProxyUrl != null) {
        "Telemetry custom proxy sync"
      } else {
        "Telemetry relay sync"
      }
    postJson(
      url = settings.proxyUrl,
      payload = telemetryProxyBatchPayload(settings, rows).toPayload(),
      errorContext = errorContext,
      requester = resolvedContext.requester,
    )
  }

  override fun fetchProxyCapabilities(settings: TelemetrySettings): TelemetryProxyCapabilities {
    require(settings.proxyUrl.isNotBlank()) { "Telemetry relay URL is not configured." }
    val capabilitiesUrl = settings.proxyUrl.trimEnd('/') + "/capabilities"
    return try {
      requestJsonGet(
        url = capabilitiesUrl,
        errorContext = "Telemetry proxy capabilities request",
        headers = proxyAuthHeaders(resolvedContext.environment),
        requester = resolvedContext.requester,
      ).toMutableMap().apply {
        putIfAbsent("contract_version", TELEMETRY_PROXY_CONTRACT_VERSION)
        putIfAbsent("source", "remote_proxy")
        putIfAbsent("proxy_url", settings.proxyUrl)
        putIfAbsent("capabilities_url", capabilitiesUrl)
        putIfAbsent("supports_ingest", true)
        putIfAbsent("supports_stats", false)
        putIfAbsent("supported_workflows", emptyList<String>())
      }.toTelemetryProxyCapabilities()
    } catch (error: HttpFailureException) {
      if (error.statusCode == HTTP_NOT_FOUND || error.statusCode == HTTP_METHOD_NOT_ALLOWED) {
        defaultProxyCapabilities(settings.proxyUrl, capabilitiesUrl).toTelemetryProxyCapabilities()
      } else {
        throw IllegalArgumentException(
          error.message ?: "Telemetry proxy capabilities request failed.",
          error,
        )
      }
    }
  }

  override fun fetchRemoteStats(settings: TelemetrySettings, request: RemoteStatsRequest): TelemetryRemoteStatsResult {
    validateRemoteStatsRequest(request)
    require(settings.proxyUrl.isNotBlank()) {
      "Telemetry relay URL is not configured."
    }
    val (resolvedDateFrom, resolvedDateTo) =
      parseRemoteStatsWindow(request.since, request.dateFrom, request.dateTo, LocalDate.now(ZoneOffset.UTC))
    val capabilities = fetchProxyCapabilities(settings)
    validateRemoteStatsCapabilities(
      request = request,
      settings = settings,
      capabilities = capabilities,
    )
    val statsUrl = settings.proxyUrl.trimEnd('/') + "/stats"
    val payload =
      requestJson(
        url = statsUrl,
        payload =
        RemoteStatsQueryPayload(
          workflow = request.workflow,
          dateFrom = resolvedDateFrom,
          dateTo = resolvedDateTo,
          groupBy = request.groupBy,
        ).toPayload(),
        errorContext = "Remote telemetry stats request",
        headers = proxyAuthHeaders(resolvedContext.environment),
        requester = resolvedContext.requester,
      ).toMutableMap()
    val responseCapabilitiesPresent = payload.containsKey("capabilities")
    payload.putIfAbsent("workflow", request.workflow)
    payload.putIfAbsent("date_from", resolvedDateFrom)
    payload.putIfAbsent("date_to", resolvedDateTo)
    payload.putIfAbsent("source", "remote_proxy")
    payload.putIfAbsent("stats_url", statsUrl)
    if (!payload.containsKey("capabilities")) {
      payload["capabilities"] = capabilities
    }
    if (request.groupBy.isNotBlank()) {
      payload.putIfAbsent("group_by", request.groupBy)
    }
    return payload.toTelemetryRemoteStatsResult(
      capabilities = capabilities,
      preserveResponseCapabilities = responseCapabilitiesPresent,
    )
  }
}

private fun requestJson(
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

private fun requestJsonGet(
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

private fun proxyAuthHeaders(environment: Map<String, String>): Map<String, String> =
  environment[TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let { mapOf("Authorization" to "Bearer $it") }
    ?: emptyMap()

private fun defaultJsonHeaders(): Map<String, String> = mapOf(
  "Content-Type" to "application/json",
  "User-Agent" to "skill-bill-telemetry/1.0",
)

private fun bodyPublisher(bodyJson: String?): HttpRequest.BodyPublisher = if (bodyJson == null) {
  HttpRequest.BodyPublishers.noBody()
} else {
  HttpRequest.BodyPublishers.ofString(bodyJson)
}

private fun RuntimeContext.withProcessDefaults(): RuntimeContext {
  val withUserHome =
    if (userHome == RuntimeContext.UnspecifiedUserHome) {
      copy(userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())
    } else {
      copy(userHome = userHome.toAbsolutePath().normalize())
    }
  return if (withUserHome.environment === RuntimeContext.UnspecifiedEnvironment) {
    withUserHome.copy(environment = System.getenv())
  } else {
    withUserHome
  }.let { context ->
    if (context.requester === UnconfiguredHttpRequester) {
      context.copy(requester = JdkHttpRequester)
    } else {
      context
    }
  }
}

private const val HTTP_OK_MIN: Int = 200
private const val HTTP_OK_MAX: Int = 299

private class HttpFailureException(
  val statusCode: Int,
  message: String,
) : IllegalArgumentException(message)
