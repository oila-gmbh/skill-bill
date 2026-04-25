package skillbill.mcp

import skillbill.SAMPLE_REVIEW
import skillbill.cli.CliRuntime
import skillbill.cli.CliRuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.db.DatabaseRuntime
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.HttpResponse
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.RemoteStatsRequest
import skillbill.telemetry.TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_URL_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpRuntimeTest {
  @Test
  fun `version matches cli system service payload`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-version")
    val env = disabledTelemetryEnvironment(tempDir)
    val cliResult =
      CliRuntime.run(
        listOf("version", "--format", "json"),
        CliRuntimeContext(environment = env, userHome = tempDir),
      )

    assertEquals(0, cliResult.exitCode, cliResult.stdout)
    assertEquals(
      decodeJsonObject(cliResult.stdout),
      McpRuntime.version(McpRuntimeContext(environment = env, userHome = tempDir)),
    )
  }

  @Test
  fun `doctor returns version and db info`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-doctor")
    val env = disabledTelemetryEnvironment(tempDir)

    val result = McpRuntime.doctor(McpRuntimeContext(environment = env, userHome = tempDir))

    assertEquals("0.1.0", result["version"])
    assertEquals(tempDir.resolve("metrics.db").toAbsolutePath().normalize().toString(), result["db_path"])
    assertFalse(result["db_exists"] as Boolean)
    assertEquals(false, result["telemetry_enabled"])
    assertEquals("off", result["telemetry_level"])
  }

  @Test
  fun `doctor matches cli shared system service payload`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-cli-shared-doctor")
    val env = disabledTelemetryEnvironment(tempDir)
    val dbPath = tempDir.resolve("metrics.db")
    val cliResult =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "doctor", "--format", "json"),
        CliRuntimeContext(environment = env, userHome = tempDir),
      )

    assertEquals(0, cliResult.exitCode, cliResult.stdout)
    assertEquals(
      decodeJsonObject(cliResult.stdout),
      McpRuntime.doctor(McpRuntimeContext(environment = env, userHome = tempDir)),
    )
  }

  @Test
  fun `import review and feature stats preserve stable payloads`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-import")
    val env = enabledTelemetryEnvironment(tempDir)

    val context = McpRuntimeContext(environment = env, userHome = tempDir)
    val importResult = McpRuntime.importReview(SAMPLE_REVIEW.trimIndent(), context = context)
    val implementStats = McpRuntime.featureImplementStats(context)
    val verifyStats = McpRuntime.featureVerifyStats(context)

    assertEquals("rvw-20260402-001", importResult["review_run_id"])
    assertEquals("rvs-20260402-001", importResult["review_session_id"])
    assertEquals(2, importResult["finding_count"])
    assertEquals("bill-kotlin-code-review", importResult["routed_skill"])
    assertNotNull(implementStats["db_path"])
    assertNotNull(verifyStats["db_path"])
  }

  @Test
  fun `triage orchestrated returns telemetry payload and suppresses outbox emission`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-orchestrated")
    val env = enabledTelemetryEnvironment(tempDir)
    val dbPath = tempDir.resolve("metrics.db")

    val context = McpRuntimeContext(environment = env, userHome = tempDir)
    val importResult =
      McpRuntime.importReview(
        SAMPLE_REVIEW.trimIndent(),
        orchestrated = true,
        context = context,
      )
    val triageResult =
      McpRuntime.triageFindings(
        reviewRunId = "rvw-20260402-001",
        decisions = listOf("fix=[1,2]"),
        orchestrated = true,
        context = context,
      )

    assertEquals("orchestrated", importResult["mode"])
    assertEquals("orchestrated", triageResult["mode"])
    assertTrue("telemetry_payload" in triageResult)
    val payload = triageResult["telemetry_payload"] as Map<*, *>
    assertEquals("bill-code-review", payload["skill"])
    assertEquals(2, payload["total_findings"])

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val outboxCount =
        connection.createStatement().use { statement ->
          statement.executeQuery(
            "SELECT COUNT(*) FROM telemetry_outbox WHERE event_name = 'skillbill_review_finished'",
          ).use { resultSet ->
            resultSet.next()
            resultSet.getInt(1)
          }
        }
      val orchestratedRun =
        connection.prepareStatement(
          "SELECT orchestrated_run FROM review_runs WHERE review_run_id = ?",
        ).use { statement ->
          statement.setString(1, "rvw-20260402-001")
          statement.executeQuery().use { resultSet ->
            resultSet.next()
            resultSet.getInt(1)
          }
        }
      assertEquals(0, outboxCount)
      assertEquals(1, orchestratedRun)
    }
  }

  @Test
  fun `resolve learnings skips when telemetry is disabled`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-disabled")
    val env = disabledTelemetryEnvironment(tempDir)

    val result = McpRuntime.resolveLearnings(context = McpRuntimeContext(environment = env, userHome = tempDir))

    assertEquals("skipped", result["status"])
    assertEquals("telemetry is disabled", result["reason"])
    assertEquals("none", result["applied_learnings"])
    assertEquals(emptyList<Map<String, Any?>>(), result["learnings"])
  }

  @Test
  fun `resolve learnings returns stable payload when telemetry is enabled`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-learnings")
    val env = enabledTelemetryEnvironment(tempDir)
    seedLearningScenario(tempDir, env)

    val result =
      McpRuntime.resolveLearnings(
        skill = "bill-kotlin-code-review",
        reviewSessionId = "rvs-20260402-001",
        context = McpRuntimeContext(environment = env, userHome = tempDir),
      )

    assertEquals("bill-kotlin-code-review", result["skill_name"])
    assertEquals(
      decodeJsonObject(
        goldenJson(
          "mcp-resolve-learnings.json",
          "<DB_PATH>" to tempDir.resolve("metrics.db").toAbsolutePath().normalize().toString(),
        ),
      ),
      result,
    )
    assertEquals(listOf("skill", "repo", "global"), result["scope_precedence"])
    assertEquals("L-001", result["applied_learnings"])
    assertEquals("rvs-20260402-001", result["review_session_id"])
    val entries = result["learnings"] as List<*>
    val firstEntry = entries.first() as Map<*, *>
    assertEquals("L-001", firstEntry["reference"])
    assertEquals("skill", firstEntry["scope"])
    assertEquals("Keep wording aligned", firstEntry["title"])
  }

  @Test
  fun `mcp import review honors overridden user home when resolving telemetry config`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-user-home")
    Files.createDirectories(tempDir.resolve(".skill-bill"))
    Files.writeString(
      tempDir.resolve(".skill-bill").resolve("config.json"),
      """
      {
        "install_id": "user-home-install-id",
        "telemetry": {
          "level": "anonymous",
          "proxy_url": "",
          "batch_size": 50
        }
      }
      """.trimIndent() + "\n",
    )
    val env = mapOf("SKILL_BILL_REVIEW_DB" to tempDir.resolve("metrics.db").toString())

    val result =
      McpRuntime.importReview(
        SAMPLE_REVIEW.trimIndent(),
        context = McpRuntimeContext(environment = env, userHome = tempDir),
      )

    assertEquals("rvw-20260402-001", result["review_run_id"])
    assertEquals(2, result["finding_count"])
    assertEquals("bill-kotlin-code-review", result["routed_skill"])
  }

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

    assertTrue(capabilities["supports_stats"] == true)
    assertEquals("bill-feature-verify", stats["workflow"])
    assertEquals(14, stats["started_runs"])
    val expectedRequests: List<Map<String, Any?>> =
      listOf(
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
    assertEquals(expectedRequests, capturedRequests)
  }
}

