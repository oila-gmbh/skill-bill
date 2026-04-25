package skillbill.telemetry.model

data class RemoteStatsRequest(
  val workflow: String,
  val since: String = "",
  val dateFrom: String = "",
  val dateTo: String = "",
  val groupBy: String = "",
)
