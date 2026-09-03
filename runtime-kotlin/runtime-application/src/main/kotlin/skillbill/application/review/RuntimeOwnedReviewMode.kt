package skillbill.application.review

import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.review.context.model.toCodeReviewExecutionMode

object RuntimeOwnedReviewMode {
  private val allowed: List<CodeReviewExecutionMode> = listOf(
    CodeReviewExecutionMode.AUTO,
    CodeReviewExecutionMode.INLINE,
  )

  fun parse(value: String): CodeReviewExecutionMode = allowed.firstOrNull { it.wireValue == value }
    ?: throw IllegalArgumentException(
      "Unknown code-review execution mode '$value'. Allowed: " +
        "${allowed.joinToString { it.wireValue }}.",
    )

  fun execute(mode: CodeReviewExecutionMode): CodeReviewExecutionMode = if (mode == CodeReviewExecutionMode.DELEGATED) {
    CodeReviewExecutionMode.INLINE
  } else {
    ReviewExecutionModePolicy.resolve(mode).toCodeReviewExecutionMode()
  }
}
