package skillbill.review.context

import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.review.context.model.ResolvedReviewDepth
import skillbill.review.context.model.ResolvedReviewExecutionMode

object ReviewExecutionModePolicy {
  const val PASS_NUMBER_RULE: String = "auto_mode_by_pass_number"
  const val DEFAULT_RULE: String = "auto_mode_default"

  const val FIRST_REVIEW_PASS: Int = 1

  fun resolve(requested: CodeReviewExecutionMode): ResolvedReviewExecutionMode = resolveWithRule(requested).resolvedMode

  /**
   * The two declared auto rules. Both now resolve inline: the delegated fan-out is the experimental
   * tier, reachable only through an explicit `delegated` selection, because its cost scales with
   * lanes multiplied by model turns while inline pays one context floor. The rules stay named and
   * distinct so telemetry keeps reporting which one applied and a future retuning has a seam.
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

  private fun resolveAutoByPassNumber(passNumber: Int): ResolvedReviewDepth = if (passNumber == FIRST_REVIEW_PASS) {
    ResolvedReviewDepth(ResolvedReviewExecutionMode.INLINE, "$PASS_NUMBER_RULE:pass_1_inline")
  } else {
    ResolvedReviewDepth(ResolvedReviewExecutionMode.INLINE, "$PASS_NUMBER_RULE:pass_n_inline")
  }
}
