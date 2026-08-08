package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Writes the durable config the telemetry settings provider resolves from [userHome], so a CLI
 * test can drive an install whose resolved level is enabled (or explicitly `off`) without ever
 * reading the developer's real config.
 */
internal fun writeTelemetryConfig(userHome: Path, level: String, proxyUrl: String) {
  val configPath = userHome.resolve(".config/skill-bill/config.json")
  Files.createDirectories(configPath.parent)
  Files.writeString(
    configPath,
    """
      {
        "install_id": "cli-drain-fixture-install",
        "telemetry": { "level": "$level", "proxy_url": "$proxyUrl" }
      }
    """.trimIndent(),
  )
}

/** Unroutable by construction: a fixture that reached it would be making a real network call. */
internal const val TELEMETRY_FIXTURE_PROXY_URL = "http://127.0.0.1:9/telemetry"

/**
 * Creates the fixture database through a real CLI invocation rather than hand-authored schema, so
 * seeded outbox rows always match the schema the runtime itself creates.
 */
internal fun materializeTelemetryDatabase(userHome: Path, dbPath: Path, level: String, context: CliRuntimeContext) {
  writeTelemetryConfig(userHome, level = level, proxyUrl = TELEMETRY_FIXTURE_PROXY_URL)
  val status = CliRuntime.run(listOf("--db", dbPath.toString(), "telemetry", "status"), context)
  check(status.exitCode == 0) { "telemetry status did not materialize the fixture database: ${status.stdout}" }
}

internal fun seedTelemetryOutbox(dbPath: Path, eventName: String) {
  DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    connection.prepareStatement("INSERT INTO telemetry_outbox (event_name, payload_json) VALUES (?, ?)").use {
      it.setString(1, eventName)
      it.setString(2, """{"fixture":true}""")
      it.executeUpdate()
    }
  }
}

internal fun pendingTelemetryOutboxCount(dbPath: Path): Int = telemetryOutboxCount(dbPath, "synced_at IS NULL")

internal fun syncedTelemetryOutboxCount(dbPath: Path): Int = telemetryOutboxCount(dbPath, "synced_at IS NOT NULL")

private fun telemetryOutboxCount(dbPath: Path, predicate: String): Int =
  DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    connection.prepareStatement("SELECT COUNT(*) FROM telemetry_outbox WHERE $predicate").use { statement ->
      statement.executeQuery().use { rows ->
        rows.next()
        rows.getInt(1)
      }
    }
  }
