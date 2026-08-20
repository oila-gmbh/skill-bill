package skillbill.review.model

import skillbill.review.context.model.ReviewLaneReviewDisposition
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewCoverageReportTest {
  @Test fun `complete disposition with empty unreviewed units renders clean coverage`() {
    val report = ReviewCoverageReport(
      cleanLanes = listOf("security"),
      incompleteLanes = emptyList(),
      integrationCompleted = false,
    )

    val rendered = report.render()

    assertTrue(report.isCleanCoverage)
    assertTrue("Coverage: clean" in rendered)
    assertFalse("left unreviewed:" in rendered)
    assertFalse("Coverage: NOT clean" in rendered)
  }

  @Test fun `incomplete disposition renders not clean and names unreviewed units`() {
    val report = ReviewCoverageReport(
      cleanLanes = emptyList(),
      incompleteLanes = listOf(
        ReviewLaneAggregationInput(
          lane = "security",
          commitSequenceDigest = "a".repeat(64),
          disposition = ReviewLaneReviewDisposition.INCOMPLETE,
          unreviewedUnits = listOf("head@src/B.kt"),
        ),
      ),
      integrationCompleted = false,
    )

    val rendered = report.render()

    assertFalse(report.isCleanCoverage)
    assertTrue("Coverage: NOT clean" in rendered)
    assertTrue("security left unreviewed: head@src/B.kt" in rendered)
  }
}
