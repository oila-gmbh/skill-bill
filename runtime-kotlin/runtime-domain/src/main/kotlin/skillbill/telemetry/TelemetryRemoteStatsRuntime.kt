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
  ): Pair<String, String> = skillbill.telemetry.parseRemoteStatsWindow(since, dateFrom, dateTo, today)
}

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

fun validateRemoteStatsRequest(request: RemoteStatsRequest) {
  require(request.workflow in remoteStatsWorkflows) {
    "workflow must be one of: ${remoteStatsWorkflows.joinToString(", ")}."
  }
  require(request.groupBy.isBlank() || request.groupBy in listOf("day", "week")) {
    "group_by must be one of: day, week."
  }
}

fun validateRemoteStatsCapabilities(
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
