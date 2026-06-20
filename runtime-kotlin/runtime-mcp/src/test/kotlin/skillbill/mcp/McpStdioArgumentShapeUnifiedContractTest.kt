package skillbill.mcp

import skillbill.contracts.JsonSupport
import skillbill.mcp.core.McpStdioServer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F-003 (review-run rvw-20260519-162500-a2d4): both argument-shape
 * failure paths — the strict-args gate in `McpStdioServer.callToolResult`
 * (unknown / additional property at the JSON-RPC `arguments` map) AND
 * the schema validator in `McpToolDispatcher.call` (missing required,
 * enum mismatch, type mismatch, oneOf/discriminator mismatch) — surface
 * uniformly as MCP `isError=true` results. Transport-level JSON-RPC
 * errors are reserved for protocol violations only. This test pins the
 * unified contract so future drift between the two seams fails the
 * build. See also
 * `orchestration/contracts/telemetry-event-schema.yaml`
 * `x-coherence-checks.argument-shape-failures-surface`.
 */
class McpStdioArgumentShapeUnifiedContractTest {

  @Test
  fun `strict-args unknown property surfaces as isError=true`() {
    val response =
      decodeStdioObject(
        McpStdioServer.handleLine(
          stdioToolCallRequest(
            id = 401,
            name = "resolve_learnings",
            arguments = mapOf("repo" to "skill-bill", "unexpected" to true),
          ),
        ),
      )

    assertNull(response["error"], "argument-shape failure must not use the JSON-RPC transport error envelope")
    val result = JsonSupport.anyToStringAnyMap(response["result"])
    assertNotNull(result, "argument-shape failure must surface as a JSON-RPC `result` envelope (not `error`)")
    assertEquals(true, result["isError"])
    val payload = decodeFirstTextContent(result)
    assertEquals("error", payload["status"])
    assertEquals("resolve_learnings", payload["tool"])
    assertContains(payload["error"].toString(), "Unknown argument(s) for resolve_learnings: unexpected")
  }

  @Test
  fun `schema validator missing required field surfaces as isError=true`() {
    // `feature_task_prose_started` requires `feature_size` etc.; omit
    // ALL required keys so the schema validator throws inside the
    // dispatcher. The dispatcher's
    // `InvalidTelemetryEventSchemaError` is mapped by
    // `McpStdioServer.callToolResult` to `isError=true`, mirroring the
    // strict-args path above.
    val response =
      decodeStdioObject(
        McpStdioServer.handleLine(
          stdioToolCallRequest(
            id = 402,
            name = "feature_task_prose_started",
            arguments = emptyMap(),
          ),
        ),
      )

    assertNull(response["error"], "argument-shape failure must not use the JSON-RPC transport error envelope")
    val result = JsonSupport.anyToStringAnyMap(response["result"])
    assertNotNull(result, "argument-shape failure must surface as a JSON-RPC `result` envelope (not `error`)")
    assertEquals(true, result["isError"])
    val payload = decodeFirstTextContent(result)
    assertEquals("error", payload["status"])
    assertEquals("feature_task_prose_started", payload["tool"])
    // The composed message carries the event name and the field path
    // of the first offending required key.
    val lowerError = payload["error"].toString().lowercase()
    assertContains(lowerError, "feature_task_prose_started")
    assertTrue(
      lowerError.contains("feature_size") ||
        lowerError.contains("required") ||
        lowerError.contains("feature_name"),
      "schema validator error should name a required field or the `required` keyword — got '$lowerError'",
    )
  }

  private fun stdioToolCallRequest(id: Int, name: String, arguments: Map<String, Any?>): String =
    JsonSupport.mapToJsonString(
      mapOf(
        "jsonrpc" to "2.0",
        "id" to id,
        "method" to "tools/call",
        "params" to mapOf("name" to name, "arguments" to arguments),
      ),
    )

  private fun decodeStdioObject(rawJson: String?): Map<String, Any?> {
    requireNotNull(rawJson) { "expected JSON-RPC response but got null" }
    val parsed = JsonSupport.parseObjectOrNull(rawJson)
    require(parsed != null) { "expected JSON object but got: $rawJson" }
    val decoded = JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
    require(decoded != null) { "expected decoded JSON object but got: $rawJson" }
    return decoded
  }

  private fun decodeFirstTextContent(result: Map<String, Any?>): Map<String, Any?> {
    val content = result["content"] as List<*>
    val first = requireNotNull(JsonSupport.anyToStringAnyMap(content.first()))
    val text = first["text"].toString()
    val parsed = JsonSupport.parseObjectOrNull(text)
    require(parsed != null) { "expected content[0].text to be a JSON object but got: $text" }
    return requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed)))
  }
}
