package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.review.context.ReviewExecutionModePolicy
import skillbill.review.context.model.toCodeReviewExecutionMode
import skillbill.workflow.model.CodeReviewExecutionMode

object FeatureTaskRuntimeReviewPassSequence {
  const val REMEDIATION_PASS_RULE: String = "reserved_remediation_pass_inline"

  fun passes(pinnedMode: CodeReviewExecutionMode): List<CodeReviewExecutionMode> =
    (1..GOAL_SUBTASK_REVIEW_MAX_PASSES).map { passNumber -> resolveForPass(pinnedMode, passNumber).resolvedTier }

  fun modeForPass(pinnedMode: CodeReviewExecutionMode, passNumber: Int): CodeReviewExecutionMode =
    resolveForPass(pinnedMode, passNumber).resolvedTier

  /**
   * Pass one honours the pinned mode (auto resolving to the delegated fan-out). The reserved
   * remediation pass is bounded to the remediation delta and always runs the single-prompt inline
   * lane, so an explicitly pinned delegated run does not fan specialists out over a fix-up diff.
   */
  fun resolveForPass(pinnedMode: CodeReviewExecutionMode, passNumber: Int): ReviewPassResolution {
    if (passNumber !in 1..GOAL_SUBTASK_REVIEW_MAX_PASSES) {
      throw InvalidGoalSubtaskReviewStateSchemaError(
        sourceLabel = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
        fieldPath = "review_pass_number",
        reason = "must be between 1 and $GOAL_SUBTASK_REVIEW_MAX_PASSES.",
      )
    }
    if (pinnedMode != CodeReviewExecutionMode.AUTO && passNumber != ReviewExecutionModePolicy.FIRST_REVIEW_PASS) {
      return ReviewPassResolution(CodeReviewExecutionMode.INLINE, REMEDIATION_PASS_RULE)
    }
    val resolved = ReviewExecutionModePolicy.resolveWithRule(pinnedMode, passNumber)
    return ReviewPassResolution(
      resolvedTier = resolved.resolvedMode.toCodeReviewExecutionMode(),
      decidingRule = resolved.decidingRule,
    )
  }
}

data class ReviewPassResolution(
  val resolvedTier: CodeReviewExecutionMode,
  val decidingRule: String,
)
