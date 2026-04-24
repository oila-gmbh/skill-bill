package skillbill.telemetry

import skillbill.contracts.JsonSupport
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

object TelemetryConfigMutationRuntime {
  fun setTelemetryLevel(
    level: String,
    dbPath: Path? = null,
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home")),
  ): Pair<TelemetrySettings, Int> {
    require(level in telemetryLevels) {
      "Telemetry level must be one of: ${telemetryLevels.joinToString(", ")}."
    }
    val configPath = TelemetryConfigRuntime.resolveConfigPath(environment, userHome)
    return if (level == "off") {
      disableTelemetry(configPath, dbPath, environment, userHome)
    } else {
      enableTelemetry(configPath, level, environment, userHome)
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

private fun enableTelemetry(
  configPath: Path,
  level: String,
  environment: Map<String, String>,
  userHome: Path,
): Pair<TelemetrySettings, Int> {
  val payload = TelemetryConfigRuntime.ensureLocalConfig(configPath).toMutableMap()
  val telemetry =
    (
      (payload["telemetry"] as? Map<*, *>)
        ?.entries
        ?.filter { it.key is String }
        ?.associate { it.key as String to it.value }
        ?.toMutableMap()
      )
      ?: throw IllegalArgumentException(
        "Telemetry config at '$configPath' must contain a 'telemetry' object.",
      )
  telemetry["level"] = level
  telemetry.remove("enabled")
  payload["telemetry"] = telemetry
  Files.writeString(configPath, JsonSupport.mapToJsonString(payload) + "\n")
  return TelemetryConfigRuntime.loadTelemetrySettings(
    materialize = true,
    environment = environment,
    userHome = userHome,
  ) to 0
}

private fun disableTelemetry(
  configPath: Path,
  dbPath: Path?,
  environment: Map<String, String>,
  userHome: Path,
): Pair<TelemetrySettings, Int> {
  Files.deleteIfExists(configPath)
  val clearedEvents = dbPath?.let(::purgeTelemetryOutbox).orEmpty()
  return TelemetryConfigRuntime.loadTelemetrySettings(
    materialize = false,
    environment = environment,
    userHome = userHome,
  ) to clearedEvents
}

private fun purgeTelemetryOutbox(dbPath: Path): Int {
  if (!Files.exists(dbPath)) {
    return 0
  }
  val jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}"
  return DriverManager.getConnection(jdbcUrl).use(::purgeTelemetryOutboxRows)
}

private fun Int?.orEmpty(): Int = this ?: 0

private fun purgeTelemetryOutboxRows(connection: Connection): Int {
  if (!hasTelemetryOutbox(connection)) {
    return 0
  }
  val pendingEvents = countPendingTelemetryEvents(connection)
  deleteTelemetryOutboxRows(connection)
  return pendingEvents
}

private fun hasTelemetryOutbox(connection: Connection): Boolean = connection.createStatement().use { statement ->
  statement.executeQuery(
    """
    SELECT 1
    FROM sqlite_master
    WHERE type = 'table' AND name = 'telemetry_outbox'
    """.trimIndent(),
  ).use { resultSet -> resultSet.next() }
}

private fun countPendingTelemetryEvents(connection: Connection): Int = connection.createStatement().use { statement ->
  statement.executeQuery("SELECT COUNT(*) FROM telemetry_outbox").use { resultSet ->
    if (resultSet.next()) {
      resultSet.getInt(1)
    } else {
      0
    }
  }
}

private fun deleteTelemetryOutboxRows(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.executeUpdate("DELETE FROM telemetry_outbox")
  }
}
