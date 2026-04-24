package skillbill.ports.telemetry

data class HttpResponse(
  val statusCode: Int,
  val body: String,
)

fun interface HttpRequester {
  fun execute(method: String, url: String, bodyJson: String?, headers: Map<String, String>): HttpResponse
}
