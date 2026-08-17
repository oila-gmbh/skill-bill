package skillbill.application.review

import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.review.model.ReviewEvidenceBoundaryAccounting
import skillbill.review.model.ReviewStageDegradationReason
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ParallelCodeReviewEvidenceBoundaryTest {
  @Test
  fun `broker bind failure emits unbound degradation and a non-success lane`() {
    val recorder = ReviewRecorder()
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForPaths("src/Repo.kt"),
        evidenceBrokerFactory = ReviewEvidenceBrokerFactory { error("broker construction failed") },
      ),
      recorder,
    ).run(
      harnessRequest(
        agent2Id = null,
        reviewRunId = "rvw-195-unbound",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ),
    )

    assertFalse(result.lane1.success)
    val unbound = recorder.stageDegradations.filter {
      it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNBOUND_BROKER
    }
    assertEquals(1, unbound.size)
    assertEquals(ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM, unbound.single().seam)
    assertEquals("unbound", unbound.single().actual)
  }
}
