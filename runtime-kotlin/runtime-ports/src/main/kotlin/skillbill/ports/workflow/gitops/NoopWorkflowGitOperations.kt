package skillbill.ports.workflow.gitops

object NoopWorkflowGitOperations :
  WorkflowGitBranchOperations by NoopWorkflowGitBranchOperations,
  WorkflowGitRemoteOperations by NoopWorkflowGitRemoteOperations,
  WorkflowGitCommitHistoryOperations by NoopWorkflowGitCommitHistoryOperations,
  WorkflowGitWorktreeOperations by NoopWorkflowGitWorktreeOperations,
  WorkflowGitOperations,
  GoalSubtaskReviewGitOperationsProvider,
  RepositoryFingerprintGitOperationsProvider {
  override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations = NoopGoalSubtaskReviewGitOperations

  override val repositoryFingerprintOperations: RepositoryFingerprintGitOperations =
    NoopRepositoryFingerprintGitOperations
}
