package skillbill.review.context

import skillbill.review.context.model.ResolvedReviewDepth
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.review.context.model.ReviewAutoEligibility
import skillbill.workflow.model.CodeReviewExecutionMode

object ReviewExecutionModePolicy {
  const val PASS_NUMBER_RULE: String = "auto_depth_by_pass_number"
  const val ELIGIBILITY_RULE: String = "auto_depth_by_size_and_risk_eligibility"

  fun resolve(requested: CodeReviewExecutionMode, eligibility: ReviewAutoEligibility): ResolvedReviewExecutionMode =
    resolveWithRule(requested, eligibility).resolvedMode

  /**
   * The two declared auto rules. The pass-number rule is authoritative wherever a review pass number
   * exists (the feature-task review sequence); a standalone review carries none, so auto falls back to
   * the size-and-risk eligibility rule. Both are named so the resolved depth never reports silently.
   */
  fun resolveWithRule(
    requested: CodeReviewExecutionMode,
    eligibility: ReviewAutoEligibility,
    reviewPassNumber: Int? = null,
  ): ResolvedReviewDepth = when (requested) {
    CodeReviewExecutionMode.INLINE -> ResolvedReviewDepth(
      ResolvedReviewExecutionMode.INLINE,
      "explicit_inline_override",
    )
    CodeReviewExecutionMode.DELEGATED -> ResolvedReviewDepth(
      ResolvedReviewExecutionMode.DELEGATED,
      "explicit_delegated_override",
    )
    CodeReviewExecutionMode.AUTO -> reviewPassNumber?.let(::resolveAutoByPassNumber)
      ?: resolveAutoByEligibility(eligibility)
  }

  private fun resolveAutoByPassNumber(passNumber: Int): ResolvedReviewDepth =
    ResolvedReviewDepth(ResolvedReviewExecutionMode.INLINE, "$PASS_NUMBER_RULE:pass_${passNumber}_inline")

  private fun resolveAutoByEligibility(
    @Suppress("UNUSED_PARAMETER") eligibility: ReviewAutoEligibility,
  ): ResolvedReviewDepth = ResolvedReviewDepth(ResolvedReviewExecutionMode.INLINE, "$ELIGIBILITY_RULE:inline_default")
}
