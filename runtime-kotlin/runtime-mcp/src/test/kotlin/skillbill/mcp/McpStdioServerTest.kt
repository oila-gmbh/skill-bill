package skillbill.mcp

import skillbill.contracts.JsonSupport
import skillbill.mcp.core.McpStdioServer
import skillbill.mcp.core.McpToolSpec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  fun `strict schema coverage publishes required arguments and enums`() {
    assertStrictSchemaCoveragePublished(toolsList())
  }

  @Test
  fun `zero argument workflow tools expose strict empty objects`() {
    val tools = toolsList()

    listOf(
      "feature_verify_workflow_latest",
    ).forEach { toolName ->
      val schema = tools.schemaFor(toolName)

      assertEquals(false, schema["additionalProperties"], toolName)
      assertEquals(emptyMap<String, Any?>(), schema.properties(), toolName)
      assertEquals(emptyList<String>(), schema["required"], toolName)
    }
  }

  @Test
  fun `strict tools reject unknown arguments at the stdio boundary`() {
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
    val response =
      decodeResponse(
        McpStdioServer.handleLine(
          toolCallRequest(
            id = 100,
            name = "feature_verify_workflow_update",
            arguments = mapOf(
              "workflow_id" to "wfl-test",
              "workflow_status" to "running",
              "current_step_id" to "code_review",
              "step_updates" to listOf(
                mapOf(
                  "step_id" to "code_review",
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
}
