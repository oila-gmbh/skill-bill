package skillbill.mcp

import skillbill.SAMPLE_REVIEW
import skillbill.ZERO_FINDING_REVIEW
import skillbill.application.model.FeatureImplementFinishedRequest
import skillbill.application.model.FeatureImplementStartedRequest
import skillbill.application.model.FeatureVerifyFinishedRequest
import skillbill.application.model.FeatureVerifyStartedRequest
import skillbill.application.model.PrDescriptionGeneratedRequest
import skillbill.application.model.QualityCheckFinishedRequest
import skillbill.application.model.QualityCheckStartedRequest
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.cli.CliRuntime
import skillbill.cli.CliRuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.db.DatabaseRuntime
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.model.HttpResponse
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_URL_ENVIRONMENT_KEY
import skillbill.telemetry.model.RemoteStatsRequest
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
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

    assertGoldenPayload(
      "mcp-doctor.json",
      result,
      "<DB_PATH>" to tempDir.resolve("metrics.db").toAbsolutePath().normalize().toString(),
    )
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

    assertGoldenPayload(
      "mcp-import-review.json",
      importResult,
      "<DB_PATH>" to tempDir.resolve("metrics.db").toAbsolutePath().normalize().toString(),
    )
    assertEquals("rvw-20260402-001", importResult["review_run_id"])
    assertEquals("rvs-20260402-001", importResult["review_session_id"])
    assertEquals(2, importResult["finding_count"])
    assertEquals("bill-kotlin-code-review", importResult["routed_skill"])
    assertNotNull(implementStats["db_path"])
    assertNotNull(verifyStats["db_path"])
  }

  @Test
  fun `standalone zero finding import emits review finished telemetry`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-zero-finding-import")
    val env = enabledTelemetryEnvironment(tempDir)
    val dbPath = tempDir.resolve("metrics.db")
    val context = McpRuntimeContext(environment = env, userHome = tempDir)

    val importResult = McpRuntime.importReview(ZERO_FINDING_REVIEW.trimIndent(), context = context)

    assertEquals("rvw-20260427-empty", importResult["review_run_id"])
    assertEquals(0, importResult["finding_count"])
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(
        1,
        scalarInt(
          connection,
          "SELECT COUNT(*) FROM telemetry_outbox WHERE event_name = 'skillbill_review_finished'",
        ),
      )
      assertEquals(
        1,
        scalarInt(
          connection,
          """
          SELECT COUNT(*)
          FROM review_runs
          WHERE review_run_id = 'rvw-20260427-empty'
            AND review_finished_at IS NOT NULL
            AND review_finished_event_emitted_at IS NOT NULL
          """.trimIndent(),
        ),
      )
    }
  }

  @Test
  fun `new skill scaffold preserves standalone and orchestrated payload envelopes`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-scaffold")
    val env = enabledTelemetryEnvironment(tempDir)
    val context = McpRuntimeContext(environment = env, userHome = tempDir)
    val payload =
      mapOf(
        "scaffold_payload_version" to "1.0",
        "kind" to "horizontal",
        "name" to "bill-horizontal-mcp",
      )

    val standalone = McpRuntime.newSkillScaffold(payload, dryRun = true, orchestrated = false, context = context)
    val orchestrated = McpRuntime.newSkillScaffold(payload, dryRun = true, orchestrated = true, context = context)
    val standaloneSessionId = standalone["session_id"] as String
    val standaloneSkillPath = standalone["skill_path"] as String

    assertMatchesPattern(Regex("""^nss-\d{8}-[0-9a-f]{4}$"""), standaloneSessionId, "session_id")
    assertTrue(Path.of(standaloneSkillPath).isAbsolute, "Expected absolute skill_path but got $standaloneSkillPath")
    assertTrue(
      standaloneSkillPath.endsWith("/skills/bill-horizontal-mcp"),
      "Expected skill_path to target skills/bill-horizontal-mcp but got $standaloneSkillPath",
    )
    assertGoldenPayload(
      "mcp-new-skill-scaffold.json",
      standalone,
      "<SESSION_ID>" to standaloneSessionId,
      "<SKILL_PATH>" to standaloneSkillPath,
    )
    assertEquals("ok", standalone["status"])
    assertTrue("session_id" in standalone)
    assertTrue("skill_path" in standalone)
    assertTrue("notes" in standalone)
    assertTrue("mode" !in standalone)
    assertTrue("telemetry_payload" !in standalone)

    assertEquals("orchestrated", orchestrated["mode"])
    assertTrue("telemetry_payload" in orchestrated)
    val telemetryPayload = orchestrated["telemetry_payload"] as Map<*, *>
    assertEquals("bill-create-skill", telemetryPayload["skill"])
    assertEquals("dry-run", telemetryPayload["result"])
    assertEquals("horizontal", telemetryPayload["kind"])
    assertEquals("bill-horizontal-mcp", telemetryPayload["skill_name"])
    assertEquals(standaloneSkillPath, orchestrated["skill_path"])
    assertTrue("skill_path" in orchestrated)
    assertTrue("notes" in orchestrated)
  }

  @Test
  fun `lifecycle telemetry tools persist natively and emit outbox events`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-lifecycle")
    val env = enabledTelemetryEnvironment(tempDir)
    val dbPath = tempDir.resolve("metrics.db")
    val context = McpRuntimeContext(environment = env, userHome = tempDir)

    recordFeatureImplementLifecycle(context)
    recordQualityCheckLifecycle(context)
    recordFeatureVerifyLifecycle(context)
    recordPrDescriptionLifecycle(context)

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertLifecyclePersistence(connection)
    }
  }

  @Test
  fun `feature implement lifecycle rejects defaulted incomplete telemetry fields`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-feature-implement-invalid")
    val env = enabledTelemetryEnvironment(tempDir)
    val context = McpRuntimeContext(environment = env, userHome = tempDir)

    val started =
      McpToolDispatcher.call(
        "feature_implement_started",
        mapOf(
          "feature_size" to "SMALL",
          "feature_name" to "read-path-config-variant-resolution",
          "issue_key" to "SKILL-32",
        ),
        context,
      )
    val finished =
      McpRuntime.featureImplementFinished(
        FeatureImplementFinishedRequest(
          sessionId = "fis-invalid",
          completionStatus = "completed",
          planCorrectionCount = 0,
          planTaskCount = 1,
          planPhaseCount = 1,
          featureFlagUsed = false,
          filesCreated = 0,
          filesModified = 1,
          tasksCompleted = 1,
          reviewIterations = 0,
          auditResult = "all_pass",
          auditIterations = 1,
          validationResult = "pass",
          boundaryHistoryWritten = true,
          prCreated = false,
          featureFlagPattern = "none",
          boundaryHistoryValue = "medium",
          planDeviationNotes = "",
          childSteps = emptyList(),
        ),
        context,
      )

    assertEquals("error", started["status"])
    assertEquals("issue_key_type must not be 'none' when issue_key is provided.", started["error"])
    assertEquals("error", finished["status"])
    assertEquals("review_iterations must be greater than 0.", finished["error"])
    assertFalse(Files.exists(tempDir.resolve("metrics.db")))
  }

  @Test
  fun `orchestrated lifecycle telemetry returns child payloads without outbox events`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-lifecycle-orchestrated")
    val env = enabledTelemetryEnvironment(tempDir)
    val context = McpRuntimeContext(environment = env, userHome = tempDir)

    val started =
      McpRuntime.qualityCheckStarted(
        QualityCheckStartedRequest(
          routedSkill = "bill-kotlin-quality-check",
          detectedStack = "kotlin",
          scopeType = "repo",
          initialFailureCount = 0,
          orchestrated = true,
        ),
        context,
      )
    val finished =
      McpRuntime.qualityCheckFinished(
        QualityCheckFinishedRequest(
          finalFailureCount = 0,
          iterations = 1,
          result = "pass",
          sessionId = "",
          failingCheckNames = emptyList(),
          unsupportedReason = "",
          orchestrated = true,
          routedSkill = "bill-kotlin-quality-check",
          detectedStack = "kotlin",
          scopeType = "repo",
          initialFailureCount = 0,
          durationSeconds = 5,
        ),
        context,
      )
    val payload = finished["telemetry_payload"] as Map<*, *>

    assertEquals("skipped_in_orchestrated_mode", started["status"])
    assertEquals("orchestrated", finished["mode"])
    assertEquals("bill-quality-check", payload["skill"])
    assertFalse("session_id" in payload)
    assertFalse(Files.exists(tempDir.resolve("metrics.db")))
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

    assertOrchestratedReviewGoldens(dbPath, importResult, triageResult)
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
  fun `triage accepts individual numbered decisions`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-individual-triage")
    val env = enabledTelemetryEnvironment(tempDir)
    val context = McpRuntimeContext(environment = env, userHome = tempDir)

    McpRuntime.importReview(SAMPLE_REVIEW.trimIndent(), context = context)
    val triageResult =
      McpRuntime.triageFindings(
        reviewRunId = "rvw-20260402-001",
        decisions = listOf("1 fix", "2 reject"),
        context = context,
      )

    val recorded = triageResult["recorded"] as List<*>
    assertEquals(2, recorded.size)
    assertEquals("fix_applied", requireNotNull(JsonSupport.anyToStringAnyMap(recorded[0]))["outcome_type"])
    assertEquals("fix_rejected", requireNotNull(JsonSupport.anyToStringAnyMap(recorded[1]))["outcome_type"])
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
      .withTestTelemetryProxy()

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

  @Test
  fun `mcp workflow methods cover implement verbs and blocked continuation`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-workflow")
    val env = disabledTelemetryEnvironment(tempDir)
    val context = McpRuntimeContext(environment = env, userHome = tempDir)
    val opened = McpWorkflowRuntime.open(
      WorkflowFamilyKind.IMPLEMENT,
      sessionId = "fis-20260425-mcp",
      context = context,
    )
    val workflowId = opened["workflow_id"] as String
    assertWorkflowIdShape(workflowId, "wfl")
    assertSqliteTimestampShape(opened["started_at"].toString(), "implement started_at")
    assertEquals(opened["started_at"], opened["updated_at"])

    val updated =
      McpWorkflowRuntime.update(
        WorkflowFamilyKind.IMPLEMENT,
        WorkflowUpdateRequest(
          workflowId = workflowId,
          workflowStatus = "blocked",
          currentStepId = "implement",
          stepUpdates = listOf(mapOf("step_id" to "implement", "status" to "blocked", "attempt_count" to 1)),
          artifactsPatch = mapOf("preplan_digest" to mapOf("ok" to true)),
        ),
        context,
      )
    val listed = McpWorkflowRuntime.list(WorkflowFamilyKind.IMPLEMENT, context = context)
    val latest = McpWorkflowRuntime.latest(WorkflowFamilyKind.IMPLEMENT, context)
    val got = McpWorkflowRuntime.get(WorkflowFamilyKind.IMPLEMENT, workflowId, context)
    val resumed = McpWorkflowRuntime.resume(WorkflowFamilyKind.IMPLEMENT, workflowId, context)
    val continued = McpWorkflowRuntime.continueWorkflow(WorkflowFamilyKind.IMPLEMENT, workflowId, context)
    val updatedAt = updated["updated_at"].toString()

    assertSqliteTimestampShape(updatedAt, "implement updated_at")
    assertEquals(opened["started_at"], updated["started_at"])
    assertTrue(updatedAt >= opened["started_at"].toString())

    assertGoldenPayload(
      "mcp-feature-implement-workflow.json",
      mapOf(
        "open" to opened,
        "update" to updated,
        "list" to listed,
        "latest" to latest,
        "get" to got,
        "resume" to resumed,
        "continue" to continued,
      ),
      "<DB_PATH>" to tempDir.resolve("metrics.db").toAbsolutePath().normalize().toString(),
      "<WORKFLOW_ID>" to workflowId,
      "<STARTED_AT>" to opened["started_at"].toString(),
      "<UPDATED_AT>" to updated["updated_at"].toString(),
    )
    assertEquals("blocked", updated["workflow_status"])
    assertEquals(1, listed["workflow_count"])
    assertEquals(workflowId, latest["workflow_id"])
    assertEquals(workflowId, got["workflow_id"])
    assertEquals(listOf("plan"), resumed["missing_artifacts"])
    assertEquals("error", continued["status"])
    assertEquals("blocked", continued["continue_status"])
  }

  @Test
  fun `mcp workflow methods cover verify verbs and reopened continuation`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-verify-workflow")
    val env = disabledTelemetryEnvironment(tempDir)
    val context = McpRuntimeContext(environment = env, userHome = tempDir)
    val opened = McpWorkflowRuntime.open(WorkflowFamilyKind.VERIFY, currentStepId = "code_review", context = context)
    val workflowId = opened["workflow_id"] as String
    assertWorkflowIdShape(workflowId, "wfv")
    assertSqliteTimestampShape(opened["started_at"].toString(), "verify started_at")
    assertEquals(opened["started_at"], opened["updated_at"])

    val updated = markVerifyWorkflowVerdictBlocked(workflowId, context)

    val listed = McpWorkflowRuntime.list(WorkflowFamilyKind.VERIFY, context = context)
    val latest = McpWorkflowRuntime.latest(WorkflowFamilyKind.VERIFY, context)
    val got = McpWorkflowRuntime.get(WorkflowFamilyKind.VERIFY, workflowId, context)
    val resumed = McpWorkflowRuntime.resume(WorkflowFamilyKind.VERIFY, workflowId, context)
    val continued = McpWorkflowRuntime.continueWorkflow(WorkflowFamilyKind.VERIFY, workflowId, context)
    val continuedAt = continued["updated_at"].toString()

    assertSqliteTimestampShape(got["updated_at"].toString(), "verify updated_at")
    assertSqliteTimestampShape(continuedAt, "verify continued_at")
    assertEquals(opened["started_at"], got["started_at"])
    assertTrue(continuedAt >= got["updated_at"].toString())

    assertGoldenPayload(
      "mcp-feature-verify-workflow.json",
      mapOf(
        "open" to opened,
        "update" to updated,
        "list" to listed,
        "latest" to latest,
        "get" to got,
        "resume" to resumed,
        "continue" to continued,
      ),
      "<DB_PATH>" to tempDir.resolve("metrics.db").toAbsolutePath().normalize().toString(),
      "<WORKFLOW_ID>" to workflowId,
      "<STARTED_AT>" to opened["started_at"].toString(),
      "<UPDATED_AT>" to got["updated_at"].toString(),
      "<CONTINUED_AT>" to continued["updated_at"].toString(),
    )
    assertEquals(1, listed["workflow_count"])
    assertEquals(workflowId, latest["workflow_id"])
    assertEquals("verdict", got["current_step_id"])
    assertEquals("resume", resumed["resume_mode"])
    assertEquals("ok", continued["status"])
    assertEquals("reopened", continued["continue_status"])
  }
}

