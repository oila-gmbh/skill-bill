package skillbill.ports.telemetry

import java.nio.file.Path

interface TelemetryConfigStore {
  fun stateDir(): Path

  fun configPath(): Path

  fun read(): Map<String, Any?>?

  fun ensure(): Map<String, Any?>

  fun write(payload: Map<String, Any?>)

  fun delete(): Boolean
}
