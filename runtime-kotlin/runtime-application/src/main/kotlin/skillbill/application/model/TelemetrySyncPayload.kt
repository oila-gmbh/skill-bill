package skillbill.application.model

import skillbill.boundary.OpenBoundaryMap

data class TelemetrySyncPayload(
  val exitCode: Int,
  @OpenBoundaryMap("Telemetry sync wire payload (legacy raw-map surface)")
  val payload: Map<String, Any?>,
)
