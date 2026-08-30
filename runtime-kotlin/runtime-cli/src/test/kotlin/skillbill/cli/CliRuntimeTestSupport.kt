package skillbill.cli

import skillbill.SAMPLE_REVIEW
import skillbill.SkillBillVersion
import skillbill.cli.core.CliRuntime
import skillbill.cli.core.ExternalCommand
import skillbill.cli.core.ExternalCommandResult
import skillbill.cli.core.ExternalCommandRunner
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.db.core.DatabaseRuntime
import skillbill.db.telemetry.LifecycleTelemetryStore
import skillbill.db.telemetry.TelemetryOutboxStore
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.model.HttpResponse
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_URL_ENVIRONMENT_KEY
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

internal fun runJson(vararg arguments: String, context: CliRuntimeContext = CliRuntimeContext()): Map<String, Any?> =
  runJson(arguments.toList(), context)

internal fun Map<String, Any?>.steps(): List<Map<*, *>> = (this["steps"] as List<*>).map { step -> step as Map<*, *> }

internal fun runJson(arguments: List<String>, context: CliRuntimeContext = CliRuntimeContext()): Map<String, Any?> {
  val result = CliRuntime.run(arguments, context)
  assertEquals(0, result.exitCode, result.stdout)
  return decodeJsonObject(result.stdout)
}

internal fun decodeJsonObject(rawJson: String): Map<String, Any?> {
  val parsed = JsonSupport.parseObjectOrNull(rawJson)
  require(parsed != null) { "Expected JSON object but got: $rawJson" }
  val decoded = JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
  require(decoded != null) { "Expected decoded JSON object but got: $rawJson" }
  return decoded
}

internal fun snapshotTree(root: Path): List<String> = Files.walk(root).use { stream ->
  stream
    .filter { path -> path != root }
    .map { path -> root.relativize(path).toString() }
    .sorted()
    .toList()
}

internal fun goldenJson(fileName: String, vararg replacements: Pair<String, String>): String {
  var expected = Files.readString(Path.of("src/test/resources/golden").resolve(fileName))
    .replace("\r\n", "\n")
    .trim()
  replacements.forEach { (placeholder, value) ->
    expected = expected.replace(placeholder, value)
  }
  return expected
}

