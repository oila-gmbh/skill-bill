package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import java.nio.file.Path

private const val GOVERNED_SPEC_ROOT = ".feature-specs/"
const val GIT_PORCELAIN_MIN_LENGTH = 4
const val GIT_PORCELAIN_STATUS_PREFIX_LENGTH = 3

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
  val excluded = dirtyPaths.filter { isGovernedSpecPath(it) || isRuntimePrivatePath(it) }.distinct().sorted()
  val stageable = dirtyPaths.filterNot { isGovernedSpecPath(it) || isRuntimePrivatePath(it) }.distinct().sorted()
  return StageablePathsOutcome(stageable = stageable, excluded = excluded)
}

fun emptyStageableReason(excluded: List<String>): String {
  val cause = if (excluded.isEmpty()) {
    "the worktree has no dirty non-ignored paths"
  } else {
    "the only dirty paths are governed `$GOVERNED_SPEC_ROOT` inputs " +
      "(${excluded.joinToString(", ")}), which finalisation never stages"
  }
  return "$cause, so there is nothing to stage. Finalisation would otherwise publish the " +
    "already-committed checkpoint tree with no deliverable content"
}

fun specExclusionRecord(identity: FeatureTaskRuntimeSubtaskCommitIdentity, paths: List<String>) =
  "seam=FeatureTaskRuntimeSubtaskFinalisation.finalise value_used='staged path set without " +
    "${paths.joinToString(", ")}' value_expected=the agent's enumerated path set for " +
    "'${identity.issueKey}/${identity.subtaskId}' cause=governed feature specs are workflow input, " +
    "never subtask deliverable output, so they are dropped from the staged set and left dirty locally"

internal fun isGovernedSpecPath(path: String): Boolean = normalizeRepoPath(path).startsWith(GOVERNED_SPEC_ROOT)

internal fun isBoundaryHistoryPath(
  path: String,
  declaredPaths: Collection<String> = emptyList(),
  declaredRoots: Collection<String> = emptyList(),
): Boolean {
  val normalized = normalizeRepoPath(path)
  val declared = declaredPaths.map(::normalizeRepoPath).toSet()
  if (normalized !in declared) return false
  if (normalized == "agent/history.md" || normalized == "agent/decisions.md") return true
  if (!normalized.endsWith("/agent/history.md") && !normalized.endsWith("/agent/decisions.md")) return false
  val root = normalized.substringBeforeLast("/agent/", missingDelimiterValue = "")
  return root.isNotBlank() && root in declaredRoots.map(::normalizeRepoPath)
}

internal fun configuredBoundaryHistoryRoots(paths: Collection<String>): List<String> = paths
  .map(::normalizeRepoPath)
  .filter { it.endsWith("/agent/history.md") || it.endsWith("/agent/decisions.md") }
  .mapNotNull { it.substringBeforeLast("/agent/", missingDelimiterValue = "").takeIf(String::isNotBlank) }
  .distinct()
  .sorted()

internal data class DeclaredBoundaryHistoryProjection(
  val paths: List<String>,
  val roots: List<String>,
)

internal fun FeatureTaskRuntimeResolvedBranch.boundaryHistoryProjection(): DeclaredBoundaryHistoryProjection =
  DeclaredBoundaryHistoryProjection(boundaryHistoryPaths, boundaryHistoryRoots)

internal fun declaredBoundaryHistoryProjection(
  phaseRecord: FeatureTaskRuntimePhaseRecord?,
  authoritativeRoots: Collection<String> = emptyList(),
): DeclaredBoundaryHistoryProjection {
  if (phaseRecord?.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY) {
    return DeclaredBoundaryHistoryProjection(emptyList(), emptyList())
  }
  val outputs = (phaseRecord.fileManifestIntroduced + phaseRecord.fileManifestAfter)
    .map(::normalizeRepoPath)
    .filter { isBoundaryHistoryOutputPath(it, authoritativeRoots) }
    .distinct()
    .sorted()
  return DeclaredBoundaryHistoryProjection(
    paths = outputs,
    roots = authoritativeRoots.map(::normalizeRepoPath).filter(String::isNotBlank).distinct().sorted(),
  )
}

private fun isBoundaryHistoryOutputPath(path: String, authoritativeRoots: Collection<String>): Boolean =
  isBoundaryHistoryPath(
    path = path,
    declaredPaths = listOf(path),
    declaredRoots = authoritativeRoots,
  )
