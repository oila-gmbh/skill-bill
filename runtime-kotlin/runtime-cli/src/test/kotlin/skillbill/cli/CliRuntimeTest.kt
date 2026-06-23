package skillbill.cli

import skillbill.SAMPLE_REVIEW
import skillbill.cli.core.CliRuntime
import skillbill.cli.core.ExternalCommand
import skillbill.cli.core.ExternalCommandResult
import skillbill.cli.core.ExternalCommandRunner
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.db.core.DatabaseRuntime
import skillbill.db.telemetry.LifecycleTelemetryStore
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

@Suppress("LargeClass")
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
  fun `verified native json commands match golden fixtures`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-native-golden")
    val dbPath = tempDir.resolve("metrics.db")
    val configPath = writeTelemetryConfig(tempDir, "off")
    val context =
      CliRuntimeContext(
        environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath.toString()),
        userHome = tempDir,
      )

    val version = CliRuntime.run(listOf("version", "--format", "json"), context)
    assertEquals(0, version.exitCode, version.stdout)
    assertEquals(goldenJson("cli-version.json"), version.stdout)

    val doctor = CliRuntime.run(listOf("--db", dbPath.toString(), "doctor", "--format", "json"), context)
    assertEquals(0, doctor.exitCode, doctor.stdout)
    assertEquals(
      goldenJson("cli-doctor.json", "<DB_PATH>" to dbPath.toAbsolutePath().normalize().toString()),
      doctor.stdout,
    )

    assertNativeReviewGolden(dbPath, context)
    assertNativeLearningGolden(tempDir, context)
    assertNativeWorkflowGolden(dbPath, context)
    assertNativeVerifyWorkflowGolden(dbPath, context)
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

    val jsonPayload =
      runJson(
        "--db",
        dbPath.toString(),
        "triage",
        "--run-id",
        "rvw-20260402-001",
        "--decision",
        "1 accept - json parity",
        "--format",
        "json",
      )
    val recorded = jsonPayload["recorded"] as List<*>
    assertEquals("F-001", (recorded.first() as Map<*, *>)["finding_id"])
    assertEquals("finding_accepted", (recorded.first() as Map<*, *>)["outcome_type"])
  }

  @Test
  fun `review commands cover list stats feedback and aliases`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-review")
    val dbPath = tempDir.resolve("metrics.db")
    val configPath = writeTelemetryConfig(tempDir, "anonymous")
    val context =
      CliRuntimeContext(
        environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath.toString()),
        userHome = tempDir,
      )

    importSampleReview(dbPath, context)

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
        context = context,
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
        context = context,
      )
    assertEquals("fix_applied", feedbackPayload["outcome_type"])
    assertEquals(1, feedbackPayload["recorded_findings"])

    assertReviewStatsPayload(dbPath, context)
    assertFeatureStatsAliases(dbPath, context)
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

    val jsonPayload =
      runJson(
        "--db",
        dbPath.toString(),
        "learnings",
        "resolve",
        "--skill",
        "bill-kotlin-code-review",
        "--format",
        "json",
      )
    assertEquals("bill-kotlin-code-review", jsonPayload["skill_name"])
    assertEquals("L-001", jsonPayload["applied_learnings"])
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
    assertEquals(expectedRemoteStatsPayload(), statsPayload)
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

    assertEquals(expectedCapabilitiesPayload(), payload)
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
    assertEquals("0.3.0-SNAPSHOT", decodeJsonObject(versionResult.stdout)["version"])

    val doctorResult =
      CliRuntime.run(listOf("--db", dbPath.toString(), "doctor", "--format", "json"), context)
    val doctorPayload = decodeJsonObject(doctorResult.stdout)
    assertEquals("0.3.0-SNAPSHOT", doctorPayload["version"])
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
    assertContains(rootHelp.stdout, "list")
    assertContains(rootHelp.stdout, "show")
    assertContains(rootHelp.stdout, "explain")
    assertContains(rootHelp.stdout, "validate")
    assertContains(rootHelp.stdout, "upgrade")
    assertContains(rootHelp.stdout, "render")
    assertContains(rootHelp.stdout, "edit")
    assertContains(rootHelp.stdout, "fill")
    assertContains(rootHelp.stdout, "learnings")
    assertContains(rootHelp.stdout, "telemetry")
    assertContains(rootHelp.stdout, "new-skill")
    assertContains(rootHelp.stdout, "create-and-fill")
    assertContains(rootHelp.stdout, "new-addon")
    assertContains(rootHelp.stdout, "install")
    val workflowHelp = CliRuntime.run(listOf("workflow", "--help"))
    val verifyWorkflowHelp = CliRuntime.run(listOf("verify-workflow", "--help"))
    assertContains(workflowHelp.stdout, "show")
    assertContains(verifyWorkflowHelp.stdout, "show")
    assertEquals(0, telemetryHelp.exitCode)
    assertContains(telemetryHelp.stdout, "capabilities")
    assertContains(telemetryHelp.stdout, "set-level")
  }

  @Test
  fun `install commands expose agent path lookup and link-skill`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-install")
    Files.createDirectories(tempDir.resolve(".claude"))
    Files.createDirectories(tempDir.resolve(".junie"))
    val context = CliRuntimeContext(userHome = tempDir, environment = emptyMap())

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
    assertEquals(
      """
      claude	${tempDir.resolve(".claude/skills")}
      junie	${tempDir.resolve(".junie/skills")}
      """.trimIndent(),
      detectAgentsResult.stdout.trim(),
    )
    assertEquals(0, linkResult.exitCode, linkResult.stdout)
    assertTrue(Files.isSymbolicLink(targetDir.resolve("skill-source")))
    assertEquals(sourceSkill.toRealPath(), targetDir.resolve("skill-source").toRealPath())
  }

  @Test
  fun `detect-agents CLI reports all supported detected agent flows`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-install-detect-all")
    Files.createDirectories(tempDir.resolve(".copilot"))
    Files.createDirectories(tempDir.resolve(".claude"))
    Files.createDirectories(tempDir.resolve(".codex"))
    Files.createDirectories(tempDir.resolve(".config/opencode"))
    Files.createDirectories(tempDir.resolve(".junie"))

    val result = CliRuntime.run(
      listOf("install", "detect-agents"),
      CliRuntimeContext(userHome = tempDir, environment = emptyMap()),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(
      """
      copilot	${tempDir.resolve(".copilot/skills")}
      claude	${tempDir.resolve(".claude/skills")}
      codex	${tempDir.resolve(".codex/skills")}
      opencode	${tempDir.resolve(".config/opencode/skills")}
      junie	${tempDir.resolve(".junie/skills")}
      """.trimIndent(),
      result.stdout.trim(),
    )
  }

  @Test
  fun `link-skill stages content managed skills when repo root is supplied`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-install-staged")
    val context = CliRuntimeContext(userHome = tempDir.resolve("home"), environment = emptyMap())
    val repoRoot = tempDir.resolve("repo")
    val skillName = "bill-cli-staged"
    val sourceSkill = repoRoot.resolve("skills").resolve(skillName)
    Files.createDirectories(sourceSkill)
    Files.writeString(
      sourceSkill.resolve("content.md"),
      """
      ---
      name: $skillName
      description: CLI staging fixture.
      ---

      ## Purpose

      Verify installer staging.
      """.trimIndent() + "\n",
    )

    val targetDir = tempDir.resolve("agents")
    val result =
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
          "--repo-root",
          repoRoot.toString(),
        ),
        context,
      )

    val link = targetDir.resolve(skillName)
    val linkedTarget = Files.readSymbolicLink(link).toAbsolutePath().normalize()
    assertEquals(0, result.exitCode, result.stdout)
    assertTrue(Files.isSymbolicLink(link))
    assertTrue(linkedTarget.startsWith(context.userHome.resolve(".skill-bill/installed-skills").toAbsolutePath()))
    assertTrue(Files.isRegularFile(link.resolve("SKILL.md")))
    assertFalse(Files.exists(sourceSkill.resolve("SKILL.md")))
  }

  @Test
  fun `doctor skill subject is retired with stable replacement`() {
    val result =
      CliRuntime.run(
        listOf(
          "doctor",
          "skill",
          "bill-feature-task",
          "--repo-root",
          ".",
          "--content",
          "none",
          "--format",
          "json",
        ),
      )

    assertEquals(1, result.exitCode)
    assertEquals(
      "doctor skill was retired in SKILL-32; use " +
        "`skill-bill show bill-feature-task --repo-root . --content none` instead.",
      result.stdout,
    )
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
    val shown = runJson("--db", dbPath.toString(), "workflow", "show", workflowId, "--format", "json")
    val resumed = runJson("--db", dbPath.toString(), "workflow", "resume", workflowId, "--format", "json")

    assertEquals(1, listed["workflow_count"])
    assertEquals(workflowId, latest["workflow_id"])
    assertEquals(workflowId, shown["workflow_id"])
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
    assertEquals(workflowId, continuePayload["workflow_id"])
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
      "{" +
        "\"criteria_summary\":{}," +
        "\"diff_summary\":{}," +
        "\"review_result\":{}," +
        "\"unit_test_value_result\":{}," +
        "\"completeness_audit_result\":{}" +
        "}",
      "--format",
      "json",
    )

    val continued = runJson("--db", dbPath.toString(), "verify-workflow", "continue", "--latest", "--format", "json")
    val shown = runJson("--db", dbPath.toString(), "verify-workflow", "show", workflowId, "--format", "json")
    assertEquals("reopened", continued["continue_status"])
    assertEquals("running", continued["workflow_status_before_continue"])
    assertEquals("verdict", continued["continue_step_id"])
    assertEquals("verdict", continued["resume_step_id"])
    assertEquals(
      "skill-bill --db '$dbPath' verify-workflow show '$workflowId' --format json",
      continued["read_only_full_state_command"],
    )
    assertFalse(continued.containsKey("artifacts"))
    assertFalse(continued.containsKey("steps"))
    assertEquals("verdict", shown["current_step_id"])
  }

  @Test
  fun `workflow latest no rows returns resolved db path`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-empty-workflow")
    val context = CliRuntimeContext(userHome = tempDir)

    val result = CliRuntime.run(listOf("workflow", "resume", "--latest", "--format", "json"), context)
    val payload = decodeJsonObject(result.stdout)

    assertEquals(1, result.exitCode)
    assertEquals("No feature-task-prose workflows found.", payload["error"])
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

  @Test
  fun `update-check emits text and json update results through configured requester`() {
    val capturedRequests = mutableListOf<Map<String, Any?>>()
    val context = CliRuntimeContext(
      requester = updateCheckRequester(capturedRequests),
      userHome = Files.createTempDirectory("skillbill-update-check-home"),
    )

    val text = CliRuntime.run(listOf("update-check"), context)

    assertEquals(0, text.exitCode, text.stdout)
    assertContains(text.stdout, "status: update_available")
    assertContains(text.stdout, "installed_version: 0.3.0-SNAPSHOT")
    assertContains(text.stdout, "latest_version: v0.4.0")
    assertContains(text.stdout, "recommended_install_command: $EXPECTED_INSTALL_COMMAND")

    val json = CliRuntime.run(listOf("update-check", "--format", "json"), context)
    val payload = decodeJsonObject(json.stdout)

    assertEquals(0, json.exitCode, json.stdout)
    assertEquals("update_available", payload["status"])
    assertEquals("0.3.0-SNAPSHOT", payload["installed_version"])
    assertEquals("v0.4.0", payload["latest_version"])
    assertEquals("https://github.com/Sermilion/skill-bill/releases/tag/v0.4.0", payload["release_url"])
    assertEquals(2, capturedRequests.size)
    assertEquals("GET", capturedRequests.first()["method"])
    assertEquals("skill-bill-update-check", (capturedRequests.first()["headers"] as Map<*, *>)["User-Agent"])
  }

  @Test
  fun `update dry-run prints installer command with reuse last selection by default`() {
    val text = CliRuntime.run(listOf("update", "--dry-run"))

    assertEquals(0, text.exitCode, text.stdout)
    assertContains(text.stdout, "status: dry_run")
    assertContains(text.stdout, "command: $EXPECTED_UPDATE_COMMAND")
  }

  @Test
  fun `update dry-run json includes composed installer args`() {
    val json = CliRuntime.run(
      listOf(
        "update",
        "--dry-run",
        "--format",
        "json",
        "--release",
        "v0.2.0",
        "--prefer-upstream",
        "--clean",
        "--no-desktop-app",
      ),
    )
    val payload = decodeJsonObject(json.stdout)

    assertEquals(0, json.exitCode, json.stdout)
    assertEquals("dry_run", payload["status"])
    assertEquals(
      "$EXPECTED_UPDATE_COMMAND --release v0.2.0 --no-desktop-app --prefer-upstream --clean",
      payload["command"],
    )
    assertEquals(
      listOf("--reuse-last-selection", "--release", "v0.2.0", "--no-desktop-app", "--prefer-upstream", "--clean"),
      payload["installer_args"],
    )
  }

  @Test
  fun `update dry-run json escapes shell-quoted command arguments`() {
    val desktopAppDir = "dir\"\\with quote"
    val json = CliRuntime.run(
      listOf(
        "update",
        "--dry-run",
        "--format",
        "json",
        "--desktop-app-dir",
        desktopAppDir,
      ),
    )
    val payload = decodeJsonObject(json.stdout)

    assertEquals(0, json.exitCode, json.stdout)
    assertEquals("dry_run", payload["status"])
    assertEquals("$EXPECTED_UPDATE_COMMAND --desktop-app-dir 'dir\"\\with quote'", payload["command"])
    assertEquals(listOf("--reuse-last-selection", "--desktop-app-dir", desktopAppDir), payload["installer_args"])
  }

  @Test
  fun `update runs installer with selected home and configured environment`() {
    val home = Files.createTempDirectory("skillbill-update-home")
    val runner = CapturingExternalCommandRunner(ExternalCommandResult(exitCode = 0, output = "installer ok\n"))
    val result = CliRuntime.run(
      listOf(
        "--home",
        home.toString(),
        "update",
        "--release",
        "v0.4.0",
        "--no-desktop-app",
        "--format",
        "json",
      ),
      CliRuntimeContext(
        environment = mapOf("HOME" to "/wrong-home", "PATH" to "/test/bin"),
        externalCommandRunner = runner,
      ),
    )
    val payload = decodeJsonObject(result.stdout)
    val command = runner.commands.single()

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("completed", payload["status"])
    assertEquals("installer ok\n", payload["installer_output"])
    assertEquals("bash", command.executable)
    assertEquals(
      listOf("-c", "$EXPECTED_UPDATE_COMMAND --release v0.4.0 --no-desktop-app"),
      command.arguments,
    )
    assertEquals(home.toString(), command.environment["HOME"])
    assertEquals("/test/bin", command.environment["PATH"])
  }

  @Test
  fun `update propagates installer failure exit code and output`() {
    val capturedRequests = mutableListOf<Map<String, Any?>>()
    val runner = CapturingExternalCommandRunner(ExternalCommandResult(exitCode = 7, output = "installer failed\n"))
    val result = CliRuntime.run(
      listOf("update", "--format", "json"),
      CliRuntimeContext(
        requester = updateCheckRequester(capturedRequests),
        externalCommandRunner = runner,
      ),
    )
    val payload = decodeJsonObject(result.stdout)

    assertEquals(7, result.exitCode, result.stdout)
    assertEquals("failed", payload["status"])
    assertEquals(7, payload["exit_code"])
    assertEquals("installer failed\n", payload["installer_output"])
  }

  @Test
  fun `update skips installer when installed version is ahead of latest release`() {
    val capturedRequests = mutableListOf<Map<String, Any?>>()
    val runner = CapturingExternalCommandRunner(ExternalCommandResult(exitCode = 0, output = "should not run\n"))
    val result = CliRuntime.run(
      listOf("update", "--format", "json"),
      CliRuntimeContext(
        requester = updateCheckRequester(capturedRequests, latest = "v0.2.0"),
        externalCommandRunner = runner,
      ),
    )
    val payload = decodeJsonObject(result.stdout)
    val updateCheck = payload["update_check"] as Map<*, *>

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("skipped", payload["status"])
    assertEquals("ahead_of_release", updateCheck["status"])
    assertEquals("installed version is newer than the latest release", payload["reason"])
    assertTrue(runner.commands.isEmpty(), "installer must not run when local version is ahead")
    assertEquals(1, capturedRequests.size)
  }

  @Test
  fun `update rejects conflicting desktop app options`() {
    val result = CliRuntime.run(listOf("update", "--dry-run", "--with-desktop-app", "--no-desktop-app"))

    assertEquals(1, result.exitCode)
    assertContains(result.stdout, "--with-desktop-app and --no-desktop-app cannot be used together")
  }

  @Test
  fun `update-check includes prereleases and returns unknown with exit zero`() {
    val prerelease = CliRuntime.run(
      listOf("update-check", "--include-prereleases", "--format", "json"),
      CliRuntimeContext(requester = updateCheckRequester(mutableListOf(), latest = "v0.4.0-rc.1")),
    )
    val prereleasePayload = decodeJsonObject(prerelease.stdout)

    assertEquals(0, prerelease.exitCode, prerelease.stdout)
    assertEquals("update_available", prereleasePayload["status"])
    assertEquals("v0.4.0-rc.1", prereleasePayload["latest_version"])

    val unknown = CliRuntime.run(
      listOf("update-check"),
      CliRuntimeContext(requester = HttpRequester { _, _, _, _ -> HttpResponse(429, "") }),
    )

    assertEquals(0, unknown.exitCode, unknown.stdout)
    assertContains(unknown.stdout, "status: unknown")
    assertContains(unknown.stdout, "reason:")
  }

  @Test
  fun `update-check is read only for local home and repo paths`() {
    val tempDir = Files.createTempDirectory("skillbill-update-check-read-only")
    val home = tempDir.resolve("home")
    val repo = tempDir.resolve("repo")
    Files.createDirectories(home)
    Files.createDirectories(repo)
    val before = snapshotTree(tempDir)

    val result = CliRuntime.run(
      listOf("update-check"),
      CliRuntimeContext(
        userHome = home,
        requester = updateCheckRequester(mutableListOf(), latest = "v0.3.0-SNAPSHOT"),
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(before, snapshotTree(tempDir))
  }

  @Test
  fun `goal-stats --format json emits stable payload`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-goal-stats-json")
    val dbPath = tempDir.resolve("metrics.db")
    seedGoalStatsDb(dbPath)
    val result = CliRuntime.run(
      listOf("--db", dbPath.toString(), "goal-stats", "--format", "json"),
      CliRuntimeContext(),
    )
    val payload = decodeJsonObject(result.stdout)
    assertEquals(0, result.exitCode)
    assertEquals("bill-goal-run", payload["workflow"])
    assertEquals(1, payload["total_runs"])
    assertEquals(1.0, payload["blocked_rate"])
    assertEquals(dbPath.toAbsolutePath().normalize().toString(), payload["db_path"])
    assertEquals(1, (payload["top_blocked_subtasks"] as List<*>).size)
  }

  @Test
  fun `goal-stats human-readable output includes key fields`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-goal-stats-human")
    val dbPath = tempDir.resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = LifecycleTelemetryStore(connection)
      store.goalStarted(
        skillbill.telemetry.model.GoalStartedRecord(
          issueKey = "SKILL-66",
          featureName = "goal telemetry",
          workflowId = "wf-cli-human",
          subtaskTotal = 1,
          resumed = false,
          startedAt = "2026-06-05T10:00:00Z",
          mode = "runtime",
        ),
        level = "full",
      )
      store.goalFinished(
        skillbill.telemetry.model.GoalFinishedRecord(
          issueKey = "SKILL-66",
          workflowId = "wf-cli-human",
          status = "completed",
          startedAt = "2026-06-05T10:00:00Z",
          finishedAt = "2026-06-05T10:30:00Z",
          durationMs = 1_800_000,
          subtasksComplete = 1,
          subtasksBlocked = 0,
          subtasksSkipped = 0,
          mode = "runtime",
        ),
        level = "full",
      )
    }

    val result = CliRuntime.run(
      listOf("--db", dbPath.toString(), "goal-stats"),
      CliRuntimeContext(),
    )

    assertEquals(0, result.exitCode)
    assertContains(result.stdout, "total_runs")
    assertContains(result.stdout, "blocked_rate")
  }

  @Test
  fun `goal-stats empty store exits 0 with zero counts`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-goal-stats-empty")
    val dbPath = tempDir.resolve("metrics.db")

    val result = CliRuntime.run(
      listOf("--db", dbPath.toString(), "goal-stats", "--format", "json"),
      CliRuntimeContext(),
    )
    val payload = decodeJsonObject(result.stdout)

    assertEquals(0, result.exitCode)
    assertEquals("bill-goal-run", payload["workflow"])
    assertEquals(0, payload["total_runs"])
  }
}