private fun markVerifyWorkflowVerdictBlocked(workflowId: String, context: McpRuntimeContext): Map<String, *> =
  McpWorkflowRuntime.update(
    WorkflowFamilyKind.VERIFY,
    WorkflowUpdateRequest(
      workflowId = workflowId,
      workflowStatus = "running",
      currentStepId = "verdict",
      stepUpdates = listOf(mapOf("step_id" to "verdict", "status" to "blocked", "attempt_count" to 1)),
      artifactsPatch =
      mapOf(
        "criteria_summary" to emptyMap<String, Nothing?>(),
        "diff_summary" to emptyMap(),
        "review_result" to emptyMap(),
        "completeness_audit_result" to emptyMap(),
      ),
    ),
    context = context,
  )

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
  ).withTestTelemetryProxy()
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

private fun Map<String, String>.withTestTelemetryProxy(): Map<String, String> =
  this + (TELEMETRY_PROXY_URL_ENVIRONMENT_KEY to TEST_TELEMETRY_PROXY_URL)

private const val TEST_TELEMETRY_PROXY_URL = "http://127.0.0.1:9/skill-bill-test-telemetry"

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

private fun recordFeatureImplementLifecycle(context: McpRuntimeContext) {
  val started =
    McpRuntime.featureImplementStarted(
      FeatureImplementStartedRequest(
        featureSize = "MEDIUM",
        acceptanceCriteriaCount = 3,
        openQuestionsCount = 0,
        specInputTypes = listOf("markdown_file"),
        specWordCount = 250,
        rolloutNeeded = false,
        featureName = "native-lifecycle",
        issueKey = "SKILL-27",
        issueKeyType = "other",
        specSummary = "Port lifecycle telemetry",
      ),
      context,
    )
  McpRuntime.featureImplementFinished(
    FeatureImplementFinishedRequest(
      sessionId = started["session_id"] as String,
      completionStatus = "completed",
      planCorrectionCount = 0,
      planTaskCount = 4,
      planPhaseCount = 1,
      featureFlagUsed = false,
      filesCreated = 1,
      filesModified = 2,
      tasksCompleted = 4,
      reviewIterations = 1,
      auditResult = "all_pass",
      auditIterations = 1,
      validationResult = "pass",
      boundaryHistoryWritten = true,
      prCreated = false,
      featureFlagPattern = "none",
      boundaryHistoryValue = "medium",
      planDeviationNotes = "",
      childSteps = listOf(mapOf("skill" to "bill-quality-check", "result" to "pass")),
    ),
    context,
  )
}

