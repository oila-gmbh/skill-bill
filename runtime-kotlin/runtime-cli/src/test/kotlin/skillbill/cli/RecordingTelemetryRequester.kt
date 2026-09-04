package skillbill.cli

import skillbill.ports.telemetry.RemoteTransportPort
import skillbill.ports.telemetry.model.RemoteTransportResponse

/**
 * Records every request instead of touching the network, so a completion drain in a CLI test can
 * never reach a real relay. [failure] makes the sync path throw the escaping exception type the
 * failure-isolation contract exists to contain.
 */
internal class RecordingTelemetryRequester(
  private val failure: (() -> Nothing)? = null,
) : RemoteTransportPort {
  val requests: MutableList<String> = mutableListOf()

  override fun execute(
    method: String,
    url: String,
    bodyJson: String?,
    headers: Map<String, String>,
  ): RemoteTransportResponse {
    requests += url
    failure?.invoke()
    return RemoteTransportResponse(statusCode = 200, body = "{}")
  }
}
