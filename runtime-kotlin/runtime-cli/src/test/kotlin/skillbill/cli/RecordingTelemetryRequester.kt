package skillbill.cli

import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.model.HttpResponse

/**
 * Records every request instead of touching the network, so a completion drain in a CLI test can
 * never reach a real relay. [failure] makes the sync path throw the escaping exception type the
 * failure-isolation contract exists to contain.
 */
internal class RecordingTelemetryRequester(
  private val failure: (() -> Nothing)? = null,
) : HttpRequester {
  val requests: MutableList<String> = mutableListOf()

  override fun execute(method: String, url: String, bodyJson: String?, headers: Map<String, String>): HttpResponse {
    requests += url
    failure?.invoke()
    return HttpResponse(statusCode = 200, body = "{}")
  }
}
