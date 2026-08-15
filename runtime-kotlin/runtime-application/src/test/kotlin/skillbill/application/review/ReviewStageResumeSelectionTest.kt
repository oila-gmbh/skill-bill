package skillbill.application.review

import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageReached
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewStageResumeSelectionTest {
  @Test
  fun `verification completion does not mark adjudication and adjudication does not backfill verification`() {
    val verificationOnly = ReviewStageResumeSelection.select(
      boundaries = listOf(reached(ReviewStage.VERIFICATION)),
      verdicts = emptyList(),
    )
    assertTrue(verificationOnly.holdsDurableResult(ReviewStage.VERIFICATION))
    assertFalse(verificationOnly.holdsDurableResult(ReviewStage.ADJUDICATION))
    assertEquals(ReviewStage.REVIEW, verificationOnly.reentryStage)

    val adjudicationOnly = ReviewStageResumeSelection.select(
      boundaries = listOf(reached(ReviewStage.ADJUDICATION)),
      verdicts = emptyList(),
    )
    assertFalse(adjudicationOnly.holdsDurableResult(ReviewStage.VERIFICATION))
    assertTrue(adjudicationOnly.holdsDurableResult(ReviewStage.ADJUDICATION))
    assertEquals(ReviewStage.REVIEW, adjudicationOnly.reentryStage)

    val reviewAndAdjudication = ReviewStageResumeSelection.select(
      boundaries = listOf(reached(ReviewStage.REVIEW), reached(ReviewStage.ADJUDICATION)),
      verdicts = emptyList(),
    )
    assertFalse(reviewAndAdjudication.holdsDurableResult(ReviewStage.VERIFICATION))
    assertEquals(ReviewStage.VERIFICATION, reviewAndAdjudication.reentryStage)
  }

  @Test
  fun `a superseded contract version verdict is not durable and records degradation`() {
    val report = ReviewStageResumeSelection.select(
      boundaries = emptyList(),
      verdicts = listOf(
        ReviewFindingVerdict(
          stage = ReviewStage.VERIFICATION,
          findingRef = "F-001",
          claimVerdict = ReviewClaimVerdict.CONFIRMED,
          recordedAt = "2026-08-14T08:00:00Z",
          contractVersion = "0.9",
        ),
      ),
    )
    assertFalse(report.holdsDurableResult(ReviewStage.VERIFICATION))
    assertEquals(ReviewStage.REVIEW, report.reentryStage)
    assertEquals(1, report.degradations.size)
    val degradation = report.degradations.single()
    assertEquals(ReviewStageResumeSelection.SEAM, degradation.seam)
    assertEquals("0.9", degradation.used)
    assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, degradation.expected)
  }

  private fun reached(stage: ReviewStage) = ReviewStageBoundary(
    stage = stage,
    reached = ReviewStageReached.REACHED,
    recordedAt = "2026-08-14T08:00:00Z",
  )
}
