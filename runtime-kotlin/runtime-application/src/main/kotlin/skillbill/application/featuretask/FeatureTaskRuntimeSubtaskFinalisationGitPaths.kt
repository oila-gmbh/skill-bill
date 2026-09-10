package skillbill.application.featuretask

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
  val excluded = dirtyPaths.filter(::isRuntimePrivatePath).distinct().sorted()
  val stageable = dirtyPaths.filterNot(::isRuntimePrivatePath).distinct().sorted()
  return StageablePathsOutcome(stageable = stageable, excluded = excluded)
}

internal fun isGovernedSpecPath(path: String): Boolean = normalizeRepoPath(path).let {
  it == GOVERNED_SPEC_ROOT.removeSuffix("/") || it.startsWith(GOVERNED_SPEC_ROOT)
}

internal data class DeclaredBoundaryHistoryProjection(
  val paths: List<String>,
  val roots: List<String>,
)

internal fun finalisationOwnedPaths(
  resolved: FeatureTaskRuntimeResolvedBranch?,
  records: Map<String, FeatureTaskRuntimePhaseRecord>?,
): List<String> {
  val writingPhases = listOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
  )
  val fromWriting = writingPhases.flatMap { phaseId ->
    val record = records?.get(phaseId) ?: return@flatMap emptyList()
    record.fileManifestIntroduced + record.fileManifestAfter
  }
  return (resolved?.workflowOwnedPaths.orEmpty() + fromWriting)
    .map(::normalizeRepoPath)
    .filter { it.isNotBlank() }
    .filterNot(::isGovernedSpecPath)
    .filterNot(::isRuntimePrivatePath)
    .distinct()
    .sorted()
}

internal fun isOwnedOrBoundaryHistoryPath(
  path: String,
  ownedPaths: Collection<String>,
  boundaryHistory: DeclaredBoundaryHistoryProjection,
): Boolean {
  val normalized = normalizeRepoPath(path)
  return normalized in ownedPaths.map(::normalizeRepoPath).toSet() ||
    isBoundaryHistoryPath(normalized, boundaryHistory.paths, boundaryHistory.roots)
}

internal fun isExemptFinalisationDirtyPath(
  path: String,
  ownedPaths: Collection<String>,
  boundaryHistory: DeclaredBoundaryHistoryProjection,
): Boolean {
  val normalized = normalizeRepoPath(path)
  return isGovernedSpecPath(normalized) ||
    isRuntimePrivatePath(normalized) ||
    isOwnedOrBoundaryHistoryPath(normalized, ownedPaths, boundaryHistory)
}
