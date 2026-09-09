package skillbill.error

class FeatureTaskRuntimeSubtaskCommitReconciliationError(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: String,
  val reason: String,
  cause: Throwable? = null,
) : SkillBillRuntimeException(
  "Feature-task-runtime subtask commit reconciliation refused for " +
    "'$issueKey/$subtaskId' in workflow '$workflowId': $reason",
  cause,
)
