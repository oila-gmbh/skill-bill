package skillbill.mcp

import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.model.HttpResponse
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_URL_ENVIRONMENT_KEY
import skillbill.telemetry.model.RemoteStatsRequest
import skillbill.telemetry.model.TelemetryProxyCapabilities
import skillbill.telemetry.model.TelemetryRemoteStatsResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpTelemetryRuntimeTest {
  @Test
  fun `telemetry tools preserve proxy request shape`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-telemetry")
    val configPath = writeMcpTelemetryConfig(tempDir, "off")
    val env =
      mapOf(
        CONFIG_ENVIRONMENT_KEY to configPath.toString(),
        TELEMETRY_PROXY_URL_ENVIRONMENT_KEY to "https://telemetry.example.dev/ingest",
        TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY to "stats-token-123",
      )
    val capturedRequests = mutableListOf<Map<String, Any?>>()
    val requester = mcpTelemetryRequester(capturedRequests)

    val context = McpRuntimeContext(requester = requester, environment = env, userHome = tempDir)
    val capabilities = McpRuntime.telemetryProxyCapabilities(context)
    val stats =
      McpRuntime.telemetryRemoteStats(
        request = RemoteStatsRequest(workflow = "bill-feature-verify", dateFrom = "2026-04-01", dateTo = "2026-04-22"),
        context = context,
      )

    assertEquals(expectedMcpCapabilitiesPayload(), capabilities)
    assertEquals(expectedMcpRemoteStatsPayload(), stats)
    assertEquals(expectedMcpRemoteStatsRequests(), capturedRequests)
  }

  @Test
  fun `telemetry remote stats tool maps short workflow aliases before proxy request`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-telemetry-alias")
    val configPath = writeMcpTelemetryConfig(tempDir, "off")
    val env =
      mapOf(
        CONFIG_ENVIRONMENT_KEY to configPath.toString(),
        TELEMETRY_PROXY_URL_ENVIRONMENT_KEY to "https://telemetry.example.dev/ingest",
        TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY to "stats-token-123",
      )
    val capturedRequests = mutableListOf<Map<String, Any?>>()
    val context =
      McpRuntimeContext(
        requester = mcpTelemetryRequester(capturedRequests),
        environment = env,
        userHome = tempDir,
      )

    val stats =
      McpToolDispatcher.call(
        "telemetry_remote_stats",
        mapOf("workflow" to "verify", "date_from" to "2026-04-01", "date_to" to "2026-04-22"),
        context,
      )

    assertEquals("bill-feature-verify", stats["workflow"])
    assertEquals(
      "{\"workflow\":\"bill-feature-verify\",\"date_from\":\"2026-04-01\",\"date_to\":\"2026-04-22\"}",
      capturedRequests.last()["body"],
    )
  }

  @Test
  fun `telemetry remote stats tool maps implement alias to bill-feature-task`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-telemetry-implement-alias")
    val configPath = writeMcpTelemetryConfig(tempDir, "off")
    val env =
      mapOf(
        CONFIG_ENVIRONMENT_KEY to configPath.toString(),
        TELEMETRY_PROXY_URL_ENVIRONMENT_KEY to "https://telemetry.example.dev/ingest",
        TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY to "stats-token-123",
      )
    val capturedRequests = mutableListOf<Map<String, Any?>>()
    val context =
      McpRuntimeContext(
        requester = mcpTelemetryRequester(capturedRequests),
        environment = env,
        userHome = tempDir,
      )

    val stats =
      McpToolDispatcher.call(
        "telemetry_remote_stats",
        mapOf("workflow" to "implement", "date_from" to "2026-04-01", "date_to" to "2026-04-22"),
        context,
      )

    assertEquals(
      "{\"workflow\":\"bill-feature-task\",\"date_from\":\"2026-04-01\",\"date_to\":\"2026-04-22\"}",
      capturedRequests.last()["body"],
    )
  }

  @Test
  fun `telemetry remote stats tool accepts bill-feature-task canonical passthrough`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-telemetry-task-passthrough")
    val configPath = writeMcpTelemetryConfig(tempDir, "off")
    val env =
      mapOf(
        CONFIG_ENVIRONMENT_KEY to configPath.toString(),
        TELEMETRY_PROXY_URL_ENVIRONMENT_KEY to "https://telemetry.example.dev/ingest",
        TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY to "stats-token-123",
      )
    val capturedRequests = mutableListOf<Map<String, Any?>>()
    val context =
      McpRuntimeContext(
        requester = mcpTelemetryRequester(capturedRequests),
        environment = env,
        userHome = tempDir,
      )

    McpToolDispatcher.call(
      "telemetry_remote_stats",
      mapOf("workflow" to "bill-feature-task", "date_from" to "2026-04-01", "date_to" to "2026-04-22"),
      context,
    )

    assertTrue(capturedRequests.last()["body"].toString().contains("\"workflow\":\"bill-feature-task\""))
  }

  @Test
  fun `telemetry remote stats mapper preserves explicit null capabilities`() {
    val result =
      TelemetryRemoteStatsResult(
        workflow = "bill-feature-verify",
        dateFrom = "2026-04-01",
        dateTo = "2026-04-22",
        source = "remote_proxy",
        statsUrl = "https://telemetry.example.dev/ingest/stats",
        groupBy = null,
        capabilities = typedCapabilities(),
        metrics = linkedMapOf("status" to "ok", "capabilities" to null),
      )

    val payload = result.toMcpMap()

    assertTrue("capabilities" in payload)
    assertEquals(null, payload["capabilities"])
  }
}

