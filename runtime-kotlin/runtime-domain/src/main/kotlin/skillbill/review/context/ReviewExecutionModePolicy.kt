package skillbill.review.context

import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.review.context.model.ReviewAutoEligibility
import skillbill.workflow.model.CodeReviewExecutionMode

object ReviewExecutionModePolicy {
  fun resolve(requested: CodeReviewExecutionMode, eligibility: ReviewAutoEligibility): ResolvedReviewExecutionMode =
    when (requested) {
      CodeReviewExecutionMode.INLINE -> ResolvedReviewExecutionMode.INLINE
      CodeReviewExecutionMode.DELEGATED -> ResolvedReviewExecutionMode.DELEGATED
      CodeReviewExecutionMode.AUTO -> resolveAuto(eligibility)
    }

  private fun resolveAuto(eligibility: ReviewAutoEligibility): ResolvedReviewExecutionMode =
    if (eligibility.oversized || eligibility.highRisk || eligibility.layeredStack) {
      ResolvedReviewExecutionMode.DELEGATED
    } else {
      ResolvedReviewExecutionMode.INLINE
    }
}
