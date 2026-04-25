package skillbill.telemetry

import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetrySettingsProvider
import java.nio.file.Path
import java.util.UUID

object TelemetryConfigRuntime {
  fun stateDir(configStore: TelemetryConfigStore): Path = configStore.stateDir()

  fun resolveConfigPath(configStore: TelemetryConfigStore): Path = configStore.configPath()

  fun defaultLocalConfig(): Map<String, Any?> = mapOf(
    "install_id" to UUID.randomUUID().toString(),
    "telemetry" to
      mapOf(
        "level" to "anonymous",
        "proxy_url" to "",
        "batch_size" to DEFAULT_TELEMETRY_BATCH_SIZE,
      ),
  )

  fun readLocalConfig(configStore: TelemetryConfigStore): Map<String, Any?>? = configStore.read()

  fun ensureLocalConfig(configStore: TelemetryConfigStore): Map<String, Any?> = configStore.ensure()

  fun parseBoolValue(rawValue: String, name: String): Boolean = when (rawValue.trim().lowercase()) {
    "1", "true", "yes", "on" -> true
    "0", "false", "no", "off" -> false
    else -> throw IllegalArgumentException("$name must be one of: 1, 0, true, false, yes, no, on, off.")
  }

  fun parsePositiveInt(rawValue: String, name: String): Int {
    val value = rawValue.toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer.")
    require(value > 0) { "$name must be greater than zero." }
    return value
  }

  fun parseTelemetryLevel(rawValue: String, name: String): String {
    val normalized = rawValue.trim().lowercase()
    require(normalized in telemetryLevels) {
      "$name must be one of: ${telemetryLevels.joinToString(", ")}."
    }
    return normalized
  }

  fun loadTelemetrySettings(
    materialize: Boolean = false,
    settingsProvider: TelemetrySettingsProvider,
  ): TelemetrySettings = settingsProvider.load(materialize)

  fun telemetryIsEnabled(settingsProvider: TelemetrySettingsProvider): Boolean =
    settingsProvider.loadOrNull()?.enabled ?: false
}
