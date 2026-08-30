package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface ScopedStagingGitOperations {
  fun stagePaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult

  fun captureIndexState(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult

  fun restoreIndexState(repoRoot: Path, paths: List<String>, snapshot: String): WorkflowGitOperationResult

  fun stagedPaths(repoRoot: Path): WorkflowGitOperationResult

  fun pathContentIdentities(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult
}

interface ScopedStagingGitOperationsProvider {
  val scopedStagingOperations: ScopedStagingGitOperations
}

private object UnavailableScopedStagingGitOperations : ScopedStagingGitOperations {
  override fun stagePaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
    unavailable("stage an explicit owned-path inventory")

  override fun captureIndexState(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
    unavailable("capture the pre-checkpoint index state")

  override fun restoreIndexState(repoRoot: Path, paths: List<String>, snapshot: String): WorkflowGitOperationResult =
    unavailable("restore the pre-checkpoint index state")

  override fun stagedPaths(repoRoot: Path): WorkflowGitOperationResult = unavailable("list staged paths")

  override fun pathContentIdentities(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
    unavailable("read owned-path content identities")

  private fun unavailable(capability: String) = WorkflowGitOperationResult(
    status = "error",
    error = "This git operations implementation cannot $capability; scoped checkpoints require a git adapter.",
  )
}

private fun WorkflowGitOperations.scopedStagingOperations(): ScopedStagingGitOperations =
  (this as? ScopedStagingGitOperationsProvider)?.scopedStagingOperations ?: UnavailableScopedStagingGitOperations

fun WorkflowGitOperations.stagePaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
  scopedStagingOperations().stagePaths(repoRoot, paths)

fun WorkflowGitOperations.captureIndexState(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
  scopedStagingOperations().captureIndexState(repoRoot, paths)

fun WorkflowGitOperations.restoreIndexState(
  repoRoot: Path,
  paths: List<String>,
  snapshot: String,
): WorkflowGitOperationResult = scopedStagingOperations().restoreIndexState(repoRoot, paths, snapshot)

fun WorkflowGitOperations.stagedPaths(repoRoot: Path): WorkflowGitOperationResult =
  scopedStagingOperations().stagedPaths(repoRoot)

fun WorkflowGitOperations.pathContentIdentities(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
  scopedStagingOperations().pathContentIdentities(repoRoot, paths)