private fun writeMcpTelemetryConfig(tempDir: Path, level: String): Path {
  val configPath = tempDir.resolve("config.json")
  Files.writeString(
    configPath,
    """
    {
      "install_id": "test-install-id",
      "telemetry": {
        "level": "$level",
        "proxy_url": "",
        "batch_size": 50
      }
    }
    """.trimIndent() + "\n",
  )
  return configPath
}

private fun mcpTelemetryRequester(capturedRequests: MutableList<Map<String, Any?>>): HttpRequester =
  HttpRequester { method, url, bodyJson, headers ->
    capturedRequests +=
      linkedMapOf(
        "method" to method,
        "url" to url,
        "body" to bodyJson,
        "authorization" to headers["Authorization"],
      )
    when {
      url.endsWith("/capabilities") ->
        HttpResponse(
          200,
          """
          {
            "supports_ingest": true,
            "supports_stats": true,
            "supported_workflows": ["bill-feature-verify", "bill-feature-task"],
            "region": "eu"
          }
          """.trimIndent(),
        )

      else ->
        HttpResponse(
          200,
          """
          {
            "status": "ok",
            "workflow": "bill-feature-verify",
            "source": "remote_proxy",
            "started_runs": 14,
            "finished_runs": 12,
            "in_progress_runs": 2,
            "capabilities": {
              "source": "stats_inline",
              "supports_stats": true,
              "inline_only": true
            }
          }
          """.trimIndent(),
        )
    }
  }

private fun expectedMcpRemoteStatsRequests(): List<Map<String, Any?>> = listOf(
  linkedMapOf<String, Any?>(
    "method" to "GET",
    "url" to "https://telemetry.example.dev/ingest/capabilities",
    "body" to null,
    "authorization" to "Bearer stats-token-123",
  ),
  linkedMapOf<String, Any?>(
    "method" to "GET",
    "url" to "https://telemetry.example.dev/ingest/capabilities",
    "body" to null,
    "authorization" to "Bearer stats-token-123",
  ),
  linkedMapOf<String, Any?>(
    "method" to "POST",
    "url" to "https://telemetry.example.dev/ingest/stats",
    "body" to "{\"workflow\":\"bill-feature-verify\",\"date_from\":\"2026-04-01\",\"date_to\":\"2026-04-22\"}",
    "authorization" to "Bearer stats-token-123",
  ),
)

private fun expectedMcpCapabilitiesPayload(): Map<String, Any?> = linkedMapOf(
  "contract_version" to "1",
  "source" to "remote_proxy",
  "proxy_url" to "https://telemetry.example.dev/ingest",
  "capabilities_url" to "https://telemetry.example.dev/ingest/capabilities",
  "supports_ingest" to true,
  "supports_stats" to true,
  "supported_workflows" to listOf("bill-feature-verify", "bill-feature-task"),
  "region" to "eu",
)

private fun expectedMcpRemoteStatsPayload(): Map<String, Any?> = linkedMapOf(
  "status" to "ok",
  "started_runs" to 14,
  "finished_runs" to 12,
  "in_progress_runs" to 2,
  "capabilities" to
    linkedMapOf<String, Any?>(
      "source" to "stats_inline",
      "supports_stats" to true,
      "inline_only" to true,
    ),
  "workflow" to "bill-feature-verify",
  "date_from" to "2026-04-01",
  "date_to" to "2026-04-22",
  "source" to "remote_proxy",
  "stats_url" to "https://telemetry.example.dev/ingest/stats",
)

private fun typedCapabilities(): TelemetryProxyCapabilities = TelemetryProxyCapabilities(
  contractVersion = "1",
  source = "remote_proxy",
  proxyUrl = "https://telemetry.example.dev/ingest",
  capabilitiesUrl = "https://telemetry.example.dev/ingest/capabilities",
  supportsIngest = true,
  supportsStats = true,
  supportedWorkflows = listOf("bill-feature-verify"),
)
