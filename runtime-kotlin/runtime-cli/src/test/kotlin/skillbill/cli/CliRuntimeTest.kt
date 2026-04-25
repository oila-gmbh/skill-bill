package skillbill.cli

import skillbill.SAMPLE_REVIEW
import skillbill.contracts.JsonSupport
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.model.HttpResponse
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
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
    assertEquals(
      goldenJson("cli-import-review.json", "<DB_PATH>" to dbPath.toAbsolutePath().normalize().toString()),
      result.stdout,
    )
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
  fun `review commands cover list stats feedback and aliases`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-review")
    val dbPath = tempDir.resolve("metrics.db")

    importSampleReview(dbPath)

    val listPayload =
      runJson(
        "--db",
        dbPath.toString(),
        "triage",
        "--run-id",
        "rvw-20260402-001",
        "--list",
        "--format",
        "json",
      )
    assertEquals(2, (listPayload["findings"] as List<*>).size)

    val feedbackPayload =
      runJson(
        "--db",
        dbPath.toString(),
        "record-feedback",
        "--run-id",
        "rvw-20260402-001",
        "--event",
        "fix_applied",
        "--finding",
        "F-001",
        "--format",
        "json",
      )
    assertEquals("fix_applied", feedbackPayload["outcome_type"])
    assertEquals(1, feedbackPayload["recorded_findings"])

    val statsPayload = runJson("--db", dbPath.toString(), "stats", "--run-id", "rvw-20260402-001", "--format", "json")
    assertEquals(2, statsPayload["total_findings"])
    assertEquals(1, statsPayload["accepted_findings"])
    assertEquals(1, statsPayload["unresolved_findings"])

    val implementAliasPayload =
      runJson("--db", dbPath.toString(), "feature-implement-stats", "--format", "json")
    val verifyAliasPayload =
      runJson("--db", dbPath.toString(), "feature-verify-stats", "--format", "json")
    assertEquals("bill-feature-implement", implementAliasPayload["workflow"])
    assertEquals("bill-feature-verify", verifyAliasPayload["workflow"])
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
  fun `learnings commands cover lifecycle mutations and validation`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-learnings-lifecycle")
    val dbPath = tempDir.resolve("metrics.db")
    seedLearningScenario(dbPath)

    val listPayload = runJson("--db", dbPath.toString(), "learnings", "list", "--format", "json")
    assertEquals(1, (listPayload["learnings"] as List<*>).size)

    val showPayload = runJson("--db", dbPath.toString(), "learnings", "show", "--id", "1", "--format", "json")
    assertEquals("Keep wording aligned", showPayload["title"])
    assertEquals("active", showPayload["status"])

    val editPayload =
      runJson(
        "--db",
        dbPath.toString(),
        "learnings",
        "edit",
        "--id",
        "1",
        "--title",
        "Keep installer wording aligned",
        "--format",
        "json",
      )
    assertEquals("Keep installer wording aligned", editPayload["title"])

    val disabledPayload = runJson("--db", dbPath.toString(), "learnings", "disable", "--id", "1", "--format", "json")
    assertEquals("disabled", disabledPayload["status"])

    val enabledPayload = runJson("--db", dbPath.toString(), "learnings", "enable", "--id", "1", "--format", "json")
    assertEquals("active", enabledPayload["status"])

    val editWithoutFields =
      CliRuntime.run(listOf("--db", dbPath.toString(), "learnings", "edit", "--id", "1", "--format", "json"))
    assertEquals(1, editWithoutFields.exitCode)
    assertContains(editWithoutFields.stdout, "Learning edit requires at least one field")

    val deletePayload = runJson("--db", dbPath.toString(), "learnings", "delete", "--id", "1", "--format", "json")
    assertEquals(1, deletePayload["deleted_learning_id"])

    val emptyListResult = CliRuntime.run(listOf("--db", dbPath.toString(), "learnings", "list"))
    assertEquals(0, emptyListResult.exitCode)
    assertEquals("No learnings found.\n", emptyListResult.stdout)
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
  fun `telemetry local commands mutate config and sync disabled state`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-telemetry-local")
    val dbPath = tempDir.resolve("metrics.db")
    val configPath = writeTelemetryConfig(tempDir, "off")
    val context = CliRuntimeContext(environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath.toString()))

    val enablePayload =
      runJson(
        listOf("--db", dbPath.toString(), "telemetry", "enable", "--level", "full", "--format", "json"),
        context,
      )
    assertEquals(true, enablePayload["telemetry_enabled"])
    assertEquals("full", enablePayload["telemetry_level"])

    val setLevelPayload =
      runJson(listOf("--db", dbPath.toString(), "telemetry", "set-level", "anonymous", "--format", "json"), context)
    assertEquals("anonymous", setLevelPayload["telemetry_level"])

    val disablePayload = runJson(listOf("--db", dbPath.toString(), "telemetry", "disable", "--format", "json"), context)
    assertEquals(false, disablePayload["telemetry_enabled"])
    assertEquals("off", disablePayload["telemetry_level"])

    val syncPayload = runJson(listOf("--db", dbPath.toString(), "telemetry", "sync", "--format", "json"), context)
    assertEquals("disabled", syncPayload["sync_status"])
    assertEquals("disabled", syncPayload["sync_target"])
  }

  @Test
  fun `telemetry capabilities uses configured requester`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-telemetry-capabilities")
    val configPath = writeTelemetryConfig(tempDir, "anonymous")
    val capturedRequests = mutableListOf<Map<String, Any?>>()
    val context =
      CliRuntimeContext(
        environment =
        mapOf(
          CONFIG_ENVIRONMENT_KEY to configPath.toString(),
          TELEMETRY_PROXY_URL_ENVIRONMENT_KEY to "https://telemetry.example.dev/ingest",
          TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY to "stats-token-123",
        ),
        requester = statsRequester(capturedRequests),
      )

    val payload = runJson(listOf("telemetry", "capabilities", "--format", "json"), context)

    assertEquals(true, payload["supports_ingest"])
    assertEquals(true, payload["supports_stats"])
    assertEquals(listOf(expectedCliRemoteStatsRequests().first()), capturedRequests)
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

  @Test
  fun `help output documents nested clikt commands`() {
    val rootHelp = CliRuntime.run(listOf("--help"))
    val telemetryHelp = CliRuntime.run(listOf("telemetry", "--help"))

    assertEquals(0, rootHelp.exitCode)
    assertContains(rootHelp.stdout, "--generate-completion=(bash|zsh|fish)")
    assertContains(rootHelp.stdout, "learnings")
    assertContains(rootHelp.stdout, "telemetry")
    assertContains(rootHelp.stdout, "new-skill")
    assertContains(rootHelp.stdout, "create-and-fill")
    assertContains(rootHelp.stdout, "new-addon")
    assertContains(rootHelp.stdout, "install")
    assertEquals(0, telemetryHelp.exitCode)
    assertContains(telemetryHelp.stdout, "capabilities")
    assertContains(telemetryHelp.stdout, "set-level")
  }

  @Test
  fun `new skill and new alias share the scaffold payload contract`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-new")
    val context = scaffoldPayloadContext(tempDir)

    val newSkillPayload = scaffoldPayload("new-skill", context)
    val newAliasPayload = scaffoldPayload("new", context)

    assertEquals("horizontal", newSkillPayload["kind"])
    assertEquals("bill-horizontal-kotlin", newSkillPayload["skill_name"])
    assertEquals(true, newSkillPayload["dry_run"])
    assertEquals("bill-horizontal-kotlin", (newSkillPayload["started_payload"] as Map<*, *>)["skill_name"])
    assertEquals("horizontal", newAliasPayload["kind"])
    assertEquals("bill-horizontal-kotlin", newAliasPayload["skill_name"])
  }

  @Test
  fun `create-and-fill dry run preserves scaffold payload contract`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-fill")
    val context =
      CliRuntimeContext(
        stdinText =
        """
        {
          "scaffold_payload_version": "1.0",
          "kind": "code-review-area",
          "name": "bill-kotlin-code-review-ui",
          "platform": "kotlin",
          "area": "ui"
        }
        """.trimIndent(),
        userHome = tempDir,
      )

    val payload =
      scaffoldPayload(
        listOf("create-and-fill", "--payload", "-", "--dry-run", "--format", "json"),
        context,
      )

    assertEquals("code-review-area", payload["kind"])
    assertEquals("bill-kotlin-code-review-ui", payload["skill_name"])
  }

  @Test
  fun `new addon dry run preserves scaffold payload contract`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-addon")
    val payload =
      scaffoldPayload(
        listOf(
          "new-addon",
          "--platform",
          "kmp",
          "--name",
          "android-new-addon",
          "--body",
          "Pack-owned guidance.",
          "--dry-run",
          "--format",
          "json",
        ),
        CliRuntimeContext(userHome = tempDir),
      )

    assertEquals("add-on", payload["kind"])
    assertTrue(
      Path.of(payload["skill_path"] as String)
        .endsWith(Path.of("platform-packs", "kmp", "addons")),
    )
  }

  @Test
  fun `install commands expose agent path lookup and link-skill`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-install")
    val context = CliRuntimeContext(userHome = tempDir)

    val agentPathResult = CliRuntime.run(listOf("install", "agent-path", "codex"), context)
    val detectAgentsResult = CliRuntime.run(listOf("install", "detect-agents"), context)

    val sourceSkill = tempDir.resolve("skill-source")
    Files.createDirectories(sourceSkill)
    Files.writeString(sourceSkill.resolve("SKILL.md"), "# Skill\n")
    val targetDir = tempDir.resolve("agents")
    val linkResult =
      CliRuntime.run(
        listOf(
          "install",
          "link-skill",
          "--source",
          sourceSkill.toString(),
          "--target-dir",
          targetDir.toString(),
          "--agent",
          "codex",
        ),
        context,
      )

    assertEquals(0, agentPathResult.exitCode, agentPathResult.stdout)
    assertEquals(tempDir.resolve(".agents/skills").toString(), agentPathResult.stdout.trim())
    assertEquals(0, detectAgentsResult.exitCode, detectAgentsResult.stdout)
    assertEquals("", detectAgentsResult.stdout.trim())
    assertEquals(0, linkResult.exitCode, linkResult.stdout)
    assertTrue(Files.isSymbolicLink(targetDir.resolve("skill-source")))
    assertEquals(sourceSkill.toRealPath(), targetDir.resolve("skill-source").toRealPath())
  }

  @Test
  fun `workflow cli commands list latest resume and block continuation`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-workflow")
    val dbPath = tempDir.resolve("metrics.db")
    val opened =
      runJson(
        "--db",
        dbPath.toString(),
        "workflow",
        "open",
        "--session-id",
        "fis-20260425-000001-test",
        "--format",
        "json",
      )
    val workflowId = opened["workflow_id"] as String

    val listed = runJson("--db", dbPath.toString(), "workflow", "list", "--format", "json")
    val latest = runJson("--db", dbPath.toString(), "workflow", "latest", "--format", "json")
    val resumed = runJson("--db", dbPath.toString(), "workflow", "resume", workflowId, "--format", "json")

    assertEquals(1, listed["workflow_count"])
    assertEquals(workflowId, latest["workflow_id"])
    assertEquals("resume", resumed["resume_mode"])

    val update =
      runJson(
        "--db",
        dbPath.toString(),
        "workflow",
        "update",
        workflowId,
        "--workflow-status",
        "blocked",
        "--current-step-id",
        "implement",
        "--step-updates",
        """[{"step_id":"implement","status":"blocked","attempt_count":1}]""",
        "--artifacts-patch",
        """{"preplan_digest":{"ok":true}}""",
        "--format",
        "json",
      )
    assertEquals("blocked", update["workflow_status"])

    val continued =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "workflow", "continue", workflowId, "--format", "json"),
      )
    val continuePayload = decodeJsonObject(continued.stdout)
    assertEquals(1, continued.exitCode)
    assertEquals("blocked", continuePayload["continue_status"])
    assertEquals(listOf("plan"), continuePayload["missing_artifacts"])
  }

  @Test
  fun `verify workflow cli preserves prior step completion and continuation payloads`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-verify-workflow")
    val dbPath = tempDir.resolve("metrics.db")
    val opened =
      runJson(
        "--db",
        dbPath.toString(),
        "verify-workflow",
        "open",
        "--current-step-id",
        "code_review",
        "--format",
        "json",
      )
    val workflowId = opened["workflow_id"] as String
    val steps = opened.steps()
    assertEquals("completed", steps.single { it["step_id"] == "gather_diff" }["status"])

    runJson(
      "--db",
      dbPath.toString(),
      "verify-workflow",
      "update",
      workflowId,
      "--workflow-status",
      "running",
      "--current-step-id",
      "verdict",
      "--step-updates",
      """[{"step_id":"verdict","status":"blocked","attempt_count":1}]""",
      "--artifacts-patch",
      """{"criteria_summary":{},"diff_summary":{},"review_result":{},"completeness_audit_result":{}}""",
      "--format",
      "json",
    )

    val continued = runJson("--db", dbPath.toString(), "verify-workflow", "continue", "--latest", "--format", "json")
    assertEquals("reopened", continued["continue_status"])
    assertEquals("verdict", continued["continue_step_id"])
  }

  @Test
  fun `workflow latest no rows returns resolved db path`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-empty-workflow")
    val context = CliRuntimeContext(userHome = tempDir)

    val result = CliRuntime.run(listOf("workflow", "resume", "--latest", "--format", "json"), context)
    val payload = decodeJsonObject(result.stdout)

    assertEquals(1, result.exitCode)
    assertEquals("No feature-implement workflows found.", payload["error"])
    assertEquals(tempDir.resolve(".skill-bill/review-metrics.db").toString(), payload["db_path"])
  }

  @Test
  fun `clikt validation reports command usage errors`() {
    val missingRequiredOption = CliRuntime.run(listOf("record-feedback", "--run-id", "rvw-1"))
    assertEquals(1, missingRequiredOption.exitCode)
    assertContains(missingRequiredOption.stdout, "Error:")
    assertContains(missingRequiredOption.stdout, "--event")

    val invalidFormat = CliRuntime.run(listOf("version", "--format", "yaml"))
    assertEquals(1, invalidFormat.exitCode)
    assertContains(invalidFormat.stdout, "invalid choice")

    val unknownCommand = CliRuntime.run(listOf("unknown"))
    assertEquals(1, unknownCommand.exitCode)
    assertContains(unknownCommand.stdout, "no such subcommand")
  }
}

