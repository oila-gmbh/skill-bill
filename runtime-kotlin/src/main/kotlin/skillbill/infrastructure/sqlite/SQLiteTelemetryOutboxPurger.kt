package skillbill.infrastructure.sqlite

import skillbill.db.DatabaseRuntime
import skillbill.db.TelemetryOutboxStore
import java.nio.file.Files
import java.nio.file.Path

object SQLiteTelemetryOutboxPurger {
  fun clearIfDatabaseExists(dbPath: Path): Int {
    if (!Files.exists(dbPath)) {
      return 0
    }
    return DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      TelemetryOutboxStore(connection).clear()
    }
  }
}
