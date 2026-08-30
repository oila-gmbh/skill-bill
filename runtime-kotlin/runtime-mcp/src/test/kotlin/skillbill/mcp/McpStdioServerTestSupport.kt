package skillbill.mcp

import skillbill.contracts.JsonSupport
import skillbill.db.core.DatabaseRuntime
import skillbill.db.telemetry.LifecycleTelemetryStore
import skillbill.mcp.core.McpStdioServer
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_URL_ENVIRONMENT_KEY
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal fun assertStrictSchemaCoveragePublished(tools: List<*>) {
  assertQualityCheckSchemaCoverage(tools)
  assertFeatureVerifySchemaCoverage(tools)
  assertTelemetryRemoteStatsSchemaCoverage(tools)
  assertGoalStatsSchemaCoverage(tools)
  assertMiscToolSchemaCoverage(tools)
}

private fun assertQualityCheckSchemaCoverage(tools: List<*>) {
  tools.schemaFor("quality_check_finished").assertRequired(
    "session_id",
    "result",
    "routed_skill",
    "detected_stack",
    "fallback",
    "scope_type",
  )
  assertEquals(
    listOf("pass", "fail", "skipped", "unsupported_stack"),
    tools.schemaFor("quality_check_finished").properties().enumFor("result"),
  )
  assertEquals(
    listOf("files", "working_tree", "branch_diff", "repo"),
    tools.schemaFor("quality_check_started").properties().enumFor("scope_type"),
  )
  tools.schemaFor("quality_check_started").assertRequired("routed_skill", "detected_stack", "fallback")
}

private fun assertFeatureVerifySchemaCoverage(tools: List<*>) {
  assertEquals(
    listOf("completed", "abandoned_at_review", "abandoned_at_audit", "error"),
    tools.schemaFor("feature_verify_finished").properties().enumFor("completion_status"),
  )
  assertEquals(
    listOf("all_pass", "had_gaps", "skipped"),
    tools.schemaFor("feature_verify_finished").properties().enumFor("audit_result"),
  )
  assertEquals(
    listOf(
      "collect_inputs",
      "extract_criteria",
      "gather_diff",
      "feature_flag_audit",
      "code_review",
      "unit_test_value_check",
      "completeness_audit",
      "verdict",
      "finish",
    ),
    tools.schemaFor("feature_verify_workflow_update").properties().enumFor("current_step_id"),
  )
}

private fun assertTelemetryRemoteStatsSchemaCoverage(tools: List<*>) {
  tools.schemaFor("telemetry_remote_stats").assertRequired("workflow")
  assertEquals(
    listOf(
      "verify",
      "bill-feature-verify",
      "feature-task-runtime",
    ),
    tools.schemaFor("telemetry_remote_stats").properties().enumFor("workflow"),
  )
  assertEquals(
    listOf("", "day", "week"),
    tools.schemaFor("telemetry_remote_stats").properties().enumFor("group_by"),
  )
}

private fun assertGoalStatsSchemaCoverage(tools: List<*>) {
  assertEquals(false, tools.schemaFor("goal_stats")["additionalProperties"])
  assertEquals(emptyList<String>(), tools.schemaFor("goal_stats")["required"])
  assertEquals(
    setOf("since", "date_from", "date_to", "group_by"),
    tools.schemaFor("goal_stats").properties().keys,
  )
  assertEquals(
    listOf("", "day", "week"),
    tools.schemaFor("goal_stats").properties().enumFor("group_by"),
  )
}

private fun assertMiscToolSchemaCoverage(tools: List<*>) {
  tools.schemaFor("new_skill_scaffold").assertRequired("payload")
  tools.schemaFor("import_review").assertRequired("review_text")
  tools.schemaFor("triage_findings").assertRequired("review_run_id", "decisions")
}

