package skillbill.cli

import skillbill.SAMPLE_REVIEW
import skillbill.contracts.JsonSupport
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.HttpRequester
import skillbill.telemetry.INSTALL_ID_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_URL_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliRuntimeTest {
  @Test
  fun `import-review emits stable json payload`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-import")
    val dbPath = tempDir.resolve("metrics.db")
    val reviewPath = tempDir.resolve("review.txt")
    Files.writeString(reviewPath, SAMPLE_REVIEW.trimIndent() + "\n")

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          dbPath.toString(),
          "import-review",
          reviewPath.toString(),
          "--format",
          "json",
        ),
        CliRuntimeContext(),
      )

    val payload = decodeJsonObject(result.stdout)
    assertEquals(0, result.exitCode)
    assertEquals(dbPath.toAbsolutePath().normalize().toString(), payload["db_path"])
    assertEquals("rvw-20260402-001", payload["review_run_id"])
    assertEquals("rvs-20260402-001", payload["review_session_id"])
    assertEquals(2, payload["finding_count"])
    assertEquals("bill-kotlin-code-review", payload["routed_skill"])
  }

  @Test
  fun `triage text output mirrors numbered decision lines`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-triage")
    val dbPath = tempDir.resolve("metrics.db")

    CliRuntime.run(
      listOf("--db", dbPath.toString(), "import-review", "-", "--format", "json"),
      CliRuntimeContext(stdinText = SAMPLE_REVIEW.trimIndent()),
    )

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          dbPath.toString(),
          "triage",
          "--run-id",
          "rvw-20260402-001",
          "--decision",
          "1 fix - patched",
          "--decision",
          "2 reject - intentional",
        ),
        CliRuntimeContext(),
      )

    assertEquals(0, result.exitCode)
    assertContains(result.stdout, "review_run_id: rvw-20260402-001")
    assertContains(result.stdout, "1. F-001 -> fix_applied | note: patched")
    assertContains(result.stdout, "2. F-002 -> fix_rejected | note: intentional")
  }

  @Test
  fun `learnings resolve text output preserves scope summary`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-learnings")
    val dbPath = tempDir.resolve("metrics.db")
    seedLearningScenario(dbPath)

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          dbPath.toString(),
          "learnings",
          "resolve",
          "--skill",
          "bill-kotlin-code-review",
        ),
        CliRuntimeContext(),
      )

    assertEquals(0, result.exitCode)
    assertContains(result.stdout, "scope_precedence: skill > repo > global")
    assertContains(result.stdout, "skill_name: bill-kotlin-code-review")
    assertContains(result.stdout, "applied_learnings: L-001")
    assertContains(
      result.stdout,
      "- [L-001] skill:bill-kotlin-code-review | Keep wording aligned | " +
        "Update the installer prompt when routing text changes.",
    )
  }

  @Test
  fun `telemetry status and remote stats preserve payload contract`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-telemetry")
    val dbPath = tempDir.resolve("metrics.db")
    val configPath = writeTelemetryConfig(tempDir, "off")
    telemetryStatusPayload(dbPath, configPath)
    val (statsPayload, capturedRequests) = remoteStatsScenario(configPath)
    assertEquals("bill-feature-verify", statsPayload["workflow"])
    assertEquals(14, statsPayload["started_runs"])
    assertTrue((statsPayload["capabilities"] as Map<*, *>)["supports_stats"] == true)
    assertEquals(expectedCliRemoteStatsRequests(), capturedRequests)
  }

  @Test
  fun `doctor and version expose stable metadata`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-doctor")
    val dbPath = tempDir.resolve("metrics.db")
    val configPath = tempDir.resolve("config.json")
    Files.writeString(
      configPath,
      """
      {
        "install_id": "doctor-install-id",
        "telemetry": {
          "level": "anonymous",
          "proxy_url": "",
          "batch_size": 50
        }
      }
      """.trimIndent() + "\n",
    )
    val context =
      CliRuntimeContext(
        environment =
        mapOf(
          CONFIG_ENVIRONMENT_KEY to configPath.toString(),
          INSTALL_ID_ENVIRONMENT_KEY to "doctor-install-id",
        ),
      )

    val versionResult = CliRuntime.run(listOf("version", "--format", "json"), context)
    assertEquals("0.1.0", decodeJsonObject(versionResult.stdout)["version"])

    val doctorResult =
      CliRuntime.run(listOf("--db", dbPath.toString(), "doctor", "--format", "json"), context)
    val doctorPayload = decodeJsonObject(doctorResult.stdout)
    assertEquals("0.1.0", doctorPayload["version"])
    assertEquals(dbPath.toAbsolutePath().normalize().toString(), doctorPayload["db_path"])
    assertFalse(doctorPayload["db_exists"] as Boolean)
    assertEquals(true, doctorPayload["telemetry_enabled"])
    assertEquals("anonymous", doctorPayload["telemetry_level"])
  }
}

