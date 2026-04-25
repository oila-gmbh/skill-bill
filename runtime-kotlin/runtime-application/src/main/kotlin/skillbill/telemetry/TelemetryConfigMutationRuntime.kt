package skillbill.telemetry

import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetrySettingsProvider

object TelemetryConfigMutationRuntime {
  fun setTelemetryLevel(
    level: String,
    configStore: TelemetryConfigStore,
    settingsProvider: TelemetrySettingsProvider,
    outbox: TelemetryOutboxRepository? = null,
  ): Pair<TelemetrySettings, Int> = TelemetryConfigMutations.setTelemetryLevel(
    level = level,
    configStore = configStore,
    settingsProvider = settingsProvider,
    outbox = outbox,
  )

  fun setTelemetryEnabled(
    enabled: Boolean,
    configStore: TelemetryConfigStore,
    settingsProvider: TelemetrySettingsProvider,
    outbox: TelemetryOutboxRepository? = null,
  ): Pair<TelemetrySettings, Int> = setTelemetryLevel(
    level = if (enabled) "anonymous" else "off",
    configStore = configStore,
    settingsProvider = settingsProvider,
    outbox = outbox,
  )
}
