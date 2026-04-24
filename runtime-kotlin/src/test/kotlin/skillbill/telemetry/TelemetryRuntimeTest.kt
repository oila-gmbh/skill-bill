package skillbill.telemetry

import skillbill.contracts.JsonSupport
import skillbill.db.DatabaseRuntime
import skillbill.db.TelemetryOutboxStore
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TelemetryRuntimeTest {
  @Test
  fun `fetchRemoteStats posts proxy contract payload`() {
    val requests = mutableListOf<Triple<String, String, String?>>()
    val requester = remoteStatsRequester(requests)
    val settings = telemetrySettings(Files.createTempFile("telemetry", ".json"))

    val payload =
      TelemetryRemoteStatsRuntime.fetchRemoteStats(
        request =
        RemoteStatsRequest(
          workflow = "bill-feature-verify",
          dateFrom = "2026-04-01",
          dateTo = "2026-04-22",
        ),
        settings = settings,
        requester = requester,
        environment = mapOf("SKILL_BILL_TELEMETRY_PROXY_STATS_TOKEN" to "stats-token-123"),
      )

    assertEquals("bill-feature-verify", payload["workflow"])
    assertEquals(14, payload["started_runs"])
    assertEquals(2, requests.size)
    assertEquals("GET", requests[0].first)
    assertEquals("POST", requests[1].first)
    assertNotNull(payload["capabilities"])
  }

  @Test
  fun `fetchProxyCapabilities falls back to default contract on 404`() {
    val requester = HttpRequester { _, _, _, _ -> HttpResponse(statusCode = 404, body = "") }
    val settings = telemetrySettings(Files.createTempFile("telemetry-capabilities", ".json"), customProxyUrl = null)

    val payload = TelemetryHttpRuntime.fetchProxyCapabilities(settings = settings, requester = requester)

    assertEquals("0", payload["contract_version"])
    assertEquals(false, payload["supports_stats"])
  }

  @Test
  fun `syncTelemetry marks batch synced and failed batches`() {
    val tempDir = Files.createTempDirectory("telemetry-sync")
    val dbPath = tempDir.resolve("metrics.db")
    val settings =
      telemetrySettings(
        configPath = tempDir.resolve("config.json"),
        proxyUrl = "http://127.0.0.1:0",
        customProxyUrl = "http://127.0.0.1:0",
      )

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val outboxStore = TelemetryOutboxStore(connection)
      outboxStore.enqueue("skillbill_feature_implement_started", JsonSupport.mapToJsonString(mapOf("name" to "ok")))
      outboxStore.enqueue("skillbill_feature_implement_finished", JsonSupport.mapToJsonString(mapOf("name" to "fail")))
    }

    val successRequester = HttpRequester { _, _, _, _ -> HttpResponse(statusCode = 200, body = "") }
    val successResult = TelemetrySyncRuntime.syncTelemetry(dbPath, settings, successRequester)
    assertEquals("synced", successResult.status)
    assertEquals(2, successResult.syncedEvents)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val outboxStore = TelemetryOutboxStore(connection)
      outboxStore.enqueue("skillbill_feature_verify_started", JsonSupport.mapToJsonString(mapOf("name" to "retry")))
    }
    val failingRequester = HttpRequester { _, _, _, _ -> throw IOException("blocked by network isolation sentinel") }
    val failedResult = TelemetrySyncRuntime.syncTelemetry(dbPath, settings, failingRequester)
    assertEquals("failed", failedResult.status)
    assertEquals("blocked by network isolation sentinel", failedResult.message)
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val latestError = TelemetryOutboxStore(connection).latestError()
      assertEquals("blocked by network isolation sentinel", latestError)
    }
  }

  @Test
  fun `autoSyncTelemetry returns disabled result when telemetry is off`() {
    val dbPath = Files.createTempFile("telemetry-invalid", ".db")
    val settings =
      TelemetrySettings(
        configPath = Files.createTempFile("telemetry-invalid-config", ".json"),
        level = "off",
        enabled = false,
        installId = "",
        proxyUrl = "",
        customProxyUrl = null,
        batchSize = 50,
      )
    val result =
      TelemetrySyncRuntime.autoSyncTelemetry(
        dbPath = dbPath,
        reportFailures = false,
        settings = settings,
      )

    assertEquals("disabled", result?.status)
    assertEquals(false, TelemetrySyncRuntime.telemetryStatusPayload(dbPath, settings)["telemetry_enabled"])
  }
}

private fun remoteStatsRequester(requests: MutableList<Triple<String, String, String?>>): HttpRequester =
  HttpRequester { method, url, bodyJson, _ ->
    requests += Triple(method, url, bodyJson)
    if (url.endsWith("/capabilities")) {
      capabilitiesResponse()
    } else {
      remoteStatsResponse()
    }
  }

private fun capabilitiesResponse(): HttpResponse = HttpResponse(
  statusCode = 200,
  body =
  """
      {
        "contract_version": "1",
        "supports_ingest": true,
        "supports_stats": true,
        "supported_workflows": ["bill-feature-verify", "bill-feature-implement"]
      }
  """.trimIndent(),
)

private fun remoteStatsResponse(): HttpResponse = HttpResponse(
  statusCode = 200,
  body =
  """
      {
        "status": "ok",
        "workflow": "bill-feature-verify",
        "source": "remote_proxy",
        "started_runs": 14,
        "finished_runs": 12,
        "in_progress_runs": 2
      }
  """.trimIndent(),
)

private fun telemetrySettings(
  configPath: java.nio.file.Path,
  proxyUrl: String = "https://telemetry.example.dev/ingest",
  customProxyUrl: String? = proxyUrl,
): TelemetrySettings = TelemetrySettings(
  configPath = configPath,
  level = "anonymous",
  enabled = true,
  installId = "test-install-id",
  proxyUrl = proxyUrl,
  customProxyUrl = customProxyUrl,
  batchSize = 50,
)
