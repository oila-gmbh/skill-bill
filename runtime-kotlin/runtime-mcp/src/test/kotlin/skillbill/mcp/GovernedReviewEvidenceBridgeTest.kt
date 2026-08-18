package skillbill.mcp

import skillbill.contracts.JsonSupport
import skillbill.mcp.review.GovernedReviewEvidenceBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GovernedReviewEvidenceBridgeTest {
  @Test
  fun `the bridge advertises exactly the two governed operations`() {
    val reply = requireNotNull(
      GovernedReviewEvidenceBridge.handleLine(
        JsonSupport.mapToJsonString(linkedMapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list")),
      ) { error("tools/list must not be forwarded") },
    )

    val result = JsonSupport.anyToStringAnyMap(
      JsonSupport.parseObjectOrNull(reply)?.get("result")?.let(JsonSupport::jsonElementToValue),
    ).orEmpty()

    @Suppress("UNCHECKED_CAST")
    val tools = result["tools"] as List<Map<String, Any?>>
    assertEquals(listOf("read_evidence", "request_expansion"), tools.map { it["name"] })
  }

  @Test
  fun `a call to any other tool is refused instead of forwarded`() {
    var forwarded = false
    val reply = requireNotNull(
      GovernedReviewEvidenceBridge.handleLine(
        JsonSupport.mapToJsonString(
          linkedMapOf(
            "jsonrpc" to "2.0",
            "id" to 2,
            "method" to "tools/call",
            "params" to mapOf("name" to "Read", "arguments" to mapOf("path" to "/etc/passwd")),
          ),
        ),
      ) { forwarded = true; "{}" },
    )

    assertTrue(!forwarded)
    assertTrue(reply.contains("\"error\""))
  }
}
