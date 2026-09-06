package skillbill.ports.telemetry

import skillbill.ports.telemetry.model.RemoteTransportResponse

fun interface RemoteTransportPort {
  fun execute(method: String, url: String, bodyJson: String?, headers: Map<String, String>): RemoteTransportResponse
}
