package skillbill.application.featuretask

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.nio.file.Path

private const val GOVERNED_SPEC_ROOT = ".feature-specs/"
private const val GIT_PORCELAIN_MIN_LENGTH = 4
private const val GIT_PORCELAIN_STATUS_PREFIX_LENGTH = 3

internal sealed interface DirtyPathsResult

internal data class DirtyPaths(val paths: List<String>) : DirtyPathsResult

internal data class DirtyPathsError(val reason: String) : DirtyPathsResult

internal data class StageablePathsOutcome(
  val stageable: List<String>,
  val excluded: List<String>,
)

internal fun WorkflowGitOperations.dirtyImplementationPaths(repoRoot: Path): DirtyPathsResult {
  val status = worktreeStatus(repoRoot)
  if (!status.ok) {
    return DirtyPathsError("the worktree status could not be read before staging (${status.error})")
  }
  val paths = parseGitPorcelainPaths(status.value.orEmpty())
    .map(::normalizeRepoPath)
    .filter { it.isNotBlank() }
    .distinct()
    .sorted()
  return DirtyPaths(paths)
}

internal fun stageablePathsFrom(dirtyPaths: List<String>): StageablePathsOutcome {
  val excluded = dirtyPaths.filter(::isGovernedSpecPath).distinct().sorted()
  val stageable = dirtyPaths.filterNot(::isGovernedSpecPath).distinct().sorted()
  return StageablePathsOutcome(stageable = stageable, excluded = excluded)
}

internal fun emptyStageableReason(excluded: List<String>): String {
  val cause = if (excluded.isEmpty()) {
    "the worktree has no dirty non-ignored paths"
  } else {
    "the only dirty paths are governed `$GOVERNED_SPEC_ROOT` inputs " +
      "(${excluded.joinToString(", ")}), which finalisation never stages"
  }
  return "$cause, so there is nothing to stage. Finalisation would otherwise publish the " +
    "already-committed checkpoint tree with no deliverable content"
}

internal fun specExclusionRecord(identity: FeatureTaskRuntimeSubtaskCommitIdentity, paths: List<String>) =
  "seam=FeatureTaskRuntimeSubtaskFinalisation.finalise value_used='staged path set without " +
    "${paths.joinToString(", ")}' value_expected=the agent's enumerated path set for " +
    "'${identity.issueKey}/${identity.subtaskId}' cause=governed feature specs are workflow input, " +
    "never subtask deliverable output, so they are dropped from the staged set and left dirty locally"

internal fun isGovernedSpecPath(path: String): Boolean = normalizeRepoPath(path).startsWith(GOVERNED_SPEC_ROOT)

internal fun normalizeRepoPath(path: String): String = path.trim().removeSurrounding("\"").removePrefix("./")

private fun parseGitPorcelainPaths(output: String): List<String> = output
  .lineSequence()
  .map(String::trimEnd)
  .filter { line -> line.length >= GIT_PORCELAIN_MIN_LENGTH }
  .map(::pathFromPorcelainLine)
  .filter(String::isNotBlank)
  .toList()

private fun pathFromPorcelainLine(line: String): String {
  val pathPart = when {
    line.length >= 2 &&
      line[0] != ' ' &&
      line[1] == ' ' &&
      (
        line.length < GIT_PORCELAIN_STATUS_PREFIX_LENGTH + 1 ||
          line[GIT_PORCELAIN_STATUS_PREFIX_LENGTH] != ' '
        ) -> line.drop(2)
    else -> line.drop(GIT_PORCELAIN_STATUS_PREFIX_LENGTH)
  }
  return pathPart.substringAfterLast(" -> ").trim().removeSurrounding("\"")
}
