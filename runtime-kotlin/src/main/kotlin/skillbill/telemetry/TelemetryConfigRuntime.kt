package skillbill.telemetry

import skillbill.RuntimeContext
import skillbill.infrastructure.fs.FileTelemetryConfigStore
import skillbill.infrastructure.fs.ensureTelemetryConfigFile
import skillbill.infrastructure.fs.readTelemetryConfigFile
import skillbill.infrastructure.fs.resolveTelemetryConfigPath
import skillbill.infrastructure.fs.resolveTelemetryStateDir
import java.nio.file.Path
import java.util.UUID

object TelemetryConfigRuntime {
  fun stateDir(
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
  ): Path = resolveTelemetryStateDir(environment, userHome)

  fun resolveConfigPath(
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
  ): Path = resolveTelemetryConfigPath(environment, userHome)

  fun defaultLocalConfig(): Map<String, Any?> = mapOf(
    "install_id" to UUID.randomUUID().toString(),
    "telemetry" to
      mapOf(
        "level" to "anonymous",
        "proxy_url" to "",
        "batch_size" to DEFAULT_TELEMETRY_BATCH_SIZE,
      ),
  )

  fun readLocalConfig(path: Path): Map<String, Any?>? = readTelemetryConfigFile(path)

  fun ensureLocalConfig(path: Path): Map<String, Any?> = ensureTelemetryConfigFile(path)

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
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
  ): TelemetrySettings = loadTelemetrySettingsFromStore(
    materialize = materialize,
    environment = environment,
    configStore = FileTelemetryConfigStore(RuntimeContext(environment = environment, userHome = userHome)),
  )

  fun telemetryIsEnabled(
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
  ): Boolean = runCatching {
    loadTelemetrySettings(environment = environment, userHome = userHome).enabled
  }.getOrDefault(false)
}
