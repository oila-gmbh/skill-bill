package skillbill.telemetry

import skillbill.RuntimeContext
import skillbill.infrastructure.fs.FileTelemetryConfigStore
import skillbill.infrastructure.sqlite.SQLiteTelemetryOutboxPurger
import java.nio.file.Path

object TelemetryConfigMutationRuntime {
  fun setTelemetryLevel(
    level: String,
    dbPath: Path? = null,
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
  ): Pair<TelemetrySettings, Int> {
    val context = RuntimeContext(environment = environment, userHome = userHome)
    val configStore = FileTelemetryConfigStore(context)
    val settingsProvider = DefaultTelemetrySettingsProvider(context, configStore)
    return if (level == "off") {
      require(level in telemetryLevels) {
        "Telemetry level must be one of: ${telemetryLevels.joinToString(", ")}."
      }
      configStore.delete()
      val clearedEvents = dbPath?.let(SQLiteTelemetryOutboxPurger::clearIfDatabaseExists).orEmpty()
      settingsProvider.load(materialize = false) to clearedEvents
    } else {
      TelemetryConfigMutations.setTelemetryLevel(
        level = level,
        configStore = configStore,
        settingsProvider = settingsProvider,
      )
    }
  }

  fun setTelemetryEnabled(
    enabled: Boolean,
    dbPath: Path? = null,
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
  ): Pair<TelemetrySettings, Int> = setTelemetryLevel(
    level = if (enabled) "anonymous" else "off",
    dbPath = dbPath,
    environment = environment,
    userHome = userHome,
  )
}

private fun Int?.orEmpty(): Int = this ?: 0
