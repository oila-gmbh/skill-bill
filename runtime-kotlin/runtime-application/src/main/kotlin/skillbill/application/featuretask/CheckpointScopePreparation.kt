package skillbill.application.featuretask

import skillbill.ports.workflow.gitops.stagedPaths

internal data class CheckpointScopePreparation(
  val worktreeDelta: List<String>,
  val stagedPaths: List<String>,
  val phaseWritten: List<String>,
  val writingIntroduced: List<String>,
  val seedOwned: List<String>,
  val deletedPaths: List<String>,
)

internal fun FeatureTaskRuntimeRunLoop.prepareCheckpointScope(
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
): CheckpointScopePreparation? {
  val resolved = recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)
  val worktreeDelta = checkpointWorktreeDelta(resolved?.baselineOwnedPathsForCheckpoint().orEmpty())
    ?: run {
      blockCheckpointScope(precedingPhaseId, branch, "the owned-path inventory could not be read", blockedReason)
      return null
    }
  val staged = phaseGates.gitOperations.stagedPaths(request.repoRoot)
  if (!staged.ok) {
    blockCheckpointScope(precedingPhaseId, branch, staged.error, blockedReason)
    return null
  }
  val stagedPaths = staged.value.orEmpty().split(OWNED_PATH_DELIMITER)
    .map(String::trim)
    .filter(String::isNotBlank)
  val persistedOwned = resolved?.workflowOwnedPaths.orEmpty()
  val evictedFeatureSpecs = persistedOwned
    .filter { path -> isFeatureSpecPathForIssue(path, request.issueKey) }
    .toSet()
  val phaseWritten = phaseWrittenPaths(precedingPhaseId, worktreeDelta, persistedOwned)
    .filterNot { it in evictedFeatureSpecs }
  val writingIntroduced = writingPhaseIntroducedPaths(worktreeDelta)
  val seedOwned = (
    resolved?.workflowOwnedPaths.orEmpty() +
      phaseWritten.takeIf { mayExtendOwnedInventory(precedingPhaseId) }.orEmpty() +
      writingIntroduced
    ).distinct()
  val deletedPaths = absorbableDeletedPaths(
    deleted = checkpointDeletedPaths(),
    ownedOrIntroduced = seedOwned + phaseWritten,
  )
  return CheckpointScopePreparation(
    worktreeDelta = worktreeDelta,
    stagedPaths = stagedPaths,
    phaseWritten = phaseWritten,
    writingIntroduced = writingIntroduced,
    seedOwned = seedOwned,
    deletedPaths = deletedPaths,
  )
}

internal fun FeatureTaskRuntimeRunLoop.checkpointOwnedInventory(
  preparation: CheckpointScopePreparation,
): List<String> = reconcileCheckpointPathInventory(
  repoRoot = request.repoRoot,
  issueKey = request.issueKey,
  specReference = request.runInvariants.specReference,
  paths = (preparation.seedOwned + preparation.deletedPaths)
    .filterNot { path -> isFeatureSpecPathForIssue(path, request.issueKey) },
)
