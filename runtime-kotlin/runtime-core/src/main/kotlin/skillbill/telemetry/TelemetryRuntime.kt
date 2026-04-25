package skillbill.telemetry

import skillbill.ports.telemetry.TelemetrySettingsProvider

object TelemetryRuntime {
  fun loadTelemetrySettings(
    settingsProvider: TelemetrySettingsProvider,
    materialize: Boolean = false,
  ): TelemetrySettings = TelemetryConfigRuntime.loadTelemetrySettings(
    materialize = materialize,
    settingsProvider = settingsProvider,
  )

  fun telemetryIsEnabled(settingsProvider: TelemetrySettingsProvider): Boolean =
    TelemetryConfigRuntime.telemetryIsEnabled(settingsProvider)
}
