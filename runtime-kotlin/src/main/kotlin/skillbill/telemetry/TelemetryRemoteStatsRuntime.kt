package skillbill.telemetry

import java.time.LocalDate
import java.time.ZoneOffset

data class RemoteStatsRequest(
  val workflow: String,
  val since: String = "",
  val dateFrom: String = "",
  val dateTo: String = "",
  val groupBy: String = "",
)

object TelemetryRemoteStatsRuntime {
  fun parseRemoteStatsWindow(
    since: String = "",
    dateFrom: String = "",
    dateTo: String = "",
    today: LocalDate = LocalDate.now(ZoneOffset.UTC),
  ): Pair<String, String> {
    require(dateFrom.isBlank() || since.isBlank()) {
      "Use either since or date_from/date_to, not both."
    }
    val endDate = resolvedEndDate(dateTo, today)
    val startDate = resolvedStartDate(since, dateFrom, endDate)
    require(!startDate.isAfter(endDate)) {
      "date_from must be on or before date_to."
    }
    return startDate.toString() to endDate.toString()
  }

  fun fetchRemoteStats(
    request: RemoteStatsRequest,
    settings: TelemetrySettings = TelemetryConfigRuntime.loadTelemetrySettings(),
    requester: HttpRequester = TelemetryHttpRuntime.defaultHttpRequester,
    environment: Map<String, String> = System.getenv(),
  ): Map<String, Any?> {
    validateRemoteStatsRequest(request)
    require(settings.proxyUrl.isNotBlank()) {
      "Telemetry relay URL is not configured."
    }
    val (resolvedDateFrom, resolvedDateTo) =
      parseRemoteStatsWindow(request.since, request.dateFrom, request.dateTo)
    val capabilities =
      TelemetryHttpRuntime.fetchProxyCapabilities(
        settings = settings,
        requester = requester,
        environment = environment,
      )
    validateRemoteStatsCapabilities(
      request = request,
      settings = settings,
      capabilities = capabilities,
    )
    val statsUrl = settings.proxyUrl.trimEnd('/') + "/stats"
    val payload =
      requestJson(
        url = statsUrl,
        payload = remoteStatsPayload(request, resolvedDateFrom, resolvedDateTo),
        errorContext = "Remote telemetry stats request",
        headers = proxyAuthHeaders(environment),
        requester = requester,
      ).toMutableMap()
    payload.putIfAbsent("workflow", request.workflow)
    payload.putIfAbsent("date_from", resolvedDateFrom)
    payload.putIfAbsent("date_to", resolvedDateTo)
    payload.putIfAbsent("source", "remote_proxy")
    payload.putIfAbsent("stats_url", statsUrl)
    payload.putIfAbsent("capabilities", capabilities)
    if (request.groupBy.isNotBlank()) {
      payload.putIfAbsent("group_by", request.groupBy)
    }
    return payload
  }
}

private fun validateRemoteStatsRequest(request: RemoteStatsRequest) {
  require(request.workflow in remoteStatsWorkflows) {
    "workflow must be one of: ${remoteStatsWorkflows.joinToString(", ")}."
  }
  require(request.groupBy.isBlank() || request.groupBy in listOf("day", "week")) {
    "group_by must be one of: day, week."
  }
}

private fun validateRemoteStatsCapabilities(
  request: RemoteStatsRequest,
  settings: TelemetrySettings,
  capabilities: Map<String, Any?>,
) {
  require(capabilities["supports_stats"] == true) {
    val capabilitiesUrl =
      capabilities["capabilities_url"]
        ?: settings.proxyUrl.trimEnd('/') + "/capabilities"
    "Configured telemetry proxy does not support remote stats yet. Capabilities URL: $capabilitiesUrl"
  }
  val supportedWorkflows =
    (capabilities["supported_workflows"] as? List<*>)
      ?.filterIsInstance<String>()
      .orEmpty()
  require(supportedWorkflows.isEmpty() || request.workflow in supportedWorkflows) {
    "Configured telemetry proxy does not support workflow '${request.workflow}'. " +
      "Supported workflows: ${supportedWorkflows.joinToString(", ")}."
  }
}

private fun remoteStatsPayload(
  request: RemoteStatsRequest,
  resolvedDateFrom: String,
  resolvedDateTo: String,
): Map<String, Any?> = buildMap {
  put("workflow", request.workflow)
  put("date_from", resolvedDateFrom)
  put("date_to", resolvedDateTo)
  if (request.groupBy.isNotBlank()) {
    put("group_by", request.groupBy)
  }
}

private fun resolvedEndDate(dateTo: String, today: LocalDate): LocalDate = if (dateTo.isBlank()) {
  today
} else {
  parseIsoDate(dateTo, "date_to")
}

private fun resolvedStartDate(since: String, dateFrom: String, endDate: LocalDate): LocalDate =
  if (dateFrom.isNotBlank()) {
    parseIsoDate(dateFrom, "date_from")
  } else {
    endDate.minusDays((parseSinceDays(since.ifBlank { "30d" }) - 1).toLong())
  }

private fun parseIsoDate(rawValue: String, fieldName: String): LocalDate =
  runCatching { LocalDate.parse(rawValue) }.getOrElse {
    throw IllegalArgumentException("$fieldName must use YYYY-MM-DD format.")
  }

private fun parseSinceDays(rawValue: String): Int {
  val normalized = rawValue.trim().lowercase()
  require(normalized.endsWith("d")) {
    "since must use <days>d format, for example 7d or 30d."
  }
  val days =
    normalized.dropLast(1).toIntOrNull()
      ?: throw IllegalArgumentException(
        "since must use <days>d format, for example 7d or 30d.",
      )
  require(days > 0) { "since must be greater than zero days." }
  return days
}