internal fun seedLearningScenario(dbPath: Path) {
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

internal fun importSampleReview(dbPath: Path, context: CliRuntimeContext = CliRuntimeContext()) {
  val result =
    CliRuntime.run(
      listOf("--db", dbPath.toString(), "import-review", "-", "--format", "json"),
      context.copy(stdinText = SAMPLE_REVIEW.trimIndent()),
    )
  assertEquals(0, result.exitCode, result.stdout)
}

internal fun assertReviewStatsPayload(dbPath: Path, context: CliRuntimeContext) {
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

internal fun assertFeatureStatsAliases(dbPath: Path, context: CliRuntimeContext) {
  val implementStats = CliRuntime.run(
    listOf("--db", dbPath.toString(), "implement-stats", "--format", "json"),
    context,
  )
  assertEquals(1, implementStats.exitCode)
  assertContains(implementStats.stdout, "no such")

  val verifyAliasPayload =
    runJson("--db", dbPath.toString(), "feature-verify-stats", "--format", "json", context = context)
  assertEquals("bill-feature-verify", verifyAliasPayload["workflow"])
}

internal fun seedGoalStatsDb(dbPath: Path) {
  DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
    val store = LifecycleTelemetryStore(connection)
    store.goalStarted(
      GoalStartedRecord(
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
      GoalSubtaskFinishedRecord(
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
      GoalFinishedRecord(
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

internal fun telemetryStatusContext(userHome: Path): CliRuntimeContext = CliRuntimeContext(
  environment = emptyMap(),
  userHome = userHome,
  requester = HttpRequester { _, _, _, _ -> fail("telemetry status must perform no network call") },
)

internal fun telemetryStatusState(level: String, pendingEvents: Int, priorSync: Boolean): Map<String, Any?> =
  decodeJsonObject(telemetryStatusStdout(level, pendingEvents, priorSync, json = true))

internal fun telemetryStatusText(level: String, pendingEvents: Int, priorSync: Boolean): String =
  telemetryStatusStdout(level, pendingEvents, priorSync, json = false)

internal fun telemetryStatusStdout(level: String, pendingEvents: Int, priorSync: Boolean, json: Boolean): String {
  val tempDir = Files.createTempDirectory("skillbill-cli-telemetry-status")
  val dbPath = tempDir.resolve("metrics.db")
  writeTelemetryConfig(tempDir, level = level, proxyUrl = TELEMETRY_FIXTURE_PROXY_URL)
  DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
    val store = TelemetryOutboxStore(connection)
    if (priorSync) {
      store.markSynced(listOf(store.enqueue("skillbill_goal_finished", """{"seed":"delivered"}""")))
    }
    repeat(pendingEvents) { index -> store.enqueue("skillbill_review_finished", """{"seed":$index}""") }
  }
  val arguments =
    listOf("--db", dbPath.toString(), "telemetry", "status") + if (json) listOf("--format", "json") else emptyList()
  val result = CliRuntime.run(arguments, telemetryStatusContext(tempDir))
  assertEquals(0, result.exitCode, result.stdout)
  return result.stdout
}

internal fun writeTelemetryConfig(tempDir: Path, level: String): Path {
  val configPath = tempDir.resolve(".config").resolve("skill-bill").resolve("config.json")
  Files.createDirectories(configPath.parent)
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

internal fun statsRequester(capturedRequests: MutableList<Map<String, Any?>>): HttpRequester =
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
            "supported_workflows": ["bill-feature-verify", "feature-task-runtime"],
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

internal val INSTALLED_VERSION = SkillBillVersion.VALUE
internal val NEWER_MAJOR = INSTALLED_VERSION.substringBefore('.').toInt() + 1
internal val NEWER_RELEASE_TAG = "v$NEWER_MAJOR.0.0"
internal val NEWER_PRERELEASE_TAG = "v$NEWER_MAJOR.0.0-rc.1"

internal val INSTALLED_BASE_TAG = "v${INSTALLED_VERSION.substringBefore('-')}"

internal fun updateCheckRequester(
  capturedRequests: MutableList<Map<String, Any?>>,
  latest: String = NEWER_RELEASE_TAG,
): HttpRequester = HttpRequester { method, url, _, headers ->
  capturedRequests += mapOf("method" to method, "url" to url, "headers" to headers)
  HttpResponse(
    statusCode = 200,
    body = """
        [{
          "tag_name":"$latest",
          "prerelease":${latest.contains("-")},
          "draft":false,
          "html_url":"https://github.com/oila-gmbh/skill-bill/releases/tag/$latest"
        }]
    """.trimIndent(),
  )
}

internal const val EXPECTED_INSTALL_COMMAND =
  "skill-bill update"

internal const val EXPECTED_UPDATE_COMMAND =
  "curl -fsSL https://raw.githubusercontent.com/oila-gmbh/skill-bill/main/install.sh | " +
    "bash -s -- --reuse-last-selection"

internal class CapturingExternalCommandRunner(
  internal val result: ExternalCommandResult,
) : ExternalCommandRunner {
  val commands: MutableList<ExternalCommand> = mutableListOf()

  override fun run(command: ExternalCommand): ExternalCommandResult {
    commands += command
    return result
  }
}

internal fun telemetryStatusPayload(dbPath: Path, configPath: Path): Map<String, Any?> {
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

internal fun remoteStatsScenario(configPath: Path): Pair<Map<String, Any?>, List<Map<String, Any?>>> {
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

internal fun expectedRemoteStatsPayload(): Map<String, Any?> = linkedMapOf(
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

internal fun expectedCapabilitiesPayload(): Map<String, Any?> = linkedMapOf(
  "contract_version" to "1",
  "source" to "custom_capabilities",
  "proxy_url" to "https://telemetry.example.dev/ingest",
  "capabilities_url" to "https://telemetry.example.dev/ingest/capabilities",
  "supports_ingest" to true,
  "supports_stats" to true,
  "supported_workflows" to listOf("bill-feature-verify", "feature-task-runtime"),
  "region" to "eu",
)

internal fun expectedCliRemoteStatsRequests(): List<Map<String, Any?>> = listOf(
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

internal fun assertNativeReviewGolden(dbPath: Path, context: CliRuntimeContext) {
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

internal fun assertNativeLearningGolden(tempDir: Path, context: CliRuntimeContext) {
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

internal fun assertNativeVerifyWorkflowGolden(dbPath: Path, context: CliRuntimeContext) {
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

internal fun assertNewWorkflowTimestamps(opened: Map<String, *>, shown: Map<String, *>, workflowLabel: String) {
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

internal fun assertWorkflowIdShape(workflowId: String, prefix: String) {
  assertMatchesPattern(Regex("""^$prefix-\d{8}-\d{6}-[a-z0-9]{4}$"""), workflowId, "workflow_id")
}

internal fun assertSqliteTimestampShape(timestamp: String, label: String) {
  assertMatchesPattern(Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$"""), timestamp, label)
}

internal fun assertMatchesPattern(pattern: Regex, value: String, label: String) {
  assertTrue(pattern.matches(value), "Expected $label to match ${pattern.pattern} but got $value")
}
