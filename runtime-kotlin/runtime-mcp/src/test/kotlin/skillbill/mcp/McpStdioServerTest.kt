package skillbill.mcp

import skillbill.SAMPLE_REVIEW
import skillbill.contracts.JsonSupport
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_URL_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    val result = response.map("result")

    assertTrue(requireNotNull(rawResponse).contains(""""jsonrpc":"2.0""""))
    assertEquals(1, response["id"])
    assertEquals("2025-11-25", result["protocolVersion"])
    assertEquals("skill-bill", result.map("serverInfo")["name"])
    assertTrue(result.map("capabilities").containsKey("tools"))
  }

  @Test
  fun `tools list exposes the Python-compatible inventory`() {
    val response =
      decodeResponse(
        McpStdioServer.handleLine(
          """{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""",
        ),
      )
    val tools = response.map("result")["tools"] as List<*>
    val names = tools.map { tool -> requireNotNull(JsonSupport.anyToStringAnyMap(tool))["name"] }

    assertEquals(
      listOf(
        "doctor",
        "feature_implement_finished",
        "feature_implement_stats",
        "feature_implement_started",
        "feature_implement_workflow_get",
        "feature_implement_workflow_latest",
        "feature_implement_workflow_list",
        "feature_implement_workflow_continue",
        "feature_implement_workflow_open",
        "feature_implement_workflow_resume",
        "feature_implement_workflow_update",
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
      ),
      names,
    )
  }

  @Test
  fun `feature implement lifecycle tools expose required input schemas`() {
    val response =
      decodeResponse(
        McpStdioServer.handleLine(
          """{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""",
        ),
      )
    val tools = response.map("result")["tools"] as List<*>

    val startedSchema = tools.schemaFor("feature_implement_started")
    val finishedSchema = tools.schemaFor("feature_implement_finished")

    assertEquals(false, startedSchema["additionalProperties"])
    assertEquals(false, finishedSchema["additionalProperties"])
    assertTrue((startedSchema["required"] as List<*>).contains("feature_size"))
    assertTrue((startedSchema["required"] as List<*>).contains("issue_key"))
    assertTrue(startedSchema.properties().containsKey("acceptance_criteria_count"))
    assertTrue((finishedSchema["required"] as List<*>).contains("session_id"))
    assertTrue((finishedSchema["required"] as List<*>).contains("completion_status"))
    assertTrue(finishedSchema.properties().containsKey("boundary_history_written"))
  }

  @Test
  fun `tools call wraps native payloads as text content`() {
    val response =
      decodeResponse(
        McpStdioServer.handleLine(
          """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"doctor","arguments":{}}}""",
        ),
      )
    val result = response.map("result")
    val content = result["content"] as List<*>
    val textContent = requireNotNull(JsonSupport.anyToStringAnyMap(content.first()))
    val payload = decodeStdioJsonObject(textContent["text"].toString())

    assertEquals(false, result["isError"])
    assertEquals("text", textContent["type"])
    assertEquals("0.1.0", payload["version"])
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
    assertEquals(false, importResponse.map("result")["isError"])

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
      decodedTriageArguments.stringList("decisions"),
      decodedTriageArguments.toString(),
    )
    val triageResponse =
      decodeResponse(
        McpStdioServer.handleLine(
          triageRequest,
          context,
        ),
      )
    val result = triageResponse.map("result")
    val payload = toolPayload(result)

    assertEquals(false, result["isError"], payload.toString())
    val recorded = payload["recorded"] as List<*>
    assertEquals(2, recorded.size)
    assertEquals("fix_applied", requireNotNull(JsonSupport.anyToStringAnyMap(recorded[0]))["outcome_type"])
    assertEquals("fix_rejected", requireNotNull(JsonSupport.anyToStringAnyMap(recorded[1]))["outcome_type"])
  }
}

private fun List<*>.schemaFor(toolName: String): Map<String, Any?> {
  val tool = first { item -> JsonSupport.anyToStringAnyMap(item)?.get("name") == toolName }
  return requireNotNull(JsonSupport.anyToStringAnyMap(tool)?.get("inputSchema")).let { schema ->
    requireNotNull(JsonSupport.anyToStringAnyMap(schema))
  }
}

private fun Map<String, Any?>.properties(): Map<String, Any?> =
  requireNotNull(JsonSupport.anyToStringAnyMap(this["properties"]))

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
