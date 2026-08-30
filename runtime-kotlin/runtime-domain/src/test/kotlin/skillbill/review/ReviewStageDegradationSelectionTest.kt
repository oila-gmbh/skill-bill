package skillbill.review

import skillbill.review.model.ReviewEvidenceBoundaryAccounting
import skillbill.review.model.ReviewStageDegradationMeasurement
import skillbill.review.model.ReviewStageDegradationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewStageDegradationSelectionTest {
  @Test
  fun `governed launch with locators and zero authorized reads emits one unexercised record`() {
    val records = evidenceReasons(
      ReviewEvidenceBoundaryAccounting(governedLaunchCount = 1, authorizedReadCount = 0),
    )

    val unexercised = records.single()
    assertEquals(ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNEXERCISED, unexercised.reason)
    assertEquals(ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM, unexercised.seam)
    assertCountsOnly(unexercised)
  }

  @Test
  fun `governed launch with authorized reads and an admitted register emits none of the evidence reasons`() {
    val parsed = ParallelReviewFindingParser.parse(
      "- [F-001] Major | High | path=\"src/Main.kt\" | line=1 | admitted finding",
    )
    val rejected = parsed.rejections.size

    val records = evidenceReasons(
      ReviewEvidenceBoundaryAccounting(
        governedLaunchCount = 1,
        authorizedReadCount = 1,
        evidenceBytes = 12,
        rejectedCandidateCount = rejected,
      ),
    )

    assertTrue(records.isEmpty())
  }

  @Test
  fun `near miss register yields one rejected-candidate record carrying the count`() {
    val parsed = ParallelReviewFindingParser.parse("- [F-1] Major | High | short id")
    val rejected = parsed.rejections.size
    assertTrue(rejected > 0)

    val records = evidenceReasons(
      ReviewEvidenceBoundaryAccounting(
        governedLaunchCount = 1,
        authorizedReadCount = 1,
        rejectedCandidateCount = rejected,
      ),
    )

    val measurement = records.single()
    assertEquals(ReviewStageDegradationReason.REGISTER_CANDIDATES_REJECTED, measurement.reason)
    assertEquals("rejected_candidates=$rejected", measurement.actual)
    assertCountsOnly(measurement)
  }

  @Test
  fun `well formed register and prose without a finding token emit no rejected-candidate record`() {
    val wellFormed = ParallelReviewFindingParser.parse(
      "- [F-001] Major | High | path=\"src/Main.kt\" | line=1 | admitted finding",
    )
    val prose = ParallelReviewFindingParser.parse("I reviewed the diff and found nothing worth reporting.")

    listOf(wellFormed, prose).forEach { parsed ->
      val rejected = parsed.rejections.size
      assertEquals(0, rejected)
      val records = evidenceReasons(
        ReviewEvidenceBoundaryAccounting(
          governedLaunchCount = 1,
          authorizedReadCount = 1,
          rejectedCandidateCount = rejected,
        ),
      )
      assertTrue(records.none { it.reason == ReviewStageDegradationReason.REGISTER_CANDIDATES_REJECTED })
    }
  }

  @Test
  fun `exercised lane accounting does not hide another lane unexercised record`() {
    val records = evidenceReasons(
      ReviewEvidenceBoundaryAccounting(
        governedLaunchCount = 1,
        authorizedReadCount = 1,
        evidenceBytes = 12,
      ),
      ReviewEvidenceBoundaryAccounting(governedLaunchCount = 1, authorizedReadCount = 0),
    )

    val unexercised = records.single()
    assertEquals(ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNEXERCISED, unexercised.reason)
  }

  @Test
  fun `evidence degradation payloads carry seam identity reasons and counts only`() {
    val records = evidenceReasons(
      ReviewEvidenceBoundaryAccounting(
        governedLaunchCount = 1,
        authorizedReadCount = 0,
        rejectedCandidateCount = 2,
        unboundSeam = ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM,
      ),
    )

    assertTrue(records.isNotEmpty())
    records.forEach(::assertCountsOnly)
  }

  @Test
  fun `a refused governed operation is recorded with its count and no repository content`() {
    val records = evidenceReasons(
      ReviewEvidenceBoundaryAccounting(
        governedLaunchCount = 1,
        authorizedReadCount = 1,
        evidenceBytes = 12,
        refusedOperationCount = 2,
      ),
    )

    val refused = records.single()
    assertEquals(ReviewStageDegradationReason.EVIDENCE_BOUNDARY_OPERATION_REFUSED, refused.reason)
    assertEquals("refused_operations=2", refused.actual)
    assertCountsOnly(refused)
  }

  private fun evidenceReasons(
    vararg accounting: ReviewEvidenceBoundaryAccounting,
  ): List<ReviewStageDegradationMeasurement> = ReviewStageDegradationSelection.select(
    ReviewStageDegradationSelectionRequest(
      reviewRunId = "rvw-195",
      spec = null,
      boundaries = emptyList(),
      verdicts = emptyList(),
      claims = null,
      evidenceBoundaries = accounting.toList(),
    ),
  ).filter { it.reason in EVIDENCE_REASONS }

  private fun assertCountsOnly(measurement: ReviewStageDegradationMeasurement) {
    listOf(measurement.seam, measurement.expected, measurement.actual).forEach { field ->
      assertTrue(field.isNotBlank())
      assertFalse("+++" in field)
      assertFalse("---" in field)
      assertFalse("diff --git" in field)
      assertFalse("stdout" in field)
      assertFalse("[F-" in field)
    }
  }

  private companion object {
    val EVIDENCE_REASONS = setOf(
      ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNBOUND_BROKER,
      ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNEXERCISED,
      ReviewStageDegradationReason.REGISTER_CANDIDATES_REJECTED,
      ReviewStageDegradationReason.EVIDENCE_BOUNDARY_OPERATION_REFUSED,
    )
  }
}
