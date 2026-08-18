package skillbill.ports.review

import skillbill.ports.review.model.GovernedReviewEvidenceCodec
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.review.context.model.ForbiddenReviewOperation
import skillbill.review.context.model.ReviewExpansionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GovernedReviewEvidenceCodecTest {
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

    @Suppress("UNCHECKED_CAST")
    val result = (payload["results"] as List<Map<String, Any?>>).single()
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
