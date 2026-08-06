package skillbill.ports.telemetry

import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.withTelemetryLevel
import java.nio.file.Path

interface TelemetryConfigStore {
  fun stateDir(): Path

  fun configPath(): Path

  fun read(): TelemetryConfigDocument?

  fun ensure(): TelemetryConfigDocument

  fun write(document: TelemetryConfigDocument)
}

/**
 * The one implementation of "record this telemetry level in the config file", shared by the CLI
 * mutation path and the install-apply path. Enabling materializes the config (so an install_id
 * exists); disabling only rewrites a config that is already on disk — opting out never creates a
 * file, and therefore never mints an install identifier for a machine that has none.
 *
 * Returns true when the config file was written, false when `off` found no config to rewrite.
 */
fun TelemetryConfigStore.writeTelemetryLevel(level: String): Boolean {
  val document = if (level == "off") read() ?: return false else ensure()
  write(document.withTelemetryLevel(level, configPath().toString()))
  return true
}
