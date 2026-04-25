package skillbill.telemetry

import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetrySettingsProvider

object TelemetryConfigMutations {
  fun setTelemetryLevel(
    level: String,
    configStore: TelemetryConfigStore,
    settingsProvider: TelemetrySettingsProvider,
    outbox: TelemetryOutboxRepository? = null,
  ): Pair<TelemetrySettings, Int> {
    require(level in telemetryLevels) {
      "Telemetry level must be one of: ${telemetryLevels.joinToString(", ")}."
    }
    return if (level == "off") {
      disableTelemetry(configStore, settingsProvider, outbox)
    } else {
      enableTelemetry(configStore, settingsProvider, level)
    }
  }

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

private fun enableTelemetry(
  configStore: TelemetryConfigStore,
  settingsProvider: TelemetrySettingsProvider,
  level: String,
): Pair<TelemetrySettings, Int> {
  val payload = configStore.ensure().toMutableMap()
  val telemetry =
    (
      (payload["telemetry"] as? Map<*, *>)
        ?.entries
        ?.filter { it.key is String }
        ?.associate { it.key as String to it.value }
        ?.toMutableMap()
      )
      ?: throw IllegalArgumentException(
        "Telemetry config at '${configStore.configPath()}' must contain a 'telemetry' object.",
      )
  telemetry["level"] = level
  telemetry.remove("enabled")
  payload["telemetry"] = telemetry
  configStore.write(payload)
  return settingsProvider.load(materialize = true) to 0
}

private fun disableTelemetry(
  configStore: TelemetryConfigStore,
  settingsProvider: TelemetrySettingsProvider,
  outbox: TelemetryOutboxRepository?,
): Pair<TelemetrySettings, Int> {
  configStore.delete()
  val clearedEvents = outbox?.clear().orEmpty()
  return settingsProvider.load(materialize = false) to clearedEvents
}

private fun Int?.orEmpty(): Int = this ?: 0
