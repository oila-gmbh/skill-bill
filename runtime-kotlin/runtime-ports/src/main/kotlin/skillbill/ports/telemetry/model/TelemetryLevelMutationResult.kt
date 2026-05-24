package skillbill.ports.telemetry.model

import skillbill.telemetry.model.TelemetrySettings

data class TelemetryLevelMutationResult(
  val settings: TelemetrySettings,
  val clearedEvents: Int,
)
