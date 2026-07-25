package skillbill.review.context.model

import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AC-005: `auto` resolves through named rules, and the deciding rule is reportable. Pass number, when
 * present, is the only rule applied; the size-and-risk eligibility rule is the standalone fallback.
 */
class ReviewDepthAutoRuleTest {
  private val risky = ReviewAutoEligibility(oversized = true, highRisk = true, layeredStack = true)
  private val calm = ReviewAutoEligibility(oversized = false, highRisk = false, layeredStack = false)

  @Test
  fun `auto on pass one resolves delegated by the pass-number rule even when no signal escalates`() {
    val resolved = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.AUTO, calm, reviewPassNumber = 1)

    assertEquals(ResolvedReviewExecutionMode.DELEGATED, resolved.resolvedMode)
    assertEquals("${ReviewExecutionModePolicy.PASS_NUMBER_RULE}:pass_one_delegated", resolved.decidingRule)
  }

  @Test
  fun `auto on a later pass resolves inline by the pass-number rule even when every signal escalates`() {
    val resolved = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.AUTO, risky, reviewPassNumber = 2)

    assertEquals(ResolvedReviewExecutionMode.INLINE, resolved.resolvedMode)
    assertEquals("${ReviewExecutionModePolicy.PASS_NUMBER_RULE}:later_pass_inline", resolved.decidingRule)
  }

  @Test
  fun `auto without a pass number falls back to the size-and-risk eligibility rule`() {
    val escalated = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.AUTO, risky)
    assertEquals(ResolvedReviewExecutionMode.DELEGATED, escalated.resolvedMode)
    assertEquals(
      "${ReviewExecutionModePolicy.ELIGIBILITY_RULE}:oversized_or_high_risk_or_layered",
      escalated.decidingRule,
    )

    val light = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.AUTO, calm)
    assertEquals(ResolvedReviewExecutionMode.INLINE, light.resolvedMode)
    assertEquals("${ReviewExecutionModePolicy.ELIGIBILITY_RULE}:no_escalating_signal", light.decidingRule)
  }

  @Test
  fun `an explicit tier overrides both rules and reports itself as the deciding rule`() {
    val inline = ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.INLINE, risky, reviewPassNumber = 1)
    assertEquals(ResolvedReviewExecutionMode.INLINE, inline.resolvedMode)
    assertEquals("explicit_inline_override", inline.decidingRule)

    val delegated =
      ReviewExecutionModePolicy.resolveWithRule(CodeReviewExecutionMode.DELEGATED, calm, reviewPassNumber = 2)
    assertEquals(ResolvedReviewExecutionMode.DELEGATED, delegated.resolvedMode)
    assertEquals("explicit_delegated_override", delegated.decidingRule)
  }
}
