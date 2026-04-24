package skillbill.cli.models

internal data class TelemetryStatsArgs(
  val workflow: String,
  val since: String,
  val dateFrom: String,
  val dateTo: String,
  val groupBy: String,
  val format: CliFormat,
)

internal data class TelemetryEnableArgs(
  val level: String,
  val format: CliFormat,
)

internal data class TelemetrySetLevelArgs(
  val level: String,
  val format: CliFormat,
)
