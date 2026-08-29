package skillbill.infrastructure.http

import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.telemetry.model.TelemetrySettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryProxyPayloadMappersTest {
  // SKILL-163 AC-004: the row's own recorded version rides alongside install_id.
  @Test
  fun `a recorded version is injected next to install_id`() {
    val payload = telemetryProxyBatchPayload(settings(), listOf(row(id = 1, version = "1.2.3")))

    val properties = payload.batch.single().properties
    assertEquals("1.2.3", properties["skill_bill_version"])
    assertEquals("test-install-id", properties["install_id"])
  }

  // SKILL-163 AC-005: version-absent rows still upload; the property is simply omitted.
  @Test
  fun `rows with no recorded version still produce well-formed events`() {
    val rows =
      listOf(
        row(id = 1, version = null),
        row(id = 2, version = ""),
        row(id = 3, version = "1.2.3"),
      )

    val payload = telemetryProxyBatchPayload(settings(), rows)

    assertEquals(rows.size, payload.batch.size, "No row may be dropped for lacking a version.")
    payload.batch.take(2).forEach { event ->
      assertEquals("skillbill_goal_finished", event.event)
      assertEquals("test-install-id", event.properties["install_id"])
      assertFalse("skill_bill_version" in event.properties, "An absent version must omit the property.")
    }
    assertTrue("skill_bill_version" in payload.batch.last().properties)
  }

  private fun row(id: Long, version: String?): TelemetryOutboxRecord = TelemetryOutboxRecord(
    id = id,
    eventName = "skillbill_goal_finished",
    payloadJson = """{"name":"ok"}""",
    createdAt = "2026-04-23 00:00:00",
    syncedAt = null,
    lastError = "",
    skillBillVersion = version,
  )

  private fun settings(): TelemetrySettings = TelemetrySettings(
    configPath = Files.createTempFile("telemetry-mapper", ".json"),
    level = "anonymous",
    enabled = true,
    installId = "test-install-id",
    proxyUrl = "https://telemetry.example.dev/ingest",
    customProxyUrl = "https://telemetry.example.dev/ingest",
    batchSize = 50,
  )
}
