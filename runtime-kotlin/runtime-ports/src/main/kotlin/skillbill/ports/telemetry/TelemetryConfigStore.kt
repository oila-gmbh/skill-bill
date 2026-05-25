package skillbill.ports.telemetry

import skillbill.telemetry.model.TelemetryConfigDocument
import java.nio.file.Path

interface TelemetryConfigStore {
  fun stateDir(): Path

  fun configPath(): Path

  fun read(): TelemetryConfigDocument?

  fun ensure(): TelemetryConfigDocument

  fun write(document: TelemetryConfigDocument)

  fun delete(): Boolean
}
