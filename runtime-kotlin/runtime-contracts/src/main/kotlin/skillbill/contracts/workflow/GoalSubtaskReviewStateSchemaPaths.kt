package skillbill.contracts.workflow

const val GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION: String = "0.1"

object GoalSubtaskReviewStateSchemaPaths {
  const val REPO_RELATIVE_PATH: String =
    "orchestration/contracts/goal-subtask-review-state-schema.yaml"

  const val CLASSPATH_RESOURCE: String =
    "skillbill/contracts/goal-subtask-review-state-schema.yaml"

  const val EXPECTED_SCHEMA_ID: String =
    "https://skill-bill.dev/contracts/goal-subtask-review-state-schema.yaml"
}
