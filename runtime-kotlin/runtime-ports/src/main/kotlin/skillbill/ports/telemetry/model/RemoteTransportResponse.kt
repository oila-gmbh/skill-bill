package skillbill.ports.telemetry.model

data class RemoteTransportResponse(
  val statusCode: Int,
  val body: String,
)
