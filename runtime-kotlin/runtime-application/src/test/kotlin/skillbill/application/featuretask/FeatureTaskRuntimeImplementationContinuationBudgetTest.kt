package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimeFixLoopDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeImplementationContinuationBudgetTest {
  @Test
  fun `a segment below the cap retries and advances the segment counter`() {
    val decision = FeatureTaskRuntimeFixLoopPolicy.incompleteWorkContinuationDecision("implement", segmentCount = 1)

    val retry = assertIs<FeatureTaskRuntimeFixLoopDecision.Retry>(decision)
    assertEquals(2, retry.nextIteration)
    assertEquals(1, retry.fixLoopIteration)
  }

  @Test
  fun `the segment at the cap blocks and the reason names the cap`() {
    val decision = FeatureTaskRuntimeFixLoopPolicy.incompleteWorkContinuationDecision(
      "implement",
      segmentCount = FeatureTaskRuntimeFixLoopPolicy.MAX_IMPLEMENTATION_CONTINUATION_SEGMENTS,
    )

    val block = assertIs<FeatureTaskRuntimeFixLoopDecision.Block>(decision)
    assertTrue(
      block.blockedReason.contains(
        FeatureTaskRuntimeFixLoopPolicy.MAX_IMPLEMENTATION_CONTINUATION_SEGMENTS.toString(),
      ),
      block.blockedReason,
    )
  }

  @Test
  fun `a resume at or beyond the cap re-blocks without relaunching`() {
    assertNull(FeatureTaskRuntimeFixLoopPolicy.incompleteWorkBlockReasonIfBudgetExhausted("implement", 1))
    assertNotNull(
      FeatureTaskRuntimeFixLoopPolicy.incompleteWorkBlockReasonIfBudgetExhausted(
        "implement",
        FeatureTaskRuntimeFixLoopPolicy.MAX_IMPLEMENTATION_CONTINUATION_SEGMENTS,
      ),
    )
    assertNotNull(
      FeatureTaskRuntimeFixLoopPolicy.incompleteWorkBlockReasonIfBudgetExhausted(
        "implement",
        FeatureTaskRuntimeFixLoopPolicy.MAX_IMPLEMENTATION_CONTINUATION_SEGMENTS + 3,
      ),
    )
  }

  @Test
  fun `the continuation budget is independent of the semantic fix-loop budget`() {
    // Independence is what keeps an honest partial implementation from consuming the budget reserved
    // for repairing invalid output, and vice versa.
    val atSemanticCap = FeatureTaskRuntimeFixLoopPolicy.incompleteWorkContinuationDecision(
      "implement",
      segmentCount = FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS,
    )

    assertIs<FeatureTaskRuntimeFixLoopDecision.Retry>(
      atSemanticCap,
      "a segment count at the semantic cap must still continue while the continuation budget remains",
    )
    assertTrue(
      FeatureTaskRuntimeFixLoopPolicy.MAX_IMPLEMENTATION_CONTINUATION_SEGMENTS >
        FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS,
    )
  }

  @Test
  fun `the exhaustion reason states the other budgets were not consumed`() {
    val reason = FeatureTaskRuntimeFixLoopPolicy.incompleteWorkBlockedReason("implement", 5)

    assertTrue(reason.contains("not consumed"), reason)
  }
}
