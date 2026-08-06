package skillbill.review

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SKILL-136 subtask 4 AC-001: canonicalization moved to ingestion, so the parser must hand the
 * agent-authored routed_skill through byte-for-byte while issue categorisation keeps behaving as
 * it did against the normalized value (AC-008 adjacency).
 */
class ReviewRawAttributionPreservationTest {
  private fun review(routedSkillLine: String) = ReviewParser.parseReview(
    """
    Routed to: $routedSkillLine
    Review session ID: rvs-1
    Review run ID: rvw-1
    Detected review scope: branch diff
    Detected stack: kotlin

    ### 2. Risk Register
    - [F-001] Major | High | Auth.kt:12 | Token is logged with sensitive user data.
    """.trimIndent(),
  )

  @Test
  fun `a namespaced routed skill survives parsing without being rewritten`() {
    assertEquals("skillbill:bill-kotlin-code-review", review("skillbill:bill-kotlin-code-review").routedSkill)
  }

  @Test
  fun `a prose suffixed routed skill survives parsing without being truncated`() {
    val raw = "bill-kmp-code-review (persistence specialist)"

    assertEquals(raw, review(raw).routedSkill)
  }

  @Test
  fun `issue categorisation still sees the normalized routed skill`() {
    assertEquals("security_privacy", review("bill-code-review").findings.single().issueCategory)
    assertEquals(
      review("bill-code-review").findings.single().issueCategory,
      review("skillbill:bill-code-review").findings.single().issueCategory,
    )
  }
}
