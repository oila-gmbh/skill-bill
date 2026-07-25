package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.model.CodeReviewExecutionMode

object FeatureTaskRuntimeReviewPassSequence {
  fun passes(pinnedMode: CodeReviewExecutionMode): List<CodeReviewExecutionMode> =
    (1..GOAL_SUBTASK_REVIEW_MAX_PASSES).map { passNumber -> resolveForPass(pinnedMode, passNumber).resolvedTier }

  fun modeForPass(pinnedMode: CodeReviewExecutionMode, passNumber: Int): CodeReviewExecutionMode =
    resolveForPass(pinnedMode, passNumber).resolvedTier

  fun resolveForPass(pinnedMode: CodeReviewExecutionMode, passNumber: Int): ReviewPassResolution {
    if (passNumber !in 1..GOAL_SUBTASK_REVIEW_MAX_PASSES) {
      throw InvalidGoalSubtaskReviewStateSchemaError(
        sourceLabel = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
        fieldPath = "review_pass_number",
        reason = "must be between 1 and $GOAL_SUBTASK_REVIEW_MAX_PASSES.",
      )
    }
    return when (pinnedMode) {
      CodeReviewExecutionMode.AUTO -> AutoDepthRule.resolve(passNumber)
      CodeReviewExecutionMode.INLINE -> ReviewPassResolution(
        resolvedTier = CodeReviewExecutionMode.INLINE,
        decidingRule = "explicit_inline_override",
      )
      CodeReviewExecutionMode.DELEGATED -> ReviewPassResolution(
        resolvedTier = CodeReviewExecutionMode.DELEGATED,
        decidingRule = "explicit_delegated_override",
      )
    }
  }
}

data class ReviewPassResolution(
  val resolvedTier: CodeReviewExecutionMode,
  val decidingRule: String,
)

private object AutoDepthRule {
  private const val RULE_NAME = "auto_depth_by_pass_number"

  fun resolve(passNumber: Int): ReviewPassResolution = when (passNumber) {
    1 -> ReviewPassResolution(
      resolvedTier = CodeReviewExecutionMode.DELEGATED,
      decidingRule = "$RULE_NAME:pass_one_delegated",
    )
    else -> ReviewPassResolution(
      resolvedTier = CodeReviewExecutionMode.INLINE,
      decidingRule = "$RULE_NAME:later_pass_inline",
    )
  }
}