private fun enabledTelemetryEnvironment(tempDir: Path): Map<String, String> {
  val configPath = tempDir.resolve("config.json")
  Files.writeString(
    configPath,
    """
    {
      "install_id": "test-install-id",
      "telemetry": {
        "level": "anonymous",
        "proxy_url": "",
        "batch_size": 50
      }
    }
    """.trimIndent() + "\n",
  )
  return mapOf(
    "SKILL_BILL_REVIEW_DB" to tempDir.resolve("metrics.db").toString(),
    CONFIG_ENVIRONMENT_KEY to configPath.toString(),
  )
}

private fun disabledTelemetryEnvironment(tempDir: Path): Map<String, String> {
  val configPath = tempDir.resolve("config.json")
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
  return mapOf(
    "SKILL_BILL_REVIEW_DB" to tempDir.resolve("metrics.db").toString(),
    CONFIG_ENVIRONMENT_KEY to configPath.toString(),
  )
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

private fun seedLearningScenario(tempDir: Path, env: Map<String, String>) {
  val dbPath = tempDir.resolve("metrics.db")
  assertMcpCliSuccess(
    CliRuntime.run(
      listOf("--db", dbPath.toString(), "import-review", "-", "--format", "json"),
      CliRuntimeContext(environment = env, userHome = tempDir, stdinText = SAMPLE_REVIEW.trimIndent()),
    ),
  )
  assertMcpCliSuccess(
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
      CliRuntimeContext(environment = env, userHome = tempDir),
    ),
  )
  assertMcpCliSuccess(
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
      CliRuntimeContext(environment = env, userHome = tempDir),
    ),
  )
}

private fun assertMcpCliSuccess(result: skillbill.cli.CliExecutionResult) {
  assertEquals(0, result.exitCode, result.stdout)
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
            "supported_workflows": ["bill-feature-verify", "bill-feature-implement"]
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
            "started_runs": 14
          }
          """.trimIndent(),
        )
    }
  }

private fun decodeJsonObject(rawJson: String): Map<String, Any?> {
  val parsed = JsonSupport.parseObjectOrNull(rawJson)
  require(parsed != null) { "Expected JSON object but got: $rawJson" }
  val decoded = JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
  require(decoded != null) { "Expected decoded JSON object but got: $rawJson" }
  return decoded
}

private fun goldenJson(fileName: String, vararg replacements: Pair<String, String>): String {
  var expected = Files.readString(Path.of("src/test/resources/golden").resolve(fileName)).trim()
  replacements.forEach { (placeholder, value) ->
    expected = expected.replace(placeholder, value)
  }
  return expected
}