internal val expectedToolInventory =
  listOf(
    "doctor",
    "feature_task_audit_settle",
    "feature_task_phase_block",
    "feature_task_phase_complete",
    "feature_verify_finished",
    "feature_verify_stats",
    "feature_verify_started",
    "feature_verify_workflow_get",
    "feature_verify_workflow_latest",
    "feature_verify_workflow_list",
    "feature_verify_workflow_continue",
    "feature_verify_workflow_open",
    "feature_verify_workflow_resume",
    "feature_verify_workflow_update",
    "goal_stats",
    "import_review",
    "new_skill_scaffold",
    "pr_description_generated",
    "quality_check_finished",
    "quality_check_started",
    "resolve_learnings",
    "review_stats",
    "telemetry_proxy_capabilities",
    "telemetry_remote_stats",
    "triage_findings",
    "update_check",
  )

internal val priorityStrictToolNames =
  listOf(
    "feature_verify_started",
    "feature_verify_finished",
    "quality_check_started",
    "quality_check_finished",
    "pr_description_generated",
    "import_review",
    "triage_findings",
    "resolve_learnings",
    "feature_verify_workflow_open",
    "feature_verify_workflow_update",
    "feature_verify_workflow_get",
    "feature_verify_workflow_list",
    "feature_verify_workflow_latest",
    "feature_verify_workflow_resume",
    "feature_verify_workflow_continue",
    "new_skill_scaffold",
  )

internal val verifyLifecycleToolNames =
  listOf(
    "feature_verify_started",
    "feature_verify_finished",
    "feature_verify_workflow_get",
    "feature_verify_workflow_latest",
    "feature_verify_workflow_list",
    "feature_verify_workflow_continue",
    "feature_verify_workflow_open",
    "feature_verify_workflow_resume",
    "feature_verify_workflow_update",
  )

internal val removedToolNames =
  listOf(
    "feature_task_continuation_lookup",
    "feature_task_runtime_started",
    "feature_task_runtime_finished",
    "feature_task_runtime_stats",
    "feature_task_runtime_workflow_get",
    "feature_task_runtime_workflow_latest",
    "feature_task_runtime_workflow_list",
    "feature_task_runtime_workflow_continue",
    "feature_task_runtime_workflow_open",
    "feature_task_runtime_workflow_resume",
    "feature_task_runtime_workflow_update",
    "readian_auth_status",
    "readian_get_article",
    "readian_get_articles_for_topic_query",
    "readian_get_spotlight",
    "readian_mark_story_status",
    "readian_save_candidate",
    "feature_task_prose_started",
    "feature_task_prose_finished",
    "feature_task_prose_stats",
    "feature_task_prose_workflow_get",
    "feature_task_prose_workflow_latest",
    "feature_task_prose_workflow_list",
    "feature_task_prose_workflow_continue",
    "feature_task_prose_workflow_open",
    "feature_task_prose_workflow_resume",
    "feature_task_prose_workflow_update",
    "feature_implement_started",
    "feature_implement_finished",
    "feature_implement_stats",
    "feature_implement_workflow_get",
    "feature_implement_workflow_latest",
    "feature_implement_workflow_list",
    "feature_implement_workflow_continue",
    "feature_implement_workflow_open",
    "feature_implement_workflow_resume",
    "feature_implement_workflow_update",
    "goal_prose_started",
    "goal_prose_subtask_finished",
    "goal_prose_finished",
  )

internal fun toolsList(): List<*> {
  val response =
    decodeResponse(
      McpStdioServer.handleLine(
        """{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""",
      ),
    )
  return response.fieldMap("result")["tools"] as List<*>
}

internal fun List<*>.schemaFor(toolName: String): Map<String, Any?> {
  val tool = first { item -> JsonSupport.anyToStringAnyMap(item)?.get("name") == toolName }
  return requireNotNull(JsonSupport.anyToStringAnyMap(tool)?.get("inputSchema")).let { schema ->
    requireNotNull(JsonSupport.anyToStringAnyMap(schema))
  }
}

