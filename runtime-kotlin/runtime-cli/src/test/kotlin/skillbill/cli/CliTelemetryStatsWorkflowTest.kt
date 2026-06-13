package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.model.HttpResponse
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_URL_ENVIRONMENT_KEY
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class CliTelemetryStatsWorkflowTest {
  @Test
  fun `feature task runtime stats alias remains supported`() {
    val dbPath = Files.createTempDirectory("skillbill-cli-runtime-stats").resolve("metrics.db")

    val result =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "feature-task-runtime-stats", "--format", "json"),
        CliRuntimeContext(),
      )

    val parsed = requireNotNull(JsonSupport.parseObjectOrNull(result.stdout))
    val payload = requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed)))
    assertEquals("feature-task-runtime", payload["workflow"])
  }

  @Test
  fun `telemetry remote stats accepts goal workflow`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-goal-remote-stats")
    val configPath = tempDir.resolve("telemetry.json")
    Files.writeString(
      configPath,
      """
      {
        "install_id": "test-install-id",
        "telemetry": {
          "level": "off",
          "proxy_url": "",
          "batch_size": 50
        }
      }
      """.trimIndent() + "\n",
    )
    val capturedRequests = mutableListOf<Map<String, Any?>>()
    val context =
      CliRuntimeContext(
        environment =
        mapOf(
          CONFIG_ENVIRONMENT_KEY to configPath.toString(),
          TELEMETRY_PROXY_URL_ENVIRONMENT_KEY to "https://telemetry.example.dev/ingest",
          TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY to "stats-token-123",
        ),
        requester = goalStatsRequester(capturedRequests),
      )

    val result =
      CliRuntime.run(
        listOf("telemetry", "stats", "goal", "--date-from", "2026-06-01", "--format", "json"),
        context,
      )

    assertEquals(0, result.exitCode, result.stdout)
    val statsRequest = capturedRequests.single { it["method"] == "POST" }
    val body = statsRequest["body"] as Map<*, *>
    assertEquals("bill-feature-goal", body["workflow"])
  }

  @Test
  fun `telemetry remote stats accepts feature task runtime workflow`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-runtime-remote-stats")
    val configPath = tempDir.resolve("telemetry.json")
    Files.writeString(
      configPath,
      """
      {
        "install_id": "test-install-id",
        "telemetry": {
          "level": "off",
          "proxy_url": "",
          "batch_size": 50
        }
      }
      """.trimIndent() + "\n",
    )
    val capturedRequests = mutableListOf<Map<String, Any?>>()
    val context =
      CliRuntimeContext(
        environment =
        mapOf(
          CONFIG_ENVIRONMENT_KEY to configPath.toString(),
          TELEMETRY_PROXY_URL_ENVIRONMENT_KEY to "https://telemetry.example.dev/ingest",
          TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY to "stats-token-123",
        ),
        requester = runtimeStatsRequester(capturedRequests),
      )

    val result =
      CliRuntime.run(
        listOf(
          "telemetry",
          "stats",
          "feature-task-runtime",
          "--date-from",
          "2026-04-01",
          "--date-to",
          "2026-04-22",
          "--format",
          "json",
        ),
        context,
      )

    assertEquals(0, result.exitCode, result.stdout)
    val statsRequest = capturedRequests.single { it["method"] == "POST" }
    val body = statsRequest["body"] as Map<*, *>
    assertEquals("feature-task-runtime", body["workflow"])
  }
}

private fun goalStatsRequester(capturedRequests: MutableList<Map<String, Any?>>): HttpRequester =
  HttpRequester { method, url, bodyJson, headers ->
    capturedRequests +=
      linkedMapOf(
        "method" to method,
        "url" to url,
        "body" to bodyJson?.let(::decodeRuntimeJsonObject),
        "authorization" to headers["Authorization"],
      )
    when {
      url.endsWith("/capabilities") ->
        HttpResponse(
          statusCode = 200,
          body =
          """
          {
            "contract_version": "1",
            "source": "custom_capabilities",
            "supports_ingest": true,
            "supports_stats": true,
            "supported_workflows": ["bill-feature-verify", "bill-feature-task", "feature-task-runtime", "bill-feature-goal"],
            "region": "eu"
          }
          """.trimIndent(),
        )
      url.endsWith("/stats") ->
        HttpResponse(
          statusCode = 200,
          body =
          """
          {
            "status": "ok",
            "workflow": "bill-feature-goal",
            "date_from": "2026-06-01",
            "source": "remote_proxy",
            "started_runs": 3,
            "finished_runs": 2,
            "in_progress_runs": 1,
            "capabilities": {
              "source": "stats_inline",
              "supports_stats": true,
              "inline_only": true
            }
          }
          """.trimIndent(),
        )
      else -> error("unexpected request $url")
    }
  }

private fun runtimeStatsRequester(capturedRequests: MutableList<Map<String, Any?>>): HttpRequester =
  HttpRequester { method, url, bodyJson, headers ->
    capturedRequests +=
      linkedMapOf(
        "method" to method,
        "url" to url,
        "body" to bodyJson?.let(::decodeRuntimeJsonObject),
        "authorization" to headers["Authorization"],
      )
    when {
      url.endsWith("/capabilities") ->
        HttpResponse(
          statusCode = 200,
          body =
          """
          {
            "contract_version": "1",
            "source": "custom_capabilities",
            "supports_ingest": true,
            "supports_stats": true,
            "supported_workflows": ["bill-feature-verify", "bill-feature-task", "feature-task-runtime"],
            "region": "eu"
          }
          """.trimIndent(),
        )
      url.endsWith("/stats") ->
        HttpResponse(
          statusCode = 200,
          body =
          """
          {
            "status": "ok",
            "workflow": "feature-task-runtime",
            "date_from": "2026-04-01",
            "date_to": "2026-04-22",
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
      else -> error("unexpected request $url")
    }
  }

private fun decodeRuntimeJsonObject(rawJson: String): Map<String, Any?> {
  val parsed = requireNotNull(JsonSupport.parseObjectOrNull(rawJson))
  return requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed)))
}
