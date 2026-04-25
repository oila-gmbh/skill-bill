package skillbill.review

import skillbill.SAMPLE_REVIEW
import skillbill.TABLE_REVIEW
import skillbill.tempDbConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
      assertEquals("Minor", numberedFindings[1].severity)
      assertNull(summary.reviewFinishedAt)
    }
  }
}
