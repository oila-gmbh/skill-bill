package skillbill.telemetry

import skillbill.telemetry.model.TelemetryConfigDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TelemetryConfigLevelRuleTest {
  @Test
  fun `writing a level preserves every unrelated payload key`() {
    val document = TelemetryConfigDocument(
      payload = mapOf(
        "install_id" to "retained-install-id",
        "external_addon_sources" to listOf("/tmp/addons"),
        "execution_matrix" to mapOf("default" to "claude"),
        "telemetry" to mapOf("level" to "anonymous", "proxy_url" to "", "batch_size" to 10),
      ),
    )

    val updated = document.withTelemetryLevel("off", "/tmp/config.json")

    assertEquals("retained-install-id", updated.payload["install_id"])
    assertEquals(listOf("/tmp/addons"), updated.payload["external_addon_sources"])
    assertEquals(mapOf("default" to "claude"), updated.payload["execution_matrix"])
    val telemetry = updated.payload["telemetry"] as Map<*, *>
    assertEquals("off", telemetry["level"])
    assertEquals("", telemetry["proxy_url"])
    assertEquals(10, telemetry["batch_size"])
  }

  @Test
  fun `the legacy enabled flag is dropped when a level is written`() {
    val document = TelemetryConfigDocument(
      payload = mapOf("telemetry" to mapOf("level" to "anonymous", "enabled" to true)),
    )

    val telemetry = document.withTelemetryLevel("off", "/tmp/config.json").payload["telemetry"] as Map<*, *>

    assertEquals("off", telemetry["level"])
    assertTrue("enabled" !in telemetry.keys, "the legacy enabled flag must not survive a level write")
  }

  @Test
  fun `a config with no telemetry object gains one instead of failing the level write`() {
    val document = TelemetryConfigDocument(payload = mapOf("install_id" to "retained-install-id"))

    val updated = document.withTelemetryLevel("off", "/tmp/config.json")

    assertEquals("retained-install-id", updated.payload["install_id"])
    assertEquals(mapOf("level" to "off"), updated.payload["telemetry"])
  }

  @Test
  fun `a non-object telemetry entry is rejected rather than silently replaced`() {
    val document = TelemetryConfigDocument(payload = mapOf("telemetry" to "anonymous"))

    val error = assertFailsWith<IllegalArgumentException> {
      document.withTelemetryLevel("off", "/tmp/config.json")
    }

    assertEquals("Telemetry config at '/tmp/config.json' must contain a 'telemetry' object.", error.message)
  }
}