private fun runJson(vararg arguments: String, context: CliRuntimeContext = CliRuntimeContext()): Map<String, Any?> =
  runJson(arguments.toList(), context)

private fun Map<String, Any?>.steps(): List<Map<*, *>> = (this["steps"] as List<*>).map { step -> step as Map<*, *> }

private fun scaffoldPayloadContext(tempDir: Path): CliRuntimeContext = CliRuntimeContext(
  stdinText =
  """
        {
          "scaffold_payload_version": "1.0",
          "kind": "horizontal",
          "name": "bill-horizontal-kotlin"
        }
  """.trimIndent(),
  userHome = tempDir,
)

private fun scaffoldPayload(command: String, context: CliRuntimeContext): Map<String, Any?> =
  scaffoldPayload(listOf(command, "--payload", "-", "--dry-run", "--format", "json"), context)

private fun scaffoldPayload(arguments: List<String>, context: CliRuntimeContext): Map<String, Any?> {
  val result = CliRuntime.run(arguments, context)
  assertEquals(0, result.exitCode, result.stdout)
  return decodeJsonObject(result.stdout)
}

private fun runJson(arguments: List<String>, context: CliRuntimeContext = CliRuntimeContext()): Map<String, Any?> {
  val result = CliRuntime.run(arguments, context)
  assertEquals(0, result.exitCode, result.stdout)
  return decodeJsonObject(result.stdout)
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

private fun seedLearningScenario(dbPath: Path) {
  importSampleReview(dbPath)
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

private fun importSampleReview(dbPath: Path) {
  val result =
    CliRuntime.run(
      listOf("--db", dbPath.toString(), "import-review", "-", "--format", "json"),
      CliRuntimeContext(stdinText = SAMPLE_REVIEW.trimIndent()),
    )
  assertEquals(0, result.exitCode, result.stdout)
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
        HttpResponse(
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
        HttpResponse(
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
