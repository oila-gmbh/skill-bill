package skillbill.telemetry.config

import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.ports.telemetry.writeTelemetryLevel
import skillbill.telemetry.model.TelemetrySettings
import skillbill.telemetry.telemetryLevels

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
    val currentLevel = settingsProvider.load(materialize = false).level
    return if (level == "off") {
      disableTelemetry(configStore, settingsProvider, outbox)
    } else {
      enableTelemetry(
        configStore = configStore,
        settingsProvider = settingsProvider,
        level = level,
        outbox = outbox.takeIf { clearsPendingOutbox(currentLevel, level) },
      )
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

/**
 * A downgrade narrows what may leave the machine, but payloads already sitting in the outbox were
 * built under the looser level and would still upload raw values on the next sync. Treat an
 * unrecognized current level as the loosest one so an unresolvable state fails closed and clears.
 */
fun clearsPendingOutbox(currentLevel: String, newLevel: String): Boolean {
  if (newLevel == "off") return true
  val current = telemetryLevels.indexOf(currentLevel).takeIf { it >= 0 } ?: telemetryLevels.lastIndex
  return telemetryLevels.indexOf(newLevel) < current
}

private fun enableTelemetry(
  configStore: TelemetryConfigStore,
  settingsProvider: TelemetrySettingsProvider,
  level: String,
  outbox: TelemetryOutboxRepository?,
): Pair<TelemetrySettings, Int> {
  configStore.writeTelemetryLevel(level)
  val clearedEvents = outbox?.clear().orEmpty()
  return settingsProvider.load(materialize = true) to clearedEvents
}

/**
 * Disable is an in-place level write on an existing config, never a delete and never a create:
 * `install_id` and every other config key must survive so re-enabling reuses the same install
 * identity instead of minting a fresh UUID, and a machine with no config keeps having none.
 * [TelemetrySettingsProvider.load] stays non-materializing so it cannot re-default the file just
 * written.
 */
private fun disableTelemetry(
  configStore: TelemetryConfigStore,
  settingsProvider: TelemetrySettingsProvider,
  outbox: TelemetryOutboxRepository?,
): Pair<TelemetrySettings, Int> {
  configStore.writeTelemetryLevel("off")
  val clearedEvents = outbox?.clear().orEmpty()
  return settingsProvider.load(materialize = false) to clearedEvents
}

private fun Int?.orEmpty(): Int = this ?: 0
