package skillbill.review.context.model

import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SKILL-159 AC-002: `auto` resolves through named rules and the deciding rule is reportable. Pass
 * one gets the delegated fan-out, later passes get the single-prompt inline lane, and a standalone
 * review with no pass number falls back to the named delegated default rule.
 */
class ReviewDepthAutoRuleTest {
  @Test
  fun `auto on pass one resolves delegated by the pass-number rule`() {
    val resolved = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.AUTO, reviewPassNumber = 1)

    assertEquals(ResolvedReviewExecutionMode.DELEGATED, resolved.resolvedMode)
    assertEquals("${ReviewExecutionModePolicy.PASS_NUMBER_RULE}:pass_1_delegated", resolved.decidingRule)
  }

  @Test
  fun `auto on a follow-up pass resolves inline by the pass-number rule`() {
    val resolved = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.AUTO, reviewPassNumber = 2)

    assertEquals(ResolvedReviewExecutionMode.INLINE, resolved.resolvedMode)
    assertEquals("${ReviewExecutionModePolicy.PASS_NUMBER_RULE}:pass_n_inline", resolved.decidingRule)
  }

  @Test
  fun `auto without a pass number resolves delegated by the named default rule`() {
    val resolved = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.AUTO)

    assertEquals(ResolvedReviewExecutionMode.DELEGATED, resolved.resolvedMode)
    assertEquals("${ReviewExecutionModePolicy.DEFAULT_RULE}:delegated_default", resolved.decidingRule)
  }

  @Test
  fun `an explicit mode overrides both rules and reports itself as the deciding rule`() {
    val inline = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.INLINE, reviewPassNumber = 1)
    assertEquals(ResolvedReviewExecutionMode.INLINE, inline.resolvedMode)
    assertEquals("explicit_inline_override", inline.decidingRule)

    val delegated = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.DELEGATED, reviewPassNumber = 2)
    assertEquals(ResolvedReviewExecutionMode.DELEGATED, delegated.resolvedMode)
    assertEquals("explicit_delegated_override", delegated.decidingRule)
  }

  @Test
  fun `the default requested mode is the delegated fan-out`() {
    assertEquals(CodeReviewExecutionMode.DELEGATED, CodeReviewExecutionMode.DEFAULT)
  }
}