private fun runJson(vararg arguments: String, context: CliRuntimeContext = CliRuntimeContext()): Map<String, Any?> =
  runJson(arguments.toList(), context)

private fun Map<String, Any?>.steps(): List<Map<*, *>> = (this["steps"] as List<*>).map { step -> step as Map<*, *> }

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

private fun snapshotTree(root: Path): List<String> = Files.walk(root).use { stream ->
  stream
    .filter { path -> path != root }
    .map { path -> root.relativize(path).toString() }
    .sorted()
    .toList()
}

private fun goldenJson(fileName: String, vararg replacements: Pair<String, String>): String {
  var expected = Files.readString(Path.of("src/test/resources/golden").resolve(fileName))
    .replace("\r\n", "\n")
    .trim()
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

private fun importSampleReview(dbPath: Path, context: CliRuntimeContext = CliRuntimeContext()) {
  val result =
    CliRuntime.run(
      listOf("--db", dbPath.toString(), "import-review", "-", "--format", "json"),
      context.copy(stdinText = SAMPLE_REVIEW.trimIndent()),
    )
  assertEquals(0, result.exitCode, result.stdout)
}

private fun assertReviewStatsPayload(dbPath: Path, context: CliRuntimeContext) {
  val statsPayload =
    runJson(
      "--db",
      dbPath.toString(),
      "stats",
      "--run-id",
      "rvw-20260402-001",
      "--format",
      "json",
      context = context,
    )
  assertEquals(2, statsPayload["total_findings"])
  assertEquals(1, statsPayload["accepted_findings"])
  assertEquals(1, statsPayload["unresolved_findings"])
  val reviewHealth = statsPayload["health"] as Map<*, *>
  assertEquals(1, reviewHealth["total_review_payload_records"])
  assertEquals(mapOf("standalone" to 1, "embedded" to 0, "malformed" to 0), reviewHealth["source_counts"])
}

private fun assertFeatureStatsAliases(dbPath: Path, context: CliRuntimeContext) {
  val implementAliasPayload =
    runJson("--db", dbPath.toString(), "feature-implement-stats", "--format", "json", context = context)
  val verifyAliasPayload =
    runJson("--db", dbPath.toString(), "feature-verify-stats", "--format", "json", context = context)

  assertEquals("bill-feature-task", implementAliasPayload["workflow"])
  assertEquals(0.0, implementAliasPayload["median_duration_seconds"])
  assertTrue("child_step_coverage" in implementAliasPayload)
  assertTrue("feature_size_outcome_stats" in implementAliasPayload)
  assertTrue("large_feature_health" in implementAliasPayload)
  assertEquals("bill-feature-verify", verifyAliasPayload["workflow"])
}

private fun seedGoalStatsDb(dbPath: Path) {
  DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
    val store = LifecycleTelemetryStore(connection)
    store.goalStarted(
      skillbill.telemetry.model.GoalStartedRecord(
        issueKey = "SKILL-66",
        featureName = "goal telemetry",
        workflowId = "wf-cli-1",
        subtaskTotal = 1,
        resumed = false,
        startedAt = "2026-06-05T10:00:00Z",
        mode = "runtime",
      ),
      level = "full",
    )
    store.goalSubtaskFinished(
      skillbill.telemetry.model.GoalSubtaskFinishedRecord(
        issueKey = "SKILL-66",
        workflowId = "wf-cli-1",
        subtaskId = 1,
        subtaskName = "implement",
        status = "blocked",
        startedAt = "2026-06-05T10:00:00Z",
        finishedAt = "2026-06-05T10:05:00Z",
        durationMs = 300_000,
        attemptCount = 2,
        blockedReason = "compile error",
      ),
      "full",
    )
    store.goalFinished(
      skillbill.telemetry.model.GoalFinishedRecord(
        issueKey = "SKILL-66",
        workflowId = "wf-cli-1",
        status = "blocked",
        startedAt = "2026-06-05T10:00:00Z",
        finishedAt = "2026-06-05T10:10:00Z",
        durationMs = 600_000,
        subtasksComplete = 0,
        subtasksBlocked = 1,
        subtasksSkipped = 0,
        mode = "runtime",
      ),
      level = "full",
    )
  }
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
            "source": "custom_capabilities",
            "supports_ingest": true,
            "supports_stats": true,
            "supported_workflows": ["bill-feature-verify", "bill-feature-task", "feature-task-runtime"],
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

private fun updateCheckRequester(
  capturedRequests: MutableList<Map<String, Any?>>,
  latest: String = "v0.4.0",
): HttpRequester = HttpRequester { method, url, _, headers ->
  capturedRequests += mapOf("method" to method, "url" to url, "headers" to headers)
  HttpResponse(
    statusCode = 200,
    body = """
        [{
          "tag_name":"$latest",
          "prerelease":${latest.contains("-")},
          "draft":false,
          "html_url":"https://github.com/Sermilion/skill-bill/releases/tag/$latest"
        }]
    """.trimIndent(),
  )
}

private const val EXPECTED_INSTALL_COMMAND =
  "skill-bill update"

private const val EXPECTED_UPDATE_COMMAND =
  "curl -fsSL https://raw.githubusercontent.com/Sermilion/skill-bill/main/install.sh | " +
    "bash -s -- --reuse-last-selection"

private class CapturingExternalCommandRunner(
  private val result: ExternalCommandResult,
) : ExternalCommandRunner {
  val commands: MutableList<ExternalCommand> = mutableListOf()

  override fun run(command: ExternalCommand): ExternalCommandResult {
    commands += command
    return result
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

private fun expectedRemoteStatsPayload(): Map<String, Any?> = linkedMapOf(
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

private fun expectedCapabilitiesPayload(): Map<String, Any?> = linkedMapOf(
  "contract_version" to "1",
  "source" to "custom_capabilities",
  "proxy_url" to "https://telemetry.example.dev/ingest",
  "capabilities_url" to "https://telemetry.example.dev/ingest/capabilities",
  "supports_ingest" to true,
  "supports_stats" to true,
  "supported_workflows" to listOf("bill-feature-verify", "bill-feature-task", "feature-task-runtime"),
  "region" to "eu",
)

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

private fun assertNativeReviewGolden(dbPath: Path, context: CliRuntimeContext) {
  importSampleReview(dbPath)
  val triage =
    CliRuntime.run(
      listOf(
        "--db",
        dbPath.toString(),
        "triage",
        "--run-id",
        "rvw-20260402-001",
        "--decision",
        "1 fix - patched",
        "--format",
        "json",
      ),
      context,
    )

  assertEquals(0, triage.exitCode, triage.stdout)
  assertEquals(
    goldenJson("cli-triage.json", "<DB_PATH>" to dbPath.toAbsolutePath().normalize().toString()),
    triage.stdout,
  )
}

private fun assertNativeLearningGolden(tempDir: Path, context: CliRuntimeContext) {
  val dbPath = tempDir.resolve("learnings.db")
  seedLearningScenario(dbPath)
  val learnings =
    CliRuntime.run(
      listOf(
        "--db",
        dbPath.toString(),
        "learnings",
        "resolve",
        "--skill",
        "bill-kotlin-code-review",
        "--format",
        "json",
      ),
      context,
    )

  assertEquals(0, learnings.exitCode, learnings.stdout)
  assertEquals(
    goldenJson("cli-learnings-resolve.json", "<DB_PATH>" to dbPath.toAbsolutePath().normalize().toString()),
    learnings.stdout,
  )
}

private fun assertNativeWorkflowGolden(dbPath: Path, context: CliRuntimeContext) {
  val opened =
    runJson(
      listOf(
        "--db",
        dbPath.toString(),
        "workflow",
        "open",
        "--session-id",
        "fis-20260425-000001-test",
        "--format",
        "json",
      ),
      context,
    )
  val workflowId = opened["workflow_id"] as String
  val shown =
    CliRuntime.run(
      listOf("--db", dbPath.toString(), "workflow", "show", workflowId, "--format", "json"),
      context,
    )
  val shownPayload = decodeJsonObject(shown.stdout)

  assertEquals(0, shown.exitCode, shown.stdout)
  assertWorkflowIdShape(workflowId, "wfl")
  assertNewWorkflowTimestamps(opened, shownPayload, "implement")
  assertEquals(
    goldenJson(
      "cli-workflow-show.json",
      "<DB_PATH>" to dbPath.toAbsolutePath().normalize().toString(),
      "<WORKFLOW_ID>" to workflowId,
      "<STARTED_AT>" to shownPayload["started_at"].toString(),
      "<UPDATED_AT>" to shownPayload["updated_at"].toString(),
    ),
    shown.stdout,
  )
}

private fun assertNativeVerifyWorkflowGolden(dbPath: Path, context: CliRuntimeContext) {
  val opened =
    runJson(
      listOf(
        "--db",
        dbPath.toString(),
        "verify-workflow",
        "open",
        "--current-step-id",
        "code_review",
        "--format",
        "json",
      ),
      context,
    )
  val workflowId = opened["workflow_id"] as String
  val shown =
    CliRuntime.run(
      listOf("--db", dbPath.toString(), "verify-workflow", "show", workflowId, "--format", "json"),
      context,
    )
  val shownPayload = decodeJsonObject(shown.stdout)

  assertEquals(0, shown.exitCode, shown.stdout)
  assertWorkflowIdShape(workflowId, "wfv")
  assertNewWorkflowTimestamps(opened, shownPayload, "verify")
  assertEquals(
    goldenJson(
      "cli-verify-workflow-show.json",
      "<DB_PATH>" to dbPath.toAbsolutePath().normalize().toString(),
      "<WORKFLOW_ID>" to workflowId,
      "<STARTED_AT>" to shownPayload["started_at"].toString(),
      "<UPDATED_AT>" to shownPayload["updated_at"].toString(),
    ),
    shown.stdout,
  )
}

private fun assertNewWorkflowTimestamps(opened: Map<String, *>, shown: Map<String, *>, workflowLabel: String) {
  val startedAt = shown["started_at"].toString()
  val updatedAt = shown["updated_at"].toString()

  assertSqliteTimestampShape(opened["started_at"].toString(), "$workflowLabel opened started_at")
  assertSqliteTimestampShape(opened["updated_at"].toString(), "$workflowLabel opened updated_at")
  assertSqliteTimestampShape(startedAt, "$workflowLabel shown started_at")
  assertSqliteTimestampShape(updatedAt, "$workflowLabel shown updated_at")
  assertEquals(opened["started_at"], opened["updated_at"])
  assertEquals(opened["started_at"], shown["started_at"])
  assertEquals(opened["updated_at"], shown["updated_at"])
  assertEquals(shown["started_at"], shown["updated_at"])
  assertTrue(updatedAt >= startedAt)
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
