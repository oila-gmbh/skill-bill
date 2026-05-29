package skillbill.telemetry

import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetrySettings
import java.nio.file.Path

object TelemetryConfigRuntime {
  fun stateDir(configStore: TelemetryConfigStore): Path = configStore.stateDir()

  fun resolveConfigPath(configStore: TelemetryConfigStore): Path = configStore.configPath()

  fun defaultLocalConfig(installId: String): TelemetryConfigDocument = defaultLocalTelemetryConfig(installId)

  fun readLocalConfig(configStore: TelemetryConfigStore): TelemetryConfigDocument? = configStore.read()

  fun ensureLocalConfig(configStore: TelemetryConfigStore): TelemetryConfigDocument = configStore.ensure()

  fun parseBoolValue(rawValue: String, name: String): Boolean = parseTelemetryBoolValue(rawValue, name)

  fun parsePositiveInt(rawValue: String, name: String): Int = parsePositiveTelemetryInt(rawValue, name)

  fun parseTelemetryLevel(rawValue: String, name: String): String = parseTelemetryLevelValue(rawValue, name)

  fun loadTelemetrySettings(
    materialize: Boolean = false,
    settingsProvider: TelemetrySettingsProvider,
  ): TelemetrySettings = settingsProvider.load(materialize)

  fun telemetryIsEnabled(settingsProvider: TelemetrySettingsProvider): Boolean =
    settingsProvider.loadOrNull()?.enabled ?: false
}