private fun decodeJsonObject(rawJson: String): Map<String, Any?> {
  val parsed = JsonSupport.parseObjectOrNull(rawJson)
  require(parsed != null) { "Expected JSON object but got: $rawJson" }
  val decoded = JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
  require(decoded != null) { "Expected decoded JSON object but got: $rawJson" }
  return decoded
}

private fun seedLearningScenario(dbPath: Path) {
  CliRuntime.run(
    listOf("--db", dbPath.toString(), "import-review", "-", "--format", "json"),
    CliRuntimeContext(stdinText = SAMPLE_REVIEW.trimIndent()),
  )
  CliRuntime.run(
    listOf(
      "--db",
      dbPath.toString(),
      "triage",
      "--run-id",
      "rvw-20260402-001",
      "--decision",
      "2 reject - intentional",
      "--format",
      "json",
    ),
    CliRuntimeContext(),
  )
  CliRuntime.run(
    listOf(
      "--db",
      dbPath.toString(),
      "learnings",
      "add",
      "--scope",
      "skill",
      "--scope-key",
      "bill-kotlin-code-review",
      "--title",
      "Keep wording aligned",
      "--rule",
      "Update the installer prompt when routing text changes.",
      "--from-run",
      "rvw-20260402-001",
      "--from-finding",
      "F-002",
      "--format",
      "json",
    ),
    CliRuntimeContext(),
  )
}

private fun writeTelemetryConfig(tempDir: Path, level: String): Path {
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

private fun statsRequester(capturedRequests: MutableList<Map<String, Any?>>): HttpRequester =
  HttpRequester { method, url, bodyJson, headers ->
    capturedRequests +=
      linkedMapOf(
        "method" to method,
        "url" to url,
        "body" to bodyJson?.let(::decodeJsonObject),
        "authorization" to headers["Authorization"],
      )
    when {
      url.endsWith("/capabilities") ->
        skillbill.telemetry.HttpResponse(
          200,
          """
          {
            "contract_version": "1",
            "supports_ingest": true,
            "supports_stats": true,
            "supported_workflows": ["bill-feature-verify", "bill-feature-implement"]
          }
          """.trimIndent(),
        )

      else ->
        skillbill.telemetry.HttpResponse(
          200,
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
    }
  }

private fun telemetryStatusPayload(dbPath: Path, configPath: Path): Map<String, Any?> {
  val statusContext =
    CliRuntimeContext(environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath.toString()))
  val statusResult =
    CliRuntime.run(
      listOf("--db", dbPath.toString(), "telemetry", "status", "--format", "json"),
      statusContext,
    )
  val payload = decodeJsonObject(statusResult.stdout)
  assertEquals(false, payload["telemetry_enabled"])
  assertEquals("off", payload["telemetry_level"])
  assertEquals("disabled", payload["sync_target"])
  return payload
}

private fun remoteStatsScenario(configPath: Path): Pair<Map<String, Any?>, List<Map<String, Any?>>> {
  val capturedRequests = mutableListOf<Map<String, Any?>>()
  val statsContext =
    CliRuntimeContext(
      environment =
      mapOf(
        CONFIG_ENVIRONMENT_KEY to configPath.toString(),
        TELEMETRY_PROXY_URL_ENVIRONMENT_KEY to "https://telemetry.example.dev/ingest",
        TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY to "stats-token-123",
      ),
      requester = statsRequester(capturedRequests),
    )
  val statsResult =
    CliRuntime.run(
      listOf(
        "telemetry",
        "stats",
        "verify",
        "--date-from",
        "2026-04-01",
        "--date-to",
        "2026-04-22",
        "--format",
        "json",
      ),
      statsContext,
    )
  return decodeJsonObject(statsResult.stdout) to capturedRequests
}

private fun expectedCliRemoteStatsRequests(): List<Map<String, Any?>> = listOf(
  linkedMapOf<String, Any?>(
    "method" to "GET",
    "url" to "https://telemetry.example.dev/ingest/capabilities",
    "body" to null,
    "authorization" to "Bearer stats-token-123",
  ),
  linkedMapOf<String, Any?>(
    "method" to "POST",
    "url" to "https://telemetry.example.dev/ingest/stats",
    "body" to
      linkedMapOf<String, Any?>(
        "date_from" to "2026-04-01",
        "date_to" to "2026-04-22",
        "workflow" to "bill-feature-verify",
      ),
    "authorization" to "Bearer stats-token-123",
  ),
)
