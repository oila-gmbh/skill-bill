package skillbill.application.review

import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.review.model.ReviewEvidenceBoundaryAccounting
import skillbill.review.model.ReviewStageDegradationReason
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

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
    assertEquals("governed evidence broker construction failed", result.lane1.failureReason)
    val unbound = recorder.stageDegradations.filter {
      it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNBOUND_BROKER
    }
    assertEquals(1, unbound.size)
    assertEquals(ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM, unbound.single().seam)
    assertEquals("unbound", unbound.single().actual)
  }

  @Test
  fun `governed launch with locators and zero authorized reads emits one unexercised-boundary record`() {
    val recorder = ReviewRecorder()
    reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForPaths("src/Repo.kt"),
      ),
      recorder,
    ).run(
      harnessRequest(
        agent2Id = null,
        reviewRunId = "rvw-195-unexercised",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ),
    )

    val unexercised = recorder.stageDegradations.filter {
      it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNEXERCISED
    }
    assertEquals(1, unexercised.size)
    assertEquals(ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM, unexercised.single().seam)
    assertEquals("authorized_reads=0", unexercised.single().actual)
    assertEquals(1, recorder.parentLaunches.size)
    assertNotNull(recorder.parentLaunches.single().skillRunRequest.reviewEvidenceBroker)
  }

  @Test
  fun `parse rejection that still admits a finding emits one rejected-candidate record carrying the count`() {
    val recorder = ReviewRecorder()
    val admittedWithRejectedLocation =
      "- [F-001] Major | High | path=\"/tmp/outside.kt\" | line=3 | outside the packet"
    reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForPaths("src/Repo.kt"),
        response = { RecordedWorkerResponse(stdout = admittedWithRejectedLocation) },
      ),
      recorder,
    ).run(
      harnessRequest(
        agent2Id = null,
        reviewRunId = "rvw-195-rejected",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ),
    )

    val rejected = recorder.stageDegradations.filter {
      it.reason == ReviewStageDegradationReason.REGISTER_CANDIDATES_REJECTED
    }
    assertEquals(1, rejected.size)
    assertEquals("rejected_candidates=1", rejected.single().actual)
    assertEquals("review.register.parse", rejected.single().seam)
  }
}
