package skillbill.ports.telemetry

import skillbill.ports.telemetry.model.TelemetryLevelMutationResult

fun interface TelemetryLevelMutator {
  fun setLevel(level: String, dbOverride: String?): TelemetryLevelMutationResult
}
