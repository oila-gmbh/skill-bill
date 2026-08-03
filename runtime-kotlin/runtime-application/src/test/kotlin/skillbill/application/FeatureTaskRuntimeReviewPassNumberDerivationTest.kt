package skillbill.application

import skillbill.application.featuretask.resolveReviewPassNumber
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AC-005 and AC-006: the review pass number must advance across remediation re-entry. A latched
 * reservation from a pass that already produced a result must not pin every later pass to one,
 * which would keep the remediation tier at pass one's and leave the Blocker-disposition prompt
 * addendum unreachable.
 */
class FeatureTaskRuntimeReviewPassNumberDerivationTest {
  @Test
  fun `first pass derives one with no reservation and no completed output`() {
    assertEquals(1, resolveReviewPassNumber(reservedPassNumber = null, completedReviewPassCount = 0))
  }

  @Test
  fun `stale reservation equal to the completed count advances to the next pass`() {
    assertEquals(2, resolveReviewPassNumber(reservedPassNumber = 1, completedReviewPassCount = 1))
    assertEquals(3, resolveReviewPassNumber(reservedPassNumber = 1, completedReviewPassCount = 2))
  }

  @Test
  fun `latched reservation below the completed count never pins the pass number`() {
    assertEquals(11, resolveReviewPassNumber(reservedPassNumber = 1, completedReviewPassCount = 10))
  }

  @Test
  fun `a live reservation ahead of completed outputs is reused so resume allocates no new pass`() {
    assertEquals(3, resolveReviewPassNumber(reservedPassNumber = 3, completedReviewPassCount = 2))
    assertEquals(7, resolveReviewPassNumber(reservedPassNumber = 7, completedReviewPassCount = 6))
  }

  @Test
  fun `missing reservation falls back to one past the completed pass count at any depth`() {
    assertEquals(5, resolveReviewPassNumber(reservedPassNumber = null, completedReviewPassCount = 4))
  }
}