private fun recordQualityCheckLifecycle(context: McpRuntimeContext) {
  val started =
    McpRuntime.qualityCheckStarted(
      QualityCheckStartedRequest(
        routedSkill = "bill-kotlin-quality-check",
        detectedStack = "kotlin",
        scopeType = "branch_diff",
        initialFailureCount = 1,
        orchestrated = false,
      ),
      context,
    )
  McpRuntime.qualityCheckFinished(
    QualityCheckFinishedRequest(
      sessionId = started["session_id"] as String,
      finalFailureCount = 0,
      iterations = 2,
      result = "pass",
      failingCheckNames = emptyList(),
      unsupportedReason = "",
      orchestrated = false,
      routedSkill = "",
      detectedStack = "",
      scopeType = "",
      initialFailureCount = 0,
      durationSeconds = 0,
    ),
    context,
  )
}

private fun recordFeatureVerifyLifecycle(context: McpRuntimeContext) {
  val started =
    McpRuntime.featureVerifyStarted(
      FeatureVerifyStartedRequest(
        acceptanceCriteriaCount = 2,
        rolloutRelevant = true,
        specSummary = "Verify native lifecycle",
        orchestrated = false,
      ),
      context,
    )
  McpRuntime.featureVerifyFinished(
    FeatureVerifyFinishedRequest(
      sessionId = started["session_id"] as String,
      featureFlagAuditPerformed = true,
      reviewIterations = 1,
      auditResult = "had_gaps",
      completionStatus = "completed",
      historyRelevance = "low",
      historyHelpfulness = "medium",
      gapsFound = listOf("missing QA"),
      orchestrated = false,
      acceptanceCriteriaCount = 0,
      rolloutRelevant = false,
      specSummary = "",
      durationSeconds = 0,
    ),
    context,
  )
}

