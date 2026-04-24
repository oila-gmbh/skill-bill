package skillbill.telemetry

import skillbill.contracts.JsonSupport
import skillbill.db.DatabaseRuntime
import skillbill.db.TelemetryOutboxStore
import skillbill.infrastructure.http.HttpTelemetryClient
import skillbill.ports.persistence.TelemetryOutboxRecord
import skillbill.ports.telemetry.TelemetryClient
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
      HttpTelemetryClient(
        requester = requester,
        environment = mapOf("SKILL_BILL_TELEMETRY_PROXY_STATS_TOKEN" to "stats-token-123"),
      ).fetchRemoteStats(
        settings = settings,
        request =
        RemoteStatsRequest(
          workflow = "bill-feature-verify",
          dateFrom = "2026-04-01",
          dateTo = "2026-04-22",
        ),
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

    val payload = HttpTelemetryClient(requester).fetchProxyCapabilities(settings)

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

      val successClient = RecordingTelemetryClient()
      val successResult = TelemetrySyncRuntime.syncTelemetry(settings, outboxStore, successClient)
      assertEquals("synced", successResult.status)
      assertEquals(2, successResult.syncedEvents)
      assertEquals(listOf(listOf(1L, 2L)), successClient.sentBatchIds)
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val outboxStore = TelemetryOutboxStore(connection)
      outboxStore.enqueue("skillbill_feature_verify_started", JsonSupport.mapToJsonString(mapOf("name" to "retry")))

      val failingClient = RecordingTelemetryClient(failure = IOException("blocked by network isolation sentinel"))
      val failedResult = TelemetrySyncRuntime.syncTelemetry(settings, outboxStore, failingClient)
      assertEquals("failed", failedResult.status)
      assertEquals("blocked by network isolation sentinel", failedResult.message)
      assertEquals("blocked by network isolation sentinel", outboxStore.latestError())
    }
  }

  @Test
  fun `syncTelemetry covers disabled noop and unconfigured paths`() {
    val dbPath = Files.createTempFile("telemetry-invalid", ".db")
    val disabledSettings =
      TelemetrySettings(
        configPath = Files.createTempFile("telemetry-invalid-config", ".json"),
        level = "off",
        enabled = false,
        installId = "",
        proxyUrl = "",
        customProxyUrl = null,
        batchSize = 50,
      )

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val outboxStore = TelemetryOutboxStore(connection)
      val result =
        TelemetrySyncRuntime.autoSyncTelemetry(
          settings = disabledSettings,
          outboxRepository = outboxStore,
          client = RecordingTelemetryClient(failure = IOException("must not call client")),
          reportFailures = false,
        )

      assertEquals("disabled", result?.status)
      assertEquals(
        false,
        TelemetrySyncRuntime.telemetryStatusPayload(dbPath, disabledSettings)["telemetry_enabled"],
      )
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val outboxStore = TelemetryOutboxStore(connection)
      val noopResult =
        TelemetrySyncRuntime.syncTelemetry(
          telemetrySettings(Files.createTempFile("telemetry-noop", ".json")),
          outboxStore,
          RecordingTelemetryClient(),
        )

      assertEquals("noop", noopResult.status)
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val outboxStore = TelemetryOutboxStore(connection)
      outboxStore.enqueue("skillbill_feature_verify_started", JsonSupport.mapToJsonString(mapOf("name" to "pending")))
      val unconfiguredResult =
        TelemetrySyncRuntime.syncTelemetry(
          telemetrySettings(
            configPath = Files.createTempFile("telemetry-unconfigured", ".json"),
            proxyUrl = "",
            customProxyUrl = null,
          ),
          outboxStore,
          RecordingTelemetryClient(failure = IOException("must not call client")),
        )

      assertEquals("unconfigured", unconfiguredResult.status)
      assertEquals(1, unconfiguredResult.pendingEvents)
    }
  }
}

private class RecordingTelemetryClient(
  private val failure: IOException? = null,
) : TelemetryClient {
  val sentBatchIds = mutableListOf<List<Long>>()

  override fun sendBatch(settings: TelemetrySettings, rows: List<TelemetryOutboxRecord>) {
    failure?.let { throw it }
    sentBatchIds += rows.map { it.id }
  }

  override fun fetchProxyCapabilities(settings: TelemetrySettings): Map<String, Any?> =
    error("Unexpected fetchProxyCapabilities")

  override fun fetchRemoteStats(settings: TelemetrySettings, request: RemoteStatsRequest): Map<String, Any?> =
    error("Unexpected fetchRemoteStats")
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