internal fun List<*>.toolNamedOrNull(toolName: String): Map<String, Any?>? =
  firstOrNull { item -> JsonSupport.anyToStringAnyMap(item)?.get("name") == toolName }
    ?.let { JsonSupport.anyToStringAnyMap(it) }

internal fun List<*>.descriptionFor(toolName: String): String =
  requireNotNull(toolNamedOrNull(toolName))["description"].toString()

internal fun Map<String, Any?>.properties(): Map<String, Any?> =
  requireNotNull(JsonSupport.anyToStringAnyMap(this["properties"]))

internal fun Map<String, Any?>.assertRequired(vararg names: String) {
  val required = this["required"] as List<*>
  names.forEach { name -> assertContains(required, name) }
}

internal fun Map<String, Any?>.enumFor(propertyName: String): List<*> {
  val property = requireNotNull(JsonSupport.anyToStringAnyMap(this[propertyName]))
  return requireNotNull(property["enum"] as? List<*>)
}

internal fun enabledStdioTelemetryEnvironment(tempDir: Path): Map<String, String> {
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
    TELEMETRY_PROXY_URL_ENVIRONMENT_KEY to TEST_TELEMETRY_PROXY_URL,
  )
}

internal const val TEST_TELEMETRY_PROXY_URL = "http://127.0.0.1:9/skill-bill-test-telemetry"

internal fun Map<String, String>.withTestTelemetryProxy(): Map<String, String> =
  this + (TELEMETRY_PROXY_URL_ENVIRONMENT_KEY to TEST_TELEMETRY_PROXY_URL)

internal fun toolCallRequest(id: Int, name: String, arguments: Map<String, Any?>): String = JsonSupport.mapToJsonString(
  mapOf(
    "jsonrpc" to "2.0",
    "id" to id,
    "method" to "tools/call",
    "params" to mapOf(
      "name" to name,
      "arguments" to arguments,
    ),
  ),
)

internal fun toolPayload(result: Map<String, Any?>): Map<String, Any?> {
  val content = result["content"] as List<*>
  val textContent = requireNotNull(JsonSupport.anyToStringAnyMap(content.first()))
  return decodeStdioJsonObject(textContent["text"].toString())
}

internal fun decodeToolArguments(rawJson: String): Map<String, Any?> {
  val request = decodeStdioJsonObject(rawJson)
  val params = requireNotNull(JsonSupport.anyToStringAnyMap(request["params"]))
  return requireNotNull(JsonSupport.anyToStringAnyMap(params["arguments"]))
}

internal fun decodeResponse(rawJson: String?): Map<String, Any?> {
  assertNotNull(rawJson)
  return decodeStdioJsonObject(rawJson)
}

internal fun decodeStdioJsonObject(rawJson: String): Map<String, Any?> {
  val parsed = JsonSupport.parseObjectOrNull(rawJson)
  require(parsed != null) { "Expected JSON object but got: $rawJson" }
  val decoded = JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
  require(decoded != null) { "Expected decoded JSON object but got: $rawJson" }
  return decoded
}

internal fun seedGoalBlockedRun(dbPath: Path, workflowId: String) {
  DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
    val store = LifecycleTelemetryStore(connection)
    store.goalStarted(
      GoalStartedRecord(
        issueKey = "SKILL-66",
        featureName = "goal telemetry",
        workflowId = workflowId,
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
        workflowId = workflowId,
        subtaskId = 1,
        subtaskName = "implement",
        status = "blocked",
        startedAt = "2026-06-05T10:00:00Z",
        finishedAt = "2026-06-05T10:05:00Z",
        durationMs = 300_000,
        attemptCount = 1,
        blockedReason = "test failure",
      ),
      "full",
    )
    store.goalFinished(
      GoalFinishedRecord(
        issueKey = "SKILL-66",
        workflowId = workflowId,
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

internal fun Map<String, Any?>.fieldMap(name: String): Map<String, Any?> =
  JsonSupport.anyToStringAnyMap(this[name]).orEmpty()
