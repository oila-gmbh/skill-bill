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

fun WorkflowGitOperations.stagePaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
  scopedStagingOperations.stagePaths(repoRoot, paths)

fun WorkflowGitOperations.captureIndexState(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
  scopedStagingOperations.captureIndexState(repoRoot, paths)

fun WorkflowGitOperations.restoreIndexState(
  repoRoot: Path,
  paths: List<String>,
  snapshot: String,
): WorkflowGitOperationResult = scopedStagingOperations.restoreIndexState(repoRoot, paths, snapshot)

fun WorkflowGitOperations.stagedPaths(repoRoot: Path): WorkflowGitOperationResult =
  scopedStagingOperations.stagedPaths(repoRoot)

fun WorkflowGitOperations.pathContentIdentities(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
  scopedStagingOperations.pathContentIdentities(repoRoot, paths)
