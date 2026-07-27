package skillbill.review.context.model

import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AC-005: `auto` resolves through named rules, and the deciding rule is reportable. Pass number, when
 * present, is the only rule applied; the default rule is the standalone fallback.
 */
class ReviewDepthAutoRuleTest {
  @Test
  fun `auto on pass one resolves inline by the pass-number rule`() {
    val resolved = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.AUTO, reviewPassNumber = 1)

    assertEquals(ResolvedReviewExecutionMode.INLINE, resolved.resolvedMode)
    assertEquals("${ReviewExecutionModePolicy.PASS_NUMBER_RULE}:pass_1_inline", resolved.decidingRule)
  }

  @Test
  fun `auto on a later pass resolves inline by the pass-number rule`() {
    val resolved = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.AUTO, reviewPassNumber = 2)

    assertEquals(ResolvedReviewExecutionMode.INLINE, resolved.resolvedMode)
    assertEquals("${ReviewExecutionModePolicy.PASS_NUMBER_RULE}:pass_2_inline", resolved.decidingRule)
  }

  @Test
  fun `auto without a pass number resolves inline by the named default rule`() {
    val resolved = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.AUTO)

    assertEquals(ResolvedReviewExecutionMode.INLINE, resolved.resolvedMode)
    assertEquals("${ReviewExecutionModePolicy.DEFAULT_RULE}:inline_default", resolved.decidingRule)
  }

  @Test
  fun `an explicit tier overrides both rules and reports itself as the deciding rule`() {
    val inline = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.INLINE, reviewPassNumber = 1)
    assertEquals(ResolvedReviewExecutionMode.INLINE, inline.resolvedMode)
    assertEquals("explicit_inline_override", inline.decidingRule)

    val delegated = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.DELEGATED, reviewPassNumber = 2)
    assertEquals(ResolvedReviewExecutionMode.DELEGATED, delegated.resolvedMode)
    assertEquals("explicit_delegated_override", delegated.decidingRule)
  }
}
