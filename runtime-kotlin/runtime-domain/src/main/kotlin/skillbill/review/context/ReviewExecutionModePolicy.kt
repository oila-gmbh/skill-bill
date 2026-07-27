package skillbill.review.context

import skillbill.review.context.model.ResolvedReviewDepth
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.workflow.model.CodeReviewExecutionMode

object ReviewExecutionModePolicy {
  const val PASS_NUMBER_RULE: String = "auto_depth_by_pass_number"
  const val DEFAULT_RULE: String = "auto_depth_default"

  fun resolve(requested: CodeReviewExecutionMode): ResolvedReviewExecutionMode = resolveWithRule(requested).resolvedMode

  /**
   * The two declared auto rules. The pass-number rule is authoritative wherever a review pass number
   * exists (the feature-task review sequence); a standalone review carries none, so auto falls back to
   * the default rule. Both are named so the resolved depth never reports silently.
   */
  fun resolveWithRule(requested: CodeReviewExecutionMode, reviewPassNumber: Int? = null): ResolvedReviewDepth =
    when (requested) {
      CodeReviewExecutionMode.INLINE -> ResolvedReviewDepth(
        ResolvedReviewExecutionMode.INLINE,
        "explicit_inline_override",
      )
      CodeReviewExecutionMode.DELEGATED -> ResolvedReviewDepth(
        ResolvedReviewExecutionMode.DELEGATED,
        "explicit_delegated_override",
      )
      CodeReviewExecutionMode.AUTO -> reviewPassNumber?.let(::resolveAutoByPassNumber)
        ?: ResolvedReviewDepth(ResolvedReviewExecutionMode.INLINE, "$DEFAULT_RULE:inline_default")
    }

  private fun resolveAutoByPassNumber(passNumber: Int): ResolvedReviewDepth =
    ResolvedReviewDepth(ResolvedReviewExecutionMode.INLINE, "$PASS_NUMBER_RULE:pass_${passNumber}_inline")
}
