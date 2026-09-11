package skillbill.ports.workflow.gitops

abstract class WorkflowGitOperationsTestBase : WorkflowGitOperations {
  override val checkpointHistoryOperations: CheckpointHistoryGitOperations =
    UnavailableCheckpointHistoryGitOperations

  override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations = NoopGoalSubtaskReviewGitOperations

  override val repositoryFingerprintOperations: RepositoryFingerprintGitOperations =
    UnavailableRepositoryFingerprintGitOperations

  override val repositoryOwnedPathsOperations: RepositoryOwnedPathsGitOperations =
    UnavailableRepositoryOwnedPathsGitOperations

  override val runtimePhaseFileManifestOperations: RuntimePhaseFileManifestGitOperations =
    NoopRuntimePhaseFileManifestGitOperations

  override val scopedStagingOperations: ScopedStagingGitOperations = UnavailableScopedStagingGitOperations
}
