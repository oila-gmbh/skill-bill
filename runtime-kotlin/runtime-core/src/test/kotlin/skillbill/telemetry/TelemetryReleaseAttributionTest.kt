package skillbill.telemetry

import skillbill.db.core.DatabaseRuntime
import skillbill.db.telemetry.TelemetryOutboxStore
import skillbill.infrastructure.http.telemetryProxyBatchPayload
import skillbill.telemetry.model.TelemetrySettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ENQUEUE_TIME_VERSION = "7.7.7-enqueue"
private const val UPLOAD_TIME_VERSION = "8.8.8-upload"

class TelemetryReleaseAttributionTest {
  // SKILL-163 AC-006: attribution is emit-time. A row enqueued under one release keeps that release
  // on the wire even though the running build has since moved on.
  @Test
  fun `an event is uploaded with the version that was running when it was enqueued`() {
    withOutboxDatabase { connection ->
      TelemetryOutboxStore(connection, version = ENQUEUE_TIME_VERSION)
        .enqueue(eventName = "skillbill_goal_finished", payloadJson = """{"name":"ok"}""")

      val uploadTimeStore = TelemetryOutboxStore(connection, version = UPLOAD_TIME_VERSION)
      val payload = telemetryProxyBatchPayload(settings(), uploadTimeStore.listPending())

      assertEquals(ENQUEUE_TIME_VERSION, payload.batch.single().properties["skill_bill_version"])
    }
  }

  // SKILL-163 AC-007: the on-disk shape a real upgrade leaves behind — a row inserted before the
  // column existed — must upload rather than block the queue.
  @Test
  fun `a pre-migration row carrying no version uploads without error`() {
    withOutboxDatabase { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO telemetry_outbox (event_name, payload_json)
          VALUES ('skillbill_goal_finished', '{"name":"legacy"}')
          """.trimIndent(),
        )
      }

      val pending = TelemetryOutboxStore(connection, version = UPLOAD_TIME_VERSION).listPending()
      assertEquals(1, pending.size, "The version-less row must stay pending, not be dropped by the reader.")

      val payload = telemetryProxyBatchPayload(settings(), pending)

      val event = payload.batch.single()
      assertEquals("skillbill_goal_finished", event.event)
      assertEquals("test-install-id", event.properties["install_id"])
      assertFalse("skill_bill_version" in event.properties)
      assertTrue(event.properties.containsKey("name"))
    }
  }

  private fun withOutboxDatabase(block: (java.sql.Connection) -> Unit) {
    val dbPath = Files.createTempDirectory("telemetry-release-attribution").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use(block)
  }

  private fun settings(): TelemetrySettings = TelemetrySettings(
    configPath = Files.createTempFile("telemetry-attribution", ".json"),
    level = "anonymous",
    enabled = true,
    installId = "test-install-id",
    proxyUrl = "https://telemetry.example.dev/ingest",
    customProxyUrl = "https://telemetry.example.dev/ingest",
    batchSize = 50,
  )
}
