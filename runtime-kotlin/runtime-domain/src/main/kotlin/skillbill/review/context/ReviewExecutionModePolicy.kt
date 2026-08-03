package skillbill.review.context

import skillbill.review.context.model.ResolvedReviewDepth
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.workflow.model.CodeReviewExecutionMode

object ReviewExecutionModePolicy {
  const val PASS_NUMBER_RULE: String = "auto_mode_by_pass_number"
  const val DEFAULT_RULE: String = "auto_mode_default"

  const val FIRST_REVIEW_PASS: Int = 1

  fun resolve(requested: CodeReviewExecutionMode): ResolvedReviewExecutionMode = resolveWithRule(requested).resolvedMode

  /**
   * The two declared auto rules. The pass-number rule is authoritative wherever a review pass number
   * exists (the feature-task review sequence): pass one runs the delegated specialist fan-out, and
   * every follow-up or remediation pass runs the single-prompt inline lane. A standalone review
   * carries no pass number, so auto falls back to the delegated default rule. Both are named so the
   * resolved mode never reports silently.
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
        ?: ResolvedReviewDepth(ResolvedReviewExecutionMode.DELEGATED, "$DEFAULT_RULE:delegated_default")
    }

  private fun resolveAutoByPassNumber(passNumber: Int): ResolvedReviewDepth = if (passNumber == FIRST_REVIEW_PASS) {
    ResolvedReviewDepth(ResolvedReviewExecutionMode.DELEGATED, "$PASS_NUMBER_RULE:pass_1_delegated")
  } else {
    ResolvedReviewDepth(ResolvedReviewExecutionMode.INLINE, "$PASS_NUMBER_RULE:pass_n_inline")
  }
}
