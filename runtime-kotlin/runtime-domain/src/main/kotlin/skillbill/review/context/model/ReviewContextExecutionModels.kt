package skillbill.review.context.model

import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.goal.model.CodeReviewExecutionMode.DELEGATED
import skillbill.workflow.goal.model.CodeReviewExecutionMode.INLINE

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
