package skillbill.ports.review

import skillbill.contracts.JsonSupport
import skillbill.error.GovernedReviewEvidenceTransportError
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.ports.review.model.GovernedReviewEvidenceCodec
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.readReviewEvidenceFrame
import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ReviewEvidenceLimits
import skillbill.review.context.model.ReviewExpansionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GovernedReviewEvidenceCodecTest {
  @Test
  fun `wire and direct expansion requests share field limits and frame readers reject oversized input`() {
    val reason = "x".repeat(ReviewEvidenceLimits.FIELD_CHARACTERS + 1)
    assertFailsWith<InvalidReviewContextSchemaError> {
      GovernedReviewEvidenceCodec.expansionRequest("lane", mapOf("path" to "A.kt", "reachability_reason" to reason))
    }
    assertFailsWith<InvalidReviewContextSchemaError> { ReviewExpansionAuthorizationRequest("lane", "A.kt", reason) }
    assertFailsWith<InvalidReviewContextSchemaError> {
      ReviewExpansionRecord(
        "exp-1",
        "a".repeat(64),
        "A.kt",
        reason,
        true,
        0,
      )
    }
    val expansion = GovernedReviewEvidenceCodec.TOOL_SPECS.single { it["name"] == "request_expansion" }
    val schema = requireNotNull(JsonSupport.anyToStringAnyMap(expansion["inputSchema"]))
    val properties = requireNotNull(JsonSupport.anyToStringAnyMap(schema["properties"]))
    assertEquals(
      ReviewEvidenceLimits.FIELD_CHARACTERS,
      requireNotNull(JsonSupport.anyToStringAnyMap(properties["reachability_reason"]))["maxLength"],
    )
    assertFailsWith<GovernedReviewEvidenceTransportError> {
      ("x".repeat(ReviewEvidenceLimits.REQUEST_BYTES + 1) + "\n").reader().buffered().readReviewEvidenceFrame()
    }
    assertEquals("ok", "ok\n".reader().buffered().readReviewEvidenceFrame())
  }

  @Test
  fun `discovery rejects mixed variants and oversized or mistyped pages`() {
    val invalid = listOf(
      mapOf("operation" to "discover", "requests" to emptyList<Any>()),
      mapOf("operation" to "discover", "page_size" to 33),
      mapOf("operation" to "discover", "page_size" to 1.5),
      mapOf("operation" to "discover", "cursor" to 10),
    )
    invalid.forEach { arguments ->
      assertFailsWith<InvalidReviewContextSchemaError> { GovernedReviewEvidenceCodec.discoveryRequest(arguments) }
    }
    assertEquals(
      1,
      GovernedReviewEvidenceCodec.discoveryRequest(
        mapOf(
          "operation" to "discover",
          "page_size" to 1,
        ),
      ).pageSize,
    )
  }

  @Test
  fun `a refused read serialises a reason and no content field`() {
    val payload = GovernedReviewEvidenceCodec.payload(
      ReviewEvidenceBatchResult(
        results = listOf(
          ReviewEvidenceResult(
            content = "package secrets",
            bytes = 15,
            cumulativeBytes = 15,
            expansionCount = 0,
            forbidden = ForbiddenReviewOperation(
              category = "unreachable_path",
              target = "src/Other.kt",
              reason = "outside the assignment surface",
            ),
          ),
        ),
        cumulativeBytes = 0,
        expansions = emptyList(),
      ),
    )
    val result = requireNotNull(JsonSupport.anyToStringAnyMapList((payload["results"]))).single()
    assertFalse(result.containsKey("content"))
    assertEquals(true, result["refused"])
    assertEquals("outside the assignment surface", result["reason"])
  }

  @Test
  fun `a read naming an issued expansion inherits that expansion's reachability reason`() {
    val record = ReviewExpansionRecord(
      expansionId = "exp-1",
      assignmentDigest = "a".repeat(64),
      requestedPath = "src/Other.kt",
      reachabilityReason = "called by the assigned hunk",
      authorized = true,
      sequence = 1,
    )

    val request = GovernedReviewEvidenceCodec.readRequest(
      lane = "lane-a",
      arguments = mapOf("requests" to listOf(mapOf("path" to "src/Other.kt", "expansion_id" to "exp-1"))),
      expansionById = { id -> record.takeIf { id == it.expansionId } },
    )

    assertEquals("called by the assigned hunk", request.requests.single().reachabilityReason)
  }

  @Test
  fun `the governed surface is exactly two operations`() {
    assertEquals(listOf("read_evidence", "request_expansion"), GovernedReviewEvidenceCodec.OPERATIONS)
    assertTrue(GovernedReviewEvidenceCodec.TOOL_SPECS.map { it["name"] } == GovernedReviewEvidenceCodec.OPERATIONS)
  }
}
