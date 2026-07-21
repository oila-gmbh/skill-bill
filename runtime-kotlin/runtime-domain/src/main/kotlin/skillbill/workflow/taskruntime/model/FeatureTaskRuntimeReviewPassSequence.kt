package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.model.CodeReviewExecutionMode

object FeatureTaskRuntimeReviewPassSequence {
  fun passes(pinnedMode: CodeReviewExecutionMode): List<CodeReviewExecutionMode> =
    (1..GOAL_SUBTASK_REVIEW_MAX_PASSES).map { passNumber -> modeForPass(pinnedMode, passNumber) }

  fun modeForPass(pinnedMode: CodeReviewExecutionMode, passNumber: Int): CodeReviewExecutionMode {
    if (passNumber !in 1..GOAL_SUBTASK_REVIEW_MAX_PASSES) {
      throw InvalidGoalSubtaskReviewStateSchemaError(
        sourceLabel = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
        fieldPath = "review_pass_number",
        reason = "must be between 1 and $GOAL_SUBTASK_REVIEW_MAX_PASSES.",
      )
    }
    return if (passNumber == 1) pinnedMode else CodeReviewExecutionMode.INLINE
  }
}
