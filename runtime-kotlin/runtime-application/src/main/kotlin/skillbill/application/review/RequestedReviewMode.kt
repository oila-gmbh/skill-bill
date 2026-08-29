package skillbill.application.review

import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.workflow.goal.model.CodeReviewExecutionMode

/**
 * The entrypoint-facing seam for an operator-supplied review mode. Entrypoints parse the wire value
 * here and the domain policy decides whether the requested depth still exists, so no CLI or MCP
 * surface restates review-depth policy of its own.
 */
object RequestedReviewMode {
  fun parse(value: String): CodeReviewExecutionMode = validate(CodeReviewExecutionMode.fromWire(value))

  fun validate(mode: CodeReviewExecutionMode): CodeReviewExecutionMode = mode.also(ReviewExecutionModePolicy::resolve)
}
