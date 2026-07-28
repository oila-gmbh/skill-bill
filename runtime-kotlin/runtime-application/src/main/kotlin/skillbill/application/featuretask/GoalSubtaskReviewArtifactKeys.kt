package skillbill.application.featuretask

internal const val GOAL_SUBTASK_REVIEW_RESULT_HISTORY_ARTIFACT_KEY =
  "goal_subtask_review_result_history"

internal fun goalSubtaskReviewGenerationId(
  workflowId: String,
  reviewBase: String,
  reviewedDeltaDigest: String,
  passNumber: Int,
  repositoryCheckpoint: String,
): String = "review-" + sha256HexUtf8(
  listOf(workflowId, reviewBase, reviewedDeltaDigest, passNumber.toString(), repositoryCheckpoint)
    .joinToString("\u0000"),
)
