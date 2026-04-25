package skillbill.ports.telemetry.model

data class HttpResponse(
  val statusCode: Int,
  val body: String,
)
