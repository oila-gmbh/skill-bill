package skillbill.application.review

import skillbill.ports.review.model.ReviewLifecycleEventKind
import skillbill.ports.review.model.ReviewProcessOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewLifecycleEvidenceTest {
  @Test fun `fixture records launch without aggregation and missing result`() {
    val fixture = ReviewLifecycleEvidenceFixture()
    fixture.coordinatorPrepared()
    fixture.workerSelected("worker-a", "a".repeat(64))
    fixture.workerLaunched("worker-a", "a".repeat(64))
    assertFalse(fixture.aggregation.isComplete(setOf("a".repeat(64))))
    assertEquals(
      listOf(
        ReviewLifecycleEventKind.COORDINATOR_PREPARED,
        ReviewLifecycleEventKind.WORKER_SELECTED,
        ReviewLifecycleEventKind.WORKER_LAUNCHED,
      ),
      fixture.persistence.events.map { it.eventKind },
    )
  }

  @Test fun `interruption non-zero timeout unavailable and invalid output stay non-success`() {
    listOf(
      ReviewProcessOutcome.INTERRUPTED,
      ReviewProcessOutcome.NON_ZERO_EXIT,
      ReviewProcessOutcome.TIMED_OUT,
      ReviewProcessOutcome.UNAVAILABLE,
      ReviewProcessOutcome.INVALID_OUTPUT,
    ).forEachIndexed { index, outcome ->
      val fixture = ReviewLifecycleEvidenceFixture(reviewId = "review-$index")
      fixture.workerSelected("worker", "b".repeat(64))
      fixture.workerFailed("worker", outcome, "b".repeat(64))
      assertFalse(fixture.aggregation.isComplete(setOf("b".repeat(64))))
      assertTrue(fixture.persistence.events.last().processOutcome != ReviewProcessOutcome.ZERO_EXIT)
    }
  }

  @Test fun `normal zero-exit worker can be promoted only after a complete result set`() {
    val fixture = ReviewLifecycleEvidenceFixture()
    fixture.workerSelected("worker-a", "a".repeat(64))
    fixture.workerSelected("worker-b", "b".repeat(64))
    fixture.workerCompleted("worker-a", "a".repeat(64))
    assertFalse(fixture.aggregation.isComplete(setOf("a".repeat(64), "b".repeat(64))))
    fixture.workerCompleted("worker-b", "b".repeat(64))
    assertTrue(fixture.aggregation.isComplete(setOf("a".repeat(64), "b".repeat(64))))
  }
}
