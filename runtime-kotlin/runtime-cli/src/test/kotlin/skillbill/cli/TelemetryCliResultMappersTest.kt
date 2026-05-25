package skillbill.cli

import skillbill.telemetry.model.TelemetryProxyCapabilities
import skillbill.telemetry.model.TelemetryRemoteStatsResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryCliResultMappersTest {
  @Test
  fun `remote stats mapper preserves explicit null capabilities`() {
    val result =
      TelemetryRemoteStatsResult(
        workflow = "bill-feature-verify",
        dateFrom = "2026-04-01",
        dateTo = "2026-04-22",
        source = "remote_proxy",
        statsUrl = "https://telemetry.example.dev/ingest/stats",
        groupBy = null,
        capabilities =
        TelemetryProxyCapabilities(
          contractVersion = "1",
          source = "remote_proxy",
          proxyUrl = "https://telemetry.example.dev/ingest",
          capabilitiesUrl = "https://telemetry.example.dev/ingest/capabilities",
          supportsIngest = true,
          supportsStats = true,
          supportedWorkflows = listOf("bill-feature-verify"),
        ),
        metrics = linkedMapOf("status" to "ok", "capabilities" to null),
      )

    val payload = result.toCliMap()

    assertTrue("capabilities" in payload)
    assertEquals(null, payload["capabilities"])
  }
}
