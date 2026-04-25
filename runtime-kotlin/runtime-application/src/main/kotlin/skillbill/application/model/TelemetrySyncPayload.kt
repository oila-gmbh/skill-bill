package skillbill.application.model

data class TelemetrySyncPayload(
  val exitCode: Int,
  val payload: Map<String, Any?>,
)
