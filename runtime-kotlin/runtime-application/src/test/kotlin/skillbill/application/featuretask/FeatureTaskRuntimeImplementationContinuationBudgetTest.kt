package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimeFixLoopDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FeatureTaskRuntimeImplementationContinuationBudgetTest {
  @Test
  fun `a segment retries and advances the segment counter`() {
    val decision = FeatureTaskRuntimeFixLoopPolicy.incompleteWorkContinuationDecision("implement", segmentCount = 1)

    val retry = assertIs<FeatureTaskRuntimeFixLoopDecision.Retry>(decision)
    assertEquals(2, retry.nextIteration)
    assertEquals(1, retry.fixLoopIteration)
  }

  @Test
  fun `continuation keeps retrying past the former segment cap`() {
    // Honest partial work is uncapped: a segment count that used to exhaust the budget must still
    // continue rather than block for an operator.
    val decision = FeatureTaskRuntimeFixLoopPolicy.incompleteWorkContinuationDecision(
      "implement",
      segmentCount = 5,
    )

    assertIs<FeatureTaskRuntimeFixLoopDecision.Retry>(decision)
  }

  @Test
  fun `a resume never re-blocks on continuation segment count alone`() {
    assertNull(FeatureTaskRuntimeFixLoopPolicy.incompleteWorkBlockReasonIfBudgetExhausted("implement", 1))
    assertNull(FeatureTaskRuntimeFixLoopPolicy.incompleteWorkBlockReasonIfBudgetExhausted("implement", 5))
    assertNull(FeatureTaskRuntimeFixLoopPolicy.incompleteWorkBlockReasonIfBudgetExhausted("implement", 8))
  }

  @Test
  fun `continuation stays independent of the semantic fix-loop budget`() {
    // Independence is what keeps an honest partial implementation from consuming the budget reserved
    // for repairing invalid output, and vice versa.
    val atSemanticCap = FeatureTaskRuntimeFixLoopPolicy.incompleteWorkContinuationDecision(
      "implement",
      segmentCount = FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS,
    )

    assertIs<FeatureTaskRuntimeFixLoopDecision.Retry>(
      atSemanticCap,
      "a segment count at the semantic cap must still continue on the continuation axis",
    )
  }
}
