package skillbill.review

import skillbill.SAMPLE_REVIEW
import skillbill.TABLE_REVIEW
import skillbill.infrastructure.sqlite.review.ReviewRuntime
import skillbill.infrastructure.sqlite.review.reviewSummaryChanged
import skillbill.tempDbConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewRuntimeTest {
  @Test
  fun `parseReview extracts bullet findings and deduplicates specialist reviews`() {
    val review = ReviewParser.parseReview(SAMPLE_REVIEW.trimIndent())

    assertEquals("rvw-20260402-001", review.reviewRunId)
    assertEquals("rvs-20260402-001", review.reviewSessionId)
    assertEquals("bill-kotlin-code-review", review.routedSkill)
    assertEquals("kotlin", review.detectedStack)
    assertEquals(listOf("architecture", "testing"), review.specialistReviews)
    assertEquals(2, review.findings.size)
    assertEquals("F-001", review.findings.first().findingId)
    assertEquals("High", review.findings.first().confidence)
    assertEquals("behavior_correctness", review.findings.first().issueCategory)
  }

  @Test
  fun `saveImportedReview persists table-format findings with normalized severities`() {
    val (_, connection) = tempDbConnection("review-runtime")
    connection.use {
      val review = ReviewParser.parseReview(TABLE_REVIEW.trimIndent())

      ReviewRuntime.saveImportedReview(connection, review, sourcePath = null)

      val summary = ReviewRuntime.fetchReviewSummary(connection, "rvw-20260402-tbl-a")
      val numberedFindings = ReviewRuntime.fetchNumberedFindings(connection, "rvw-20260402-tbl-a")

      assertEquals("rvs-20260402-tbl-a", summary.reviewSessionId)
      assertEquals(2, numberedFindings.size)
      assertEquals("F-001", numberedFindings[0].findingId)
      assertEquals("Major", numberedFindings[0].severity)
      assertEquals("ViewModel.kt:147-152", numberedFindings[0].location)
      assertEquals("behavior_correctness", review.findings[0].issueCategory)
      assertEquals("Minor", numberedFindings[1].severity)
      assertEquals("ux_accessibility", review.findings[1].issueCategory)
      assertNull(summary.reviewFinishedAt)
    }
  }

  // SKILL-136 subtask 4 AC-001/AC-002: canonical attribution round trips through the store, and a
  // re-import that only changes a canonical value is still detected as changed.
  @Test
  fun `canonical attribution round trips and participates in change detection`() {
    val (_, connection) = tempDbConnection("review-canonical")
    connection.use {
      val review = ReviewParser.parseReview(SAMPLE_REVIEW.trimIndent()).withCanonicalAttribution(
        knownPackSkillNames = setOf("bill-kotlin-code-review"),
        knownPlatformSlugs = canonicalPlatformSlugs,
      )

      ReviewRuntime.saveImportedReview(connection, review, sourcePath = null)
      val summary = ReviewRuntime.fetchReviewSummary(connection, review.reviewRunId)

      assertEquals("bill-kotlin-code-review", summary.routedSkillCanonical)
      assertEquals("kotlin", summary.detectedStackCanonical)
      assertEquals(review.routedSkill, summary.routedSkill)

      val findings = ReviewRuntime.fetchImportedFindings(connection, review.reviewRunId)
      assertFalse(reviewSummaryChanged(summary, review, findings))
      assertTrue(reviewSummaryChanged(summary, review.copy(routedSkillCanonical = "unresolved"), findings))
      assertTrue(reviewSummaryChanged(summary, review.copy(detectedScopeDetail = "main..HEAD"), findings))
    }
  }

  @Test
  fun `parseReview assigns fallback classifier category when routing is generic`() {
    val review = ReviewParser.parseReview(
      """
      Routed to: bill-code-review
      Review session ID: rvs-20260402-security
      Review run ID: rvw-20260402-security
      Detected review scope: branch diff
      Detected stack: unknown
      Execution mode: inline

      ### 2. Risk Register
      - [F-001] Major | High | Auth.kt:12 | Token is logged with sensitive user data.
      """.trimIndent(),
    )

    assertEquals("security_privacy", review.findings.single().issueCategory)
  }
}
