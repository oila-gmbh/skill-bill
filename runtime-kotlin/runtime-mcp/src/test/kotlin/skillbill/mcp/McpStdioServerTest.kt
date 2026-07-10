package skillbill.mcp

import skillbill.SAMPLE_REVIEW
import skillbill.SkillBillVersion
import skillbill.application.telemetry.specInputTypes
import skillbill.contracts.JsonSupport
import skillbill.db.core.DatabaseRuntime
import skillbill.db.telemetry.LifecycleTelemetryStore
import skillbill.mcp.core.McpRuntimeContext
import skillbill.mcp.core.McpStdioServer
import skillbill.mcp.core.McpToolSpec
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_URL_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LargeClass")
class McpStdioServerTest {
  @Test
  fun `initialize returns MCP server capabilities`() {
    val rawResponse =
      McpStdioServer.handleLine(
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
      )
    val response =
      decodeResponse(
        rawResponse,
      )
    val result = response.fieldMap("result")

    assertTrue(requireNotNull(rawResponse).contains(""""jsonrpc":"2.0""""))
    assertEquals(1, response["id"])
    assertEquals("2025-11-25", result["protocolVersion"])
    assertEquals("skill-bill", result.fieldMap("serverInfo")["name"])
    assertTrue(result.fieldMap("capabilities").containsKey("tools"))
  }

  @Test
  fun `tools list exposes the expected inventory`() {
    val response =
      decodeResponse(
        McpStdioServer.handleLine(
          """{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""",
        ),
      )
    val tools = response.fieldMap("result")["tools"] as List<*>
    val names = tools.map { tool -> requireNotNull(JsonSupport.anyToStringAnyMap(tool))["name"] }

    assertEquals(expectedToolInventory, names)
  }

  @Test
  fun `feature task prose lifecycle tools expose required input schemas`() {
    val response =
      decodeResponse(
        McpStdioServer.handleLine(
          """{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""",
        ),
      )
    val tools = response.fieldMap("result")["tools"] as List<*>

    val startedSchema = tools.schemaFor("feature_task_prose_started")
    val finishedSchema = tools.schemaFor("feature_task_prose_finished")

    assertEquals(false, startedSchema["additionalProperties"])
    assertEquals(false, finishedSchema["additionalProperties"])
    assertTrue((startedSchema["required"] as List<*>).contains("feature_size"))
    assertTrue((startedSchema["required"] as List<*>).contains("issue_key"))
    assertTrue(startedSchema.properties().containsKey("acceptance_criteria_count"))
    assertEquals(specInputTypes, startedSchema.properties().arrayItemsEnumFor("spec_input_types"))
    assertTrue((finishedSchema["required"] as List<*>).contains("session_id"))
    assertTrue((finishedSchema["required"] as List<*>).contains("completion_status"))
    assertTrue(finishedSchema.properties().containsKey("boundary_history_written"))
  }

  @Test
  fun `priority validating persisting and telemetry tools expose strict input schemas`() {
    val tools = toolsList()

    priorityStrictToolNames.forEach { toolName ->
      val schema = tools.schemaFor(toolName)

      assertEquals("object", schema["type"], toolName)
      assertEquals(false, schema["additionalProperties"], toolName)
      assertFalse(schema == McpToolSpec.openObjectSchema(), toolName)
    }
  }

  @Test
  @Suppress("LongMethod")
  fun `strict schema coverage publishes required arguments and enums`() {
    val tools = toolsList()

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
    assertEquals(
      listOf("completed", "abandoned_at_review", "abandoned_at_audit", "error"),
      tools.schemaFor("feature_verify_finished").properties().enumFor("completion_status"),
    )
    assertEquals(
      listOf("all_pass", "had_gaps", "skipped"),
      tools.schemaFor("feature_verify_finished").properties().enumFor("audit_result"),
    )
    assertEquals(
      listOf("pending", "running", "completed", "failed", "abandoned", "blocked"),
      tools.schemaFor("feature_task_prose_workflow_update").properties().enumFor("workflow_status"),
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
    assertEquals(
      setOf("workflow_id", "issue_key", "subtask_id"),
      tools.schemaFor("feature_task_prose_workflow_continue").properties().keys,
    )
    assertEquals(
      "integer",
      tools.schemaFor("feature_task_prose_workflow_continue").properties().fieldMap("subtask_id")["type"],
    )
    tools.schemaFor("telemetry_remote_stats").assertRequired("workflow")
    assertEquals(
      listOf(
        "verify",
        "implement",
        "bill-feature-task",
        "bill-feature-verify",
        "feature-task-prose",
      ),
      tools.schemaFor("telemetry_remote_stats").properties().enumFor("workflow"),
    )
    assertEquals(
      listOf("", "day", "week"),
      tools.schemaFor("telemetry_remote_stats").properties().enumFor("group_by"),
    )
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
    tools.schemaFor("new_skill_scaffold").assertRequired("payload")
    tools.schemaFor("import_review").assertRequired("review_text")
    tools.schemaFor("triage_findings").assertRequired("review_run_id", "decisions")
  }

  @Test
  fun `zero argument workflow tools expose strict empty objects`() {
    val tools = toolsList()

    listOf(
      "feature_task_prose_workflow_latest",
      "feature_verify_workflow_latest",
      "feature_task_runtime_workflow_latest",
    ).forEach { toolName ->
      val schema = tools.schemaFor(toolName)

      assertEquals(false, schema["additionalProperties"], toolName)
      assertEquals(emptyMap<String, Any?>(), schema.properties(), toolName)
      assertEquals(emptyList<String>(), schema["required"], toolName)
    }
  }

  @Test
  fun `strict tools reject unknown arguments at the stdio boundary`() {
    // F-003 (review-run rvw-20260519-162500-a2d4): unknown-argument
    // violations are now surfaced as MCP `isError=true` results so the
    // strict-args gate and the schema-validator inside
    // `McpToolDispatcher` share a single transport shape. See
    // `x-coherence-checks.argument-shape-failures-surface` in
    // `orchestration/contracts/telemetry-event-schema.yaml`.
    val response =
      decodeResponse(
        McpStdioServer.handleLine(
          toolCallRequest(
            id = 99,
            name = "resolve_learnings",
            arguments = mapOf("repo" to "skill-bill", "unexpected" to true),
          ),
        ),
      )
    val result = response.fieldMap("result")
    val errorPayload = toolPayload(result)

    assertEquals(true, result["isError"])
    assertEquals("resolve_learnings", errorPayload["tool"])
    assertContains(errorPayload["error"].toString(), "Unknown argument(s) for resolve_learnings: unexpected")
  }

  @Test
  fun `strict tools reject unknown nested arguments at the stdio boundary`() {
    // F-003 (review-run rvw-20260519-162500-a2d4): nested unknown
    // arguments also surface via `isError=true` for a consistent
    // client contract; see the sibling test above.
    val response =
      decodeResponse(
        McpStdioServer.handleLine(
          toolCallRequest(
            id = 100,
            name = "feature_task_prose_workflow_update",
            arguments = mapOf(
              "workflow_id" to "wfl-test",
              "workflow_status" to "running",
              "current_step_id" to "implement",
              "step_updates" to listOf(
                mapOf(
                  "step_id" to "implement",
                  "status" to "running",
                  "attempt_count" to 1,
                  "unexpected" to true,
                ),
              ),
            ),
          ),
        ),
      )
    val result = response.fieldMap("result")
    val errorPayload = toolPayload(result)

    assertEquals(true, result["isError"])
    assertContains(errorPayload["error"].toString(), "step_updates[0].unexpected")
  }

  @Test
  fun `canonical feature_task_runtime tools dispatch to the task runtime family and validate payloads`() {
    // SKILL-86 (AC4): the runtime leaf feature_task_runtime_* is canonical, dispatches correctly, and
    // validates payloads.
    val tempDir = Files.createTempDirectory("skillbill-stdio-feature-task")
    val context = McpRuntimeContext(environment = enabledStdioTelemetryEnvironment(tempDir), userHome = tempDir)

    val started =
      decodeResponse(
        McpStdioServer.handleLine(
          toolCallRequest(
            id = 1,
            name = "feature_task_runtime_started",
            arguments = mapOf(
              "feature_size" to "SMALL",
              "issue_key" to "SKILL-650",
              "feature_name" to "canonical runtime surface",
            ),
          ),
          context,
        ),
      )
    assertEquals(false, started.fieldMap("result")["isError"], started.toString())

    // Missing-required payloads must fail validation exactly as before.
    val invalid =
      decodeResponse(
        McpStdioServer.handleLine(
          toolCallRequest(
            id = 2,
            name = "feature_task_runtime_started",
            arguments = mapOf("feature_size" to "SMALL"),
          ),
          context,
        ),
      )
    assertEquals(true, invalid.fieldMap("result")["isError"], invalid.toString())
  }

  @Test
  fun `goal prose blocked subtask with blank reason persists normalized blocked_reason`() {
    val tempDir = Files.createTempDirectory("skillbill-stdio-goal-subtask-blocked")
    val context = McpRuntimeContext(environment = enabledStdioTelemetryEnvironment(tempDir), userHome = tempDir)

    val response =
      decodeResponse(
        McpStdioServer.handleLine(
          toolCallRequest(
            id = 1,
            name = "goal_prose_subtask_finished",
            arguments = mapOf(
              "issue_key" to "SKILL-109",
              "workflow_id" to "goal-wf-109",
              "subtask_id" to 3,
              "subtask_name" to "field population",
              "status" to "blocked",
              "started_at" to "2026-07-09T10:00:00Z",
              "finished_at" to "2026-07-09T10:05:00Z",
              "duration_ms" to 300_000,
              "attempt_count" to 1,
              "blocked_reason" to " ",
            ),
          ),
          context,
        ),
      )

    assertEquals(false, response.fieldMap("result")["isError"], response.toString())
    DatabaseRuntime.ensureDatabase(tempDir.resolve("metrics.db")).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery(
          """
          SELECT payload_json
          FROM telemetry_outbox
          WHERE event_name = 'skillbill_goal_subtask_finished'
          """.trimIndent(),
        ).use { resultSet ->
          assertTrue(resultSet.next())
          val payload = decodeStdioJsonObject(resultSet.getString("payload_json"))
          assertEquals("runtime: Goal subtask 3 is blocked.", payload["blocked_reason"])
        }
      }
    }
  }

  @Test
  fun `legacy feature_implement hidden aliases stay callable and validate prose payloads`() {
    // SKILL-86 (AC2 USER-CONFIRMED): feature_implement_* hidden aliases dispatch to the prose handlers
    // with no behavioral difference (same prose family, same payload validation against the canonical
    // feature_task_prose_* schema).
    val tempDir = Files.createTempDirectory("skillbill-stdio-feature-task-alias")
    val context = McpRuntimeContext(environment = enabledStdioTelemetryEnvironment(tempDir), userHome = tempDir)

    val started =
      decodeResponse(
        McpStdioServer.handleLine(
          toolCallRequest(
            id = 1,
            name = "feature_implement_started",
            arguments = mapOf(
              "feature_size" to "SMALL",
              "acceptance_criteria_count" to 3,
              "open_questions_count" to 0,
              "spec_input_types" to listOf("markdown_file"),
              "spec_word_count" to 100,
              "rollout_needed" to false,
              "feature_name" to "hidden alias surface",
              "issue_key" to "SKILL-650",
              "spec_summary" to "summary",
            ),
          ),
          context,
        ),
      )
    assertEquals(false, started.fieldMap("result")["isError"], started.toString())

    val invalid =
      decodeResponse(
        McpStdioServer.handleLine(
          toolCallRequest(
            id = 2,
            name = "feature_implement_started",
            arguments = mapOf("feature_size" to "SMALL"),
          ),
          context,
        ),
      )
    assertEquals(true, invalid.fieldMap("result")["isError"], invalid.toString())
  }

  @Test
  fun `runtime leaf workflow tools round-trip a lifecycle in the TASK_RUNTIME family`() {
    // SKILL-86: the runtime leaf is the canonical feature_task_runtime_* family. Round-trip a workflow_*
    // lifecycle (open -> latest -> get -> update -> get) and assert externally meaningful persisted
    // outcomes (workflow_id continuity + workflow_status + current_step_id), not telemetry.
    val tempDir = Files.createTempDirectory("skillbill-stdio-feature-task-runtime")
    val context = McpRuntimeContext(environment = enabledStdioTelemetryEnvironment(tempDir), userHome = tempDir)

    var nextId = 0
    fun call(name: String, arguments: Map<String, Any?> = emptyMap()): Map<String, Any?> =
      dispatchTool(id = ++nextId, name = name, arguments = arguments, context = context).also {
        assertEquals("ok", it["status"], it.toString())
      }

    val openedId = call("feature_task_runtime_workflow_open")["workflow_id"].toString()
    assertEquals(openedId, call("feature_task_runtime_workflow_latest")["workflow_id"])
    assertEquals(
      openedId,
      call("feature_task_runtime_workflow_get", mapOf("workflow_id" to openedId))["workflow_id"],
    )

    val firstStep = featureTaskWorkflowStepIds().first()
    call(
      "feature_task_runtime_workflow_update",
      mapOf(
        "workflow_id" to openedId,
        "workflow_status" to "running",
        "current_step_id" to firstStep,
        "step_updates" to listOf(
          mapOf("step_id" to firstStep, "status" to "running", "attempt_count" to 1),
        ),
      ),
    )

    val afterUpdate = call("feature_task_runtime_workflow_get", mapOf("workflow_id" to openedId))
    assertEquals("running", afterUpdate["workflow_status"], afterUpdate.toString())
    assertEquals(firstStep, afterUpdate["current_step_id"], afterUpdate.toString())
  }

  @Test
  fun `prose and runtime canonical leaves expose strict input schemas`() {
    // SKILL-86: the two record-owning leaves (feature_task_prose_* and feature_task_runtime_*) are
    // each registered with their own strict schemas. Assert every paired prose/runtime lifecycle tool
    // publishes a strict object schema so neither leaf silently degrades to the open-object fallback.
    val tools = toolsList()

    canonicalTaskRuntimeToolNames
      .filterNot { it.endsWith("_stats") }
      .forEach { runtime ->
        val prose = runtime.replaceFirst("feature_task_runtime_", "feature_task_prose_")
        assertEquals(false, tools.schemaFor(runtime)["additionalProperties"], runtime)
        assertEquals(false, tools.schemaFor(prose)["additionalProperties"], prose)
      }
  }

  @Test
  fun `canonical leaves carry no deprecation language and legacy families are absent from the registry`() {
    // SKILL-86 (AC2/AC3/AC4): feature_task_prose_* and feature_task_runtime_* are canonical leaves with
    // no deprecation/EXPERIMENTAL language; the legacy feature_implement_* family and the bare
    // feature_task_* family are absent from the registry / tools-list entirely.
    val tools = toolsList()

    canonicalTaskRuntimeToolNames.forEach { runtime ->
      val prose = runtime.replaceFirst("feature_task_runtime_", "feature_task_prose_")
      assertFalse(tools.descriptionFor(runtime).contains("Deprecated"), runtime)
      assertFalse(tools.descriptionFor(runtime).contains("EXPERIMENTAL"), runtime)
      assertFalse(tools.descriptionFor(prose).contains("Deprecated"), prose)
      assertFalse(tools.descriptionFor(prose).contains("EXPERIMENTAL"), prose)
    }

    assertNull(tools.toolNamedOrNull("feature_implement_started"))
    assertNull(tools.toolNamedOrNull("feature_implement_workflow_update"))
    assertNull(tools.toolNamedOrNull("feature_task_started"))
    assertNull(tools.toolNamedOrNull("feature_task_workflow_update"))
  }

  @Test
  fun `legacy feature_implement aliases still route through the dispatcher`() {
    // SKILL-86 (AC2 USER-CONFIRMED): feature_implement_* names are removed from the registry/tools-list
    // but remain HIDDEN DISPATCHER ALIASES routing to the prose handlers for in-flight runs. Opening via
    // the legacy alias and reading back through the canonical leaf must observe the SAME prose workflow.
    val tempDir = Files.createTempDirectory("skillbill-stdio-implement-alias")
    val context = McpRuntimeContext(environment = enabledStdioTelemetryEnvironment(tempDir), userHome = tempDir)

    var nextId = 0
    fun call(name: String, arguments: Map<String, Any?> = emptyMap()): Map<String, Any?> =
      dispatchTool(id = ++nextId, name = name, arguments = arguments, context = context).also {
        assertEquals("ok", it["status"], it.toString())
      }

    val aliasOpenedId = call("feature_implement_workflow_open")["workflow_id"].toString()
    val canonicalLatest = call("feature_task_prose_workflow_latest")
    assertEquals(aliasOpenedId, canonicalLatest["workflow_id"], canonicalLatest.toString())

    val canonicalGet = call("feature_task_prose_workflow_get", mapOf("workflow_id" to aliasOpenedId))
    assertEquals(aliasOpenedId, canonicalGet["workflow_id"], canonicalGet.toString())
  }

  @Test
  fun `legacy feature_implement alias surface enforces strict unknown-argument rejection`() {
    // SKILL-86 (F-004): the strict unknown-property gate resolves legacy aliases to their canonical
    // registry entry, so calling an alias with an unknown argument is rejected like the canonical name.
    val response =
      decodeResponse(
        McpStdioServer.handleLine(
          toolCallRequest(
            id = 1,
            name = "feature_implement_workflow_open",
            arguments = mapOf("not_a_real_argument" to "x"),
          ),
        ),
      )
    val result = response.fieldMap("result")
    val content = result["content"] as List<*>
    val textContent = requireNotNull(JsonSupport.anyToStringAnyMap(content.first()))

    assertEquals(true, result["isError"], result.toString())
    assertContains(textContent["text"].toString(), "not_a_real_argument")
  }

  @Test
  fun `tools call wraps native payloads as text content`() {
    val response =
      decodeResponse(
        McpStdioServer.handleLine(
          """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"doctor","arguments":{}}}""",
        ),
      )
    val result = response.fieldMap("result")
    val content = result["content"] as List<*>
    val textContent = requireNotNull(JsonSupport.anyToStringAnyMap(content.first()))
    val payload = decodeStdioJsonObject(textContent["text"].toString())

    assertEquals(false, result["isError"])
    assertEquals("text", textContent["type"])
    assertEquals(SkillBillVersion.VALUE, payload["version"])
  }

  @Test
  fun `tools call triage accepts individual numbered decisions`() {
    val tempDir = Files.createTempDirectory("skillbill-stdio-triage")
    val context = McpRuntimeContext(environment = enabledStdioTelemetryEnvironment(tempDir), userHome = tempDir)

    val importResponse =
      decodeResponse(
        McpStdioServer.handleLine(
          toolCallRequest(
            id = 1,
            name = "import_review",
            arguments = mapOf("review_text" to SAMPLE_REVIEW.trimIndent()),
          ),
          context,
        ),
      )
    assertEquals(false, importResponse.fieldMap("result")["isError"])

    val triageRequest =
      toolCallRequest(
        id = 2,
        name = "triage_findings",
        arguments = mapOf(
          "review_run_id" to "rvw-20260402-001",
          "decisions" to listOf("1 fix", "2 reject"),
        ),
      )
    val decodedTriageArguments = decodeToolArguments(triageRequest)
    assertEquals(
      listOf("1 fix", "2 reject"),
      decodedTriageArguments["decisions"],
      decodedTriageArguments.toString(),
    )
    val triageResponse =
      decodeResponse(
        McpStdioServer.handleLine(
          triageRequest,
          context,
        ),
      )
    val result = triageResponse.fieldMap("result")
    val payload = toolPayload(result)

    assertEquals(false, result["isError"], payload.toString())
    val recorded = payload["recorded"] as List<*>
    assertEquals(2, recorded.size)
    assertEquals("fix_applied", requireNotNull(JsonSupport.anyToStringAnyMap(recorded[0]))["outcome_type"])
    assertEquals("fix_rejected", requireNotNull(JsonSupport.anyToStringAnyMap(recorded[1]))["outcome_type"])
  }

  @Test
  fun `readian spotlight tool reports unavailable when standalone boundary cannot launch`() {
    val tempDir = Files.createTempDirectory("skillbill-readian-auth-required")
    val context =
      McpRuntimeContext(
        environment = mapOf("SKILL_BILL_READIAN_MCP_COMMAND" to tempDir.resolve("missing-readian-mcp").toString()),
        userHome = tempDir,
      )

    val response =
      decodeResponse(
        McpStdioServer.handleLine(
          toolCallRequest(
            id = 1,
            name = "readian_get_spotlight",
            arguments = mapOf("beat" to "pc-games"),
          ),
          context,
        ),
      )
    val result = response.fieldMap("result")
    val payload = toolPayload(result)

    assertEquals(false, result["isError"], payload.toString())
    assertEquals("error", payload["status"])
    assertEquals("readian_mcp_unavailable", payload["error_type"])
  }

  @Test
  fun `readian topic query bridges skill bill arguments to standalone mcp query arguments`() {
    val tempDir = Files.createTempDirectory("skillbill-readian-bridge")
    val capturedInput = tempDir.resolve("captured-stdin.jsonl")
    val bridgeResponse =
      """{"jsonrpc":"2.0","id":2,"result":{"isError":false,"structuredContent":""" +
        """{"status":"ok","tool":"readian_get_articles_for_topic_query","query":"pc gaming"}}}"""
    val script = fakeReadianMcpScript(
      tempDir,
      bridgeResponse,
    )
    val context =
      McpRuntimeContext(
        environment = mapOf(
          "SKILL_BILL_READIAN_MCP_COMMAND" to script.toString(),
          "CAPTURE_FILE" to capturedInput.toString(),
        ),
        userHome = tempDir,
      )

    val response =
      decodeResponse(
        McpStdioServer.handleLine(
          toolCallRequest(
            id = 2,
            name = "readian_get_articles_for_topic_query",
            arguments = mapOf(
              "topic_query" to "pc gaming",
              "date" to "2026-04-26",
              "subscribed_only" to true,
            ),
          ),
          context,
        ),
      )
    val payload = toolPayload(response.fieldMap("result"))
    val captured = Files.readString(capturedInput)

    assertEquals("ok", payload["status"])
    assertEquals("pc gaming", payload["query"])
    assertTrue(captured.contains(""""query":"pc gaming""""))
    assertFalse(captured.contains(""""topic_query":"""))
  }

  @Test
  fun `readian topic query tool exposes authenticated topic arguments`() {
    val tempDir = Files.createTempDirectory("skillbill-readian-topic-query")
    val context =
      McpRuntimeContext(
        environment = mapOf("SKILL_BILL_READIAN_AUTHENTICATED" to "true"),
        userHome = tempDir,
      )

    val response =
      decodeResponse(
        McpStdioServer.handleLine(
          toolCallRequest(
            id = 2,
            name = "readian_get_articles_for_topic_query",
            arguments = mapOf(
              "topic_query" to "pc gaming",
              "date" to "2026-04-26",
              "subscribed_only" to true,
            ),
          ),
          context,
        ),
      )
    val payload = toolPayload(response.fieldMap("result"))
    val data = requireNotNull(JsonSupport.anyToStringAnyMap(payload["data"]))

    assertEquals("ok", payload["status"])
    assertEquals("readian_get_articles_for_topic_query", payload["tool"])
    assertEquals("topic_query", data["feed_source"])
    assertEquals("pc gaming", data["topic_query"])
    assertEquals(true, data["subscribed_only"])
  }

  @Test
  fun `readian tool responses redact token and session material from log safe payloads`() {
    val tempDir = Files.createTempDirectory("skillbill-readian-redaction")
    val context =
      McpRuntimeContext(
        environment = mapOf("SKILL_BILL_READIAN_AUTHENTICATED" to "true"),
        userHome = tempDir,
      )

    val response =
      decodeResponse(
        McpStdioServer.handleLine(
          toolCallRequest(
            id = 2,
            name = "readian_save_candidate",
            arguments = mapOf(
              "candidate_id" to "candidate-1",
              "refresh_token" to "readian_rt_supersecret",
              "notes" to "authorization=readian_token_should_not_leak Bearer abc.def.ghi",
              "nested" to mapOf("session_cookie" to "readian_session_supersecret"),
            ),
          ),
          context,
        ),
      )
    val payload = toolPayload(response.fieldMap("result"))
    val serialized = JsonSupport.mapToJsonString(payload)

    assertEquals("ok", payload["status"])
    assertFalse(serialized.contains("readian_rt_supersecret"), serialized)
    assertFalse(serialized.contains("readian_token_should_not_leak"), serialized)
    assertFalse(serialized.contains("readian_session_supersecret"), serialized)
    assertFalse(serialized.contains("abc.def.ghi"), serialized)
    assertTrue(serialized.contains("[REDACTED]"), serialized)
  }

  @Test
  fun `goal_stats dispatch returns populated payload for a seeded store`() {
    val tempDir = Files.createTempDirectory("skillbill-stdio-goal-stats-seeded")
    val context = McpRuntimeContext(environment = enabledStdioTelemetryEnvironment(tempDir), userHome = tempDir)
    seedGoalBlockedRun(tempDir.resolve("metrics.db"), workflowId = "wf-stdio-1")

    val response = decodeResponse(
      McpStdioServer.handleLine(
        toolCallRequest(id = 1, name = "goal_stats", arguments = emptyMap()),
        context,
      ),
    )
    val result = response.fieldMap("result")
    val payload = toolPayload(result)

    assertEquals(false, result["isError"], payload.toString())
    assertEquals("bill-goal-run", payload["workflow"])
    assertEquals(1, payload["total_runs"])
    assertEquals(1, payload["blocked_runs"])
    val topBlocked = payload["top_blocked_subtasks"] as List<*>
    assertEquals(1, topBlocked.size)
    val blockedEntry = requireNotNull(JsonSupport.anyToStringAnyMap(topBlocked.first()))
    assertEquals("test failure", blockedEntry["blocked_reason"])
  }

  @Test
  fun `goal_stats dispatch returns zero-count payload for empty store`() {
    val tempDir = Files.createTempDirectory("skillbill-stdio-goal-stats-empty")
    val context = McpRuntimeContext(environment = enabledStdioTelemetryEnvironment(tempDir), userHome = tempDir)

    val response = decodeResponse(
      McpStdioServer.handleLine(
        toolCallRequest(id = 1, name = "goal_stats", arguments = emptyMap()),
        context,
      ),
    )
    val result = response.fieldMap("result")
    val payload = toolPayload(result)

    assertEquals(false, result["isError"], payload.toString())
    assertEquals("bill-goal-run", payload["workflow"])
    assertEquals(0, payload["total_runs"])
    assertEquals(null, payload["most_recent_run"])
    val topBlocked = payload["top_blocked_subtasks"] as List<*>
    assertTrue(topBlocked.isEmpty())
  }
}

private val expectedToolInventory =
  listOf(
    "doctor",
    "feature_task_prose_finished",
    "feature_task_prose_stats",
    "feature_task_prose_started",
    "feature_task_prose_workflow_get",
    "feature_task_prose_workflow_latest",
    "feature_task_prose_workflow_list",
    "feature_task_prose_workflow_continue",
    "feature_task_prose_workflow_open",
    "feature_task_prose_workflow_resume",
    "feature_task_prose_workflow_update",
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
    "feature_task_runtime_finished",
    "feature_task_runtime_started",
    "feature_task_runtime_stats",
    "feature_task_runtime_workflow_get",
    "feature_task_runtime_workflow_latest",
    "feature_task_runtime_workflow_list",
    "feature_task_runtime_workflow_continue",
    "feature_task_runtime_workflow_open",
    "feature_task_runtime_workflow_resume",
    "feature_task_runtime_workflow_update",
    "goal_prose_finished",
    "goal_prose_started",
    "goal_prose_subtask_finished",
    "goal_stats",
    "import_review",
    "new_skill_scaffold",
    "pr_description_generated",
    "quality_check_finished",
    "quality_check_started",
    "readian_auth_status",
    "readian_get_article",
    "readian_get_articles_for_topic_query",
    "readian_get_spotlight",
    "readian_mark_story_status",
    "readian_save_candidate",
    "resolve_learnings",
    "review_stats",
    "telemetry_proxy_capabilities",
    "telemetry_remote_stats",
    "triage_findings",
    "update_check",
  )

private val priorityStrictToolNames =
  listOf(
    "feature_task_prose_started",
    "feature_task_prose_finished",
    "feature_task_runtime_started",
    "feature_task_runtime_finished",
    "feature_verify_started",
    "feature_verify_finished",
    "quality_check_started",
    "quality_check_finished",
    "pr_description_generated",
    "import_review",
    "triage_findings",
    "resolve_learnings",
    "feature_task_prose_workflow_open",
    "feature_task_prose_workflow_update",
    "feature_task_prose_workflow_get",
    "feature_task_prose_workflow_list",
    "feature_task_prose_workflow_latest",
    "feature_task_prose_workflow_resume",
    "feature_task_prose_workflow_continue",
    "feature_verify_workflow_open",
    "feature_verify_workflow_update",
    "feature_verify_workflow_get",
    "feature_verify_workflow_list",
    "feature_verify_workflow_latest",
    "feature_verify_workflow_resume",
    "feature_verify_workflow_continue",
    "feature_task_runtime_workflow_open",
    "feature_task_runtime_workflow_update",
    "feature_task_runtime_workflow_get",
    "feature_task_runtime_workflow_list",
    "feature_task_runtime_workflow_latest",
    "feature_task_runtime_workflow_resume",
    "feature_task_runtime_workflow_continue",
    "new_skill_scaffold",
  )

private val canonicalTaskRuntimeToolNames =
  listOf(
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
  )

private fun dispatchTool(
  id: Int,
  name: String,
  arguments: Map<String, Any?>,
  context: McpRuntimeContext,
): Map<String, Any?> {
  val response = decodeResponse(McpStdioServer.handleLine(toolCallRequest(id, name, arguments), context))
  return toolPayload(response.fieldMap("result"))
}

private fun featureTaskWorkflowStepIds(): List<String> =
  toolsList().schemaFor("feature_task_runtime_workflow_update").properties()
    .enumFor("current_step_id").map { it.toString() }

private fun toolsList(): List<*> {
  val response =
    decodeResponse(
      McpStdioServer.handleLine(
        """{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""",
      ),
    )
  return response.fieldMap("result")["tools"] as List<*>
}

private fun List<*>.schemaFor(toolName: String): Map<String, Any?> {
  val tool = first { item -> JsonSupport.anyToStringAnyMap(item)?.get("name") == toolName }
  return requireNotNull(JsonSupport.anyToStringAnyMap(tool)?.get("inputSchema")).let { schema ->
    requireNotNull(JsonSupport.anyToStringAnyMap(schema))
  }
}

private fun List<*>.toolNamedOrNull(toolName: String): Map<String, Any?>? =
  firstOrNull { item -> JsonSupport.anyToStringAnyMap(item)?.get("name") == toolName }
    ?.let { JsonSupport.anyToStringAnyMap(it) }

private fun List<*>.descriptionFor(toolName: String): String =
  requireNotNull(toolNamedOrNull(toolName))["description"].toString()

private fun Map<String, Any?>.properties(): Map<String, Any?> =
  requireNotNull(JsonSupport.anyToStringAnyMap(this["properties"]))

private fun Map<String, Any?>.assertRequired(vararg names: String) {
  val required = this["required"] as List<*>
  names.forEach { name -> assertContains(required, name) }
}

private fun Map<String, Any?>.enumFor(propertyName: String): List<*> {
  val property = requireNotNull(JsonSupport.anyToStringAnyMap(this[propertyName]))
  return requireNotNull(property["enum"] as? List<*>)
}

private fun Map<String, Any?>.arrayItemsEnumFor(propertyName: String): List<*> {
  val property = requireNotNull(JsonSupport.anyToStringAnyMap(this[propertyName]))
  val items = requireNotNull(JsonSupport.anyToStringAnyMap(property["items"]))
  return requireNotNull(items["enum"] as? List<*>)
}

private fun enabledStdioTelemetryEnvironment(tempDir: Path): Map<String, String> {
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

private const val TEST_TELEMETRY_PROXY_URL = "http://127.0.0.1:9/skill-bill-test-telemetry"

private fun toolCallRequest(id: Int, name: String, arguments: Map<String, Any?>): String = JsonSupport.mapToJsonString(
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

private fun fakeReadianMcpScript(directory: Path, response: String): Path {
  val script = directory.resolve("fake-readian-mcp")
  Files.writeString(
    script,
    """
    #!/bin/sh
    : > "${'$'}CAPTURE_FILE"
    count=0
    while [ "${'$'}count" -lt 3 ] && IFS= read -r line; do
      printf '%s\n' "${'$'}line" >> "${'$'}CAPTURE_FILE"
      count="${'$'}((count + 1))"
    done
    printf '%s\n' '$response'
    """.trimIndent() + "\n",
  )
  script.toFile().setExecutable(true)
  return script
}

private fun toolPayload(result: Map<String, Any?>): Map<String, Any?> {
  val content = result["content"] as List<*>
  val textContent = requireNotNull(JsonSupport.anyToStringAnyMap(content.first()))
  return decodeStdioJsonObject(textContent["text"].toString())
}

private fun decodeToolArguments(rawJson: String): Map<String, Any?> {
  val request = decodeStdioJsonObject(rawJson)
  val params = requireNotNull(JsonSupport.anyToStringAnyMap(request["params"]))
  return requireNotNull(JsonSupport.anyToStringAnyMap(params["arguments"]))
}

private fun decodeResponse(rawJson: String?): Map<String, Any?> {
  assertNotNull(rawJson)
  return decodeStdioJsonObject(rawJson)
}

private fun decodeStdioJsonObject(rawJson: String): Map<String, Any?> {
  val parsed = JsonSupport.parseObjectOrNull(rawJson)
  require(parsed != null) { "Expected JSON object but got: $rawJson" }
  val decoded = JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
  require(decoded != null) { "Expected decoded JSON object but got: $rawJson" }
  return decoded
}

private fun seedGoalBlockedRun(dbPath: Path, workflowId: String) {
  DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
    val store = LifecycleTelemetryStore(connection)
    store.goalStarted(
      skillbill.telemetry.model.GoalStartedRecord(
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
      skillbill.telemetry.model.GoalSubtaskFinishedRecord(
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
      skillbill.telemetry.model.GoalFinishedRecord(
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

private fun Map<String, Any?>.fieldMap(name: String): Map<String, Any?> =
  JsonSupport.anyToStringAnyMap(this[name]).orEmpty()