private fun recordPrDescriptionLifecycle(context: McpRuntimeContext) {
  McpRuntime.prDescriptionGenerated(
    PrDescriptionGeneratedRequest(
      commitCount = 3,
      filesChangedCount = 8,
      wasEditedByUser = false,
      prCreated = false,
      prTitle = "SKILL-27 native lifecycle",
      orchestrated = false,
    ),
    context,
  )
}

private fun assertLifecyclePersistence(connection: Connection) {
  assertEquals(
    mapOf(
      "skillbill_feature_implement_started" to 1,
      "skillbill_feature_implement_finished" to 1,
      "skillbill_quality_check_started" to 1,
      "skillbill_quality_check_finished" to 1,
      "skillbill_feature_verify_started" to 1,
      "skillbill_feature_verify_finished" to 1,
      "skillbill_pr_description_generated" to 1,
    ),
    outboxEventCounts(connection),
  )
  assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM feature_implement_sessions"))
  assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM quality_check_sessions"))
  assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM feature_verify_sessions"))
}

private fun outboxEventCounts(connection: Connection): Map<String, Int> =
  connection.createStatement().use { statement ->
    statement.executeQuery(
      """
      SELECT event_name, COUNT(*) AS count
      FROM telemetry_outbox
      GROUP BY event_name
      ORDER BY event_name
      """.trimIndent(),
    ).use { resultSet ->
      buildMap {
        while (resultSet.next()) {
          put(resultSet.getString("event_name"), resultSet.getInt("count"))
        }
      }
    }
  }

