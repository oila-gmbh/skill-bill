package skillbill.mcp

import skillbill.application.telemetry.enqueueRuntimeException
import skillbill.db.core.DatabaseRuntime
import skillbill.db.telemetry.TelemetryOutboxStore
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// AC-006: redaction happens at payload construction, so the persisted column — not just the
// in-memory map — must be free of caller-supplied content at `anonymous`.
class RuntimeExceptionPersistedRedactionTest {
  private val callerMessage = "reading /home/dev/checkout/SKILL-163/spec.md failed"

  @Test
  fun `persisted payload_json holds no caller supplied exception message at anonymous`() {
    withConnection { connection ->
      enqueueRuntimeException(
        TelemetryOutboxStore(connection),
        "goal_workflow_open",
        IllegalStateException(callerMessage),
        "anonymous",
      )

      val stored = storedPayload(connection)
      assertFalse(stored.contains(callerMessage), "the caller message must not be persisted at anonymous")
      assertFalse(stored.contains("SKILL-163"), "the tracker key must not be persisted at anonymous")
      assertFalse(stored.contains("/home/dev"), "the file path must not be persisted at anonymous")
      assertTrue(stored.contains("goal_workflow_open"), "workflow_phase survives redaction")
      assertTrue(stored.contains("IllegalStateException"), "error_type survives redaction")
    }
  }

  @Test
  fun `persisted payload_json holds the caller supplied exception message at full`() {
    withConnection { connection ->
      enqueueRuntimeException(
        TelemetryOutboxStore(connection),
        "goal_workflow_open",
        IllegalStateException(callerMessage),
        "full",
      )

      assertTrue(storedPayload(connection).contains(callerMessage), "full level persists the raw message")
    }
  }

  private fun storedPayload(connection: Connection): String = connection.createStatement().use { statement ->
    statement.executeQuery("SELECT payload_json FROM telemetry_outbox ORDER BY id DESC LIMIT 1").use { resultSet ->
      assertTrue(resultSet.next(), "an exception event must be persisted")
      resultSet.getString("payload_json")
    }
  }

  private fun withConnection(block: (Connection) -> Unit) {
    val dbPath = Files.createTempDirectory("skillbill-exception-redaction").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use(block)
  }
}
