package skillbill.review.context.model

import skillbill.review.context.model.CodeReviewExecutionMode.DELEGATED
import skillbill.review.context.model.CodeReviewExecutionMode.INLINE

enum class ResolvedReviewExecutionMode { INLINE, DELEGATED }

/** A resolved depth is always a concrete tier, so it maps back onto the requested-mode vocabulary. */
fun ResolvedReviewExecutionMode.toCodeReviewExecutionMode(): CodeReviewExecutionMode = when (this) {
  ResolvedReviewExecutionMode.INLINE -> INLINE
  ResolvedReviewExecutionMode.DELEGATED -> DELEGATED
}
data class ResolvedReviewDepth(
  val resolvedMode: ResolvedReviewExecutionMode,
  val decidingRule: String,
)
