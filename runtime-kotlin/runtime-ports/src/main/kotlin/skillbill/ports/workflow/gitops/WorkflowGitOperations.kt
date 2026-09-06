package skillbill.ports.workflow.gitops

interface WorkflowGitOperations :
  WorkflowGitBranchOperations,
  WorkflowGitRemoteOperations,
  WorkflowGitCommitHistoryOperations,
  WorkflowGitWorktreeOperations {
  val checkpointHistoryOperations: CheckpointHistoryGitOperations

  val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations

  val repositoryFingerprintOperations: RepositoryFingerprintGitOperations

  val repositoryOwnedPathsOperations: RepositoryOwnedPathsGitOperations

  val runtimePhaseFileManifestOperations: RuntimePhaseFileManifestGitOperations

  val scopedStagingOperations: ScopedStagingGitOperations
}
