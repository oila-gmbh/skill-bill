package skillbill.application.telemetry.model

data class TelemetrySyncPayload(
  val exitCode: Int,
  val result: TelemetrySyncStatusResult,
)
