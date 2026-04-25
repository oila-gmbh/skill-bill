package skillbill.ports.telemetry

import skillbill.telemetry.model.TelemetrySettings

interface TelemetrySettingsProvider {
  fun load(materialize: Boolean = false): TelemetrySettings

  fun loadOrNull(materialize: Boolean = false): TelemetrySettings? = runCatching { load(materialize) }.getOrNull()
}
