@file:Suppress("MaxLineLength")

package skillbill.review.context

import skillbill.workflow.model.CodeReviewExecutionMode

enum class ResolvedReviewExecutionMode { INLINE, DELEGATED }
data class ReviewAutoEligibility(val oversized: Boolean, val highRisk: Boolean, val layeredStack: Boolean)

object ReviewExecutionModePolicy {
  fun resolve(requested: CodeReviewExecutionMode, eligibility: ReviewAutoEligibility): ResolvedReviewExecutionMode = when (requested) {
    CodeReviewExecutionMode.INLINE -> ResolvedReviewExecutionMode.INLINE
    CodeReviewExecutionMode.DELEGATED -> ResolvedReviewExecutionMode.DELEGATED
    CodeReviewExecutionMode.AUTO -> if (eligibility.oversized || eligibility.highRisk || eligibility.layeredStack) ResolvedReviewExecutionMode.DELEGATED else ResolvedReviewExecutionMode.INLINE
  }
}
