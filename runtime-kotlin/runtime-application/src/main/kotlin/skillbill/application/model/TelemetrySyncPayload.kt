package skillbill.application.model

data class TelemetrySyncPayload(
  val exitCode: Int,
  val result: TelemetrySyncStatusResult,
)
