package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface RuntimePhaseFileManifestGitOperations {
  fun headCommit(repoRoot: Path): WorkflowGitOperationResult

  fun changedPathsBetweenCommits(repoRoot: Path, beforeCommit: String, afterCommit: String): WorkflowGitOperationResult
}

interface RuntimePhaseFileManifestGitOperationsProvider {
  val runtimePhaseFileManifestOperations: RuntimePhaseFileManifestGitOperations
}

fun WorkflowGitOperations.runtimePhaseHeadCommit(repoRoot: Path): WorkflowGitOperationResult =
  runtimePhaseFileManifestOperations().headCommit(repoRoot)

fun WorkflowGitOperations.runtimePhaseChangedPathsBetweenCommits(
  repoRoot: Path,
  beforeCommit: String,
  afterCommit: String,
): WorkflowGitOperationResult = runtimePhaseFileManifestOperations().changedPathsBetweenCommits(
  repoRoot,
  beforeCommit,
  afterCommit,
)

private fun WorkflowGitOperations.runtimePhaseFileManifestOperations(): RuntimePhaseFileManifestGitOperations =
  (this as? RuntimePhaseFileManifestGitOperationsProvider)?.runtimePhaseFileManifestOperations
    ?: NoopRuntimePhaseFileManifestGitOperations

private object NoopRuntimePhaseFileManifestGitOperations : RuntimePhaseFileManifestGitOperations {
  override fun headCommit(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun changedPathsBetweenCommits(
    repoRoot: Path,
    beforeCommit: String,
    afterCommit: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = "")
}