private fun scalarInt(connection: Connection, sql: String): Int = connection.createStatement().use { statement ->
  statement.executeQuery(sql).use { resultSet ->
    resultSet.next()
    resultSet.getInt(1)
  }
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

private fun assertGoldenPayload(fileName: String, payload: Map<String, *>, vararg replacements: Pair<String, String>) {
  assertEquals(decodeJsonObject(goldenJson(fileName, *replacements)), payload)
}

private fun assertWorkflowIdShape(workflowId: String, prefix: String) {
  assertMatchesPattern(Regex("""^$prefix-\d{8}-\d{6}-[a-z0-9]{4}$"""), workflowId, "workflow_id")
}

private fun assertSqliteTimestampShape(timestamp: String, label: String) {
  assertMatchesPattern(Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$"""), timestamp, label)
}

private fun assertMatchesPattern(pattern: Regex, value: String, label: String) {
  assertTrue(pattern.matches(value), "Expected $label to match ${pattern.pattern} but got $value")
}

private fun assertOrchestratedReviewGoldens(dbPath: Path, importResult: Map<String, *>, triageResult: Map<String, *>) {
  val telemetryPayload = triageResult["telemetry_payload"] as Map<*, *>
  assertGoldenPayload(
    "mcp-import-review-orchestrated.json",
    importResult,
    "<DB_PATH>" to dbPath.toAbsolutePath().normalize().toString(),
  )
  assertGoldenPayload(
    "mcp-triage-findings-orchestrated.json",
    triageResult,
    "<DB_PATH>" to dbPath.toAbsolutePath().normalize().toString(),
    "<REVIEW_FINISHED_AT>" to telemetryPayload["review_finished_at"].toString(),
  )
}
