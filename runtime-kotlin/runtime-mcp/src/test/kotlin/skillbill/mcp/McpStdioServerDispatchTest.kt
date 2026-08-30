package skillbill.mcp

import skillbill.SAMPLE_REVIEW
import skillbill.SkillBillVersion
import skillbill.contracts.JsonSupport
import skillbill.db.core.DatabaseRuntime
import skillbill.db.telemetry.LifecycleTelemetryStore
import skillbill.mcp.core.McpRuntimeContext
import skillbill.mcp.core.McpStdioServer
import skillbill.mcp.core.McpToolSpec
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_URL_ENVIRONMENT_KEY
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpStdioServerDispatchTest {
  @Test
  fun `canonical leaves carry no deprecation language and legacy families are absent from the registry`() {
    val tools = toolsList()

    verifyLifecycleToolNames.forEach { verify ->
      assertFalse(tools.descriptionFor(verify).contains("Deprecated"), verify)
      assertFalse(tools.descriptionFor(verify).contains("EXPERIMENTAL"), verify)
    }

    assertNull(tools.toolNamedOrNull("feature_task_started"))
    assertNull(tools.toolNamedOrNull("feature_task_workflow_update"))
  }

  @Test
  fun `SKILL-175 the advertised surface carries no prose family name`() {
    val advertised = toolsList().map { tool -> requireNotNull(JsonSupport.anyToStringAnyMap(tool))["name"].toString() }

    val retired = advertised.filter { name ->
      name.startsWith("feature_task_prose_") ||
        name.startsWith("feature_implement_") ||
        name.startsWith("goal_prose_")
    }
    assertEquals(emptyList(), retired, advertised.toString())
  }

  @Test
  fun `SKILL-132 removed tools are absent from discovery`() {
    // SKILL-132 subtask 4: the duplicate feature_task_runtime_* MCP endpoints, the
    // CLI-duplicated continuation lookup, and the Readian bridge are gone. The foreground
    // runtime driver owns those services directly, so no MCP surface may advertise them.
    val tools = toolsList()

    removedToolNames.forEach { removed ->
      assertNull(tools.toolNamedOrNull(removed), removed)
    }
  }

  @Test
  fun `SKILL-132 removed tools report the typed unknown-tool error on dispatch`() {
    removedToolNames.forEach { removed ->
      val response = decodeResponse(
        McpStdioServer.handleLine(toolCallRequest(id = 1, name = removed, arguments = emptyMap())),
      )
      val result = response.fieldMap("result")
      val content = result["content"] as List<*>
      val textContent = requireNotNull(JsonSupport.anyToStringAnyMap(content.first()))

      assertEquals(true, result["isError"], removed)
      assertContains(textContent["text"].toString(), "Unknown MCP tool '$removed'")
    }
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

}
