package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.ports.workflow.gitops.stagedPaths

@Inject
class FeatureTaskRuntimeRunLoopCheckpoint {
  fun resolveCheckpointScope(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    branch: String,
    blockedReason: (String, String) -> String,
  ): FeatureTaskRuntimeCheckpointDecision? {
    val preparation = prepareCheckpointScope(runLoop, precedingPhaseId, branch, blockedReason) ?: return null
    val ownedInventory = checkpointOwnedInventory(runLoop, preparation)
    val resolved = runLoop.recorder.loadResolvedBranch(runLoop.request.workflowId, runLoop.request.dbPathOverride)
    persistOwnedInventory(runLoop, ownedInventory, resolved?.workflowOwnedPaths.orEmpty())
    runLoop.session.checkpointOwnershipDecided = true
    return FeatureTaskRuntimeCheckpointScope.decide(
      FeatureTaskRuntimeCheckpointScopeInput(
        issueKey = runLoop.request.issueKey,
        ownedPaths = ownedInventory,
        phaseIntroducedPaths = preparation.phaseWritten,
        worktreeDeltaPaths = preparation.worktreeDelta,
        foreignStagedPaths = preparation.stagedPaths,
        concurrentlyModifiedOwnedPaths = runLoop.collaborators.checkpointContinued1
          .concurrentlyModifiedOwnedPaths(runLoop, precedingPhaseId, ownedInventory),
        deletedPaths = preparation.deletedPaths,
      ),
    )
  }
  fun checkpointDeletedPaths(runLoop: FeatureTaskRuntimeRunLoop): List<String> {
    val status = runLoop.phaseGates.gitOperations.worktreeStatus(runLoop.request.repoRoot)
    if (!status.ok) return emptyList()
    return FeatureTaskRuntimePhaseSafetyPolicy.deletedPaths(status.value.orEmpty())
  }

  /**
   * Delete sources that belong to this run's package move: they share a parent directory with an
   * already-owned or writing-phase-introduced destination. Unrelated deletes stay foreign.
   */
  fun absorbableDeletedPaths(deleted: List<String>, ownedOrIntroduced: List<String>): List<String> {
    if (deleted.isEmpty() || ownedOrIntroduced.isEmpty()) return emptyList()
    val anchors = ownedOrIntroduced.map { path -> path.substringBeforeLast('/', missingDelimiterValue = path) }
      .filter(String::isNotBlank)
      .distinct()
    return deleted.filter { removed ->
      val parent = removed.substringBeforeLast('/', missingDelimiterValue = removed)
      anchors.any { anchor ->
        parent == anchor ||
          anchor.startsWith("$parent/") ||
          parent.startsWith("$anchor/")
      }
    }
  }

  fun mayExtendOwnedInventory(phaseId: String): Boolean = phaseId in INVENTORY_EXTENDING_PHASES

  /**
   * Every checkpoint seam runs from a reader phase (audit before review, review before the fix edge),
   * so the preceding phase can never widen ownership on its own. The paths a writing phase introduced
   * and left dirty would then be excluded from both the checkpoint commit and the pathspec-limited
   * review input: work that is neither committed, blocked, nor reviewed. The durable per-phase
   * manifests of the writing phases carry that attribution, so the inventory grows from those and
   * from nothing else.
   */
  fun writingPhaseIntroducedPaths(runLoop: FeatureTaskRuntimeRunLoop, worktreeDelta: List<String>): List<String> {
    val records = runLoop.recorder.loadPhaseRecords(
      runLoop.request.workflowId,
      runLoop.request.dbPathOverride,
    ).orEmpty()
    val writingRecords = INVENTORY_EXTENDING_PHASES.mapNotNull { records[it] }
    if (writingRecords.isEmpty()) {
      if (worktreeDelta.isNotEmpty()) {
        runCatching {
          runLoop.diagnostics.warning(
            "Feature-task-runtime checkpoint has no durable file manifest for any writing phase; " +
              "the whole working-tree delta is treated as this workflow's own writes.",
          )
        }
      }
      return worktreeDelta
    }
    // A writing phase owns both what it created and what it left dirty when it finished. A path that
    // only appears later, while a reader phase is running, was written by somebody else and stays out.
    val introduced = writingRecords.flatMap { it.fileManifestIntroduced + it.fileManifestAfter }.distinct()
    return phaseWrittenPaths(worktreeDelta, introduced)
  }

  /**
   * The subset of the working-tree delta the phase itself wrote, taken from its own durable
   * before/after file manifest. Without a manifest the run cannot tell its own writes from anyone
   * else's, so it degrades to the whole delta and records that it did: silently narrowing instead
   * would drop the phase's real work out of the checkpoint.
   */
  fun phaseWrittenPaths(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    worktreeDelta: List<String>,
    persistedInventory: List<String>,
  ): List<String> {
    val record = runLoop.recorder.loadPhaseRecords(
      runLoop.request.workflowId,
      runLoop.request.dbPathOverride,
    )?.get(phaseId)
    if (record == null) {
      if (worktreeDelta.isNotEmpty()) {
        runCatching {
          runLoop.diagnostics.warning(
            "Feature-task-runtime checkpoint for phase '$phaseId' has no durable file manifest; " +
              "the whole working-tree delta is treated as the phase's own writes.",
          )
        }
      }
      return worktreeDelta
    }
    // What the phase itself introduced, plus the already-owned paths it left dirty. A path that was
    // dirty before the phase ran and that this workflow does not own belongs to someone else, and
    // is the case where the old since-baseline listing silently adopted a stranger's file.
    val owned = persistedInventory.toSet()
    val ownedStillDirty = record.fileManifestAfter.filter { it in owned }
    val manifest = (record.fileManifestIntroduced + ownedStillDirty).distinct()
    return phaseWrittenPaths(worktreeDelta, manifest)
  }

  fun persistOwnedInventory(runLoop: FeatureTaskRuntimeRunLoop, inventory: List<String>, persisted: List<String>) {
    if (inventory.sorted() == persisted.sorted()) return
    runLoop.recorder.recordWorkflowOwnedPaths(runLoop.request.workflowId, inventory, runLoop.request.dbPathOverride)
  }

  /**
   * Owned paths whose content is no longer what the phase left there. The phase's own writes are
   * captured the moment it finishes, so a difference measured here is by definition somebody else's
   * edit landing while the run was between phases — the unstaged half of the overlap ambiguity.
   *
   * An absent capture (a resumed process, a phase that never launched here) yields no comparison
   * rather than a false accusation; the staged-overlap check still applies.
   */

  private fun stagedCheckpointPaths(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    branch: String,
    blockedReason: (String, String) -> String,
  ): List<String>? {
    val staged = runLoop.phaseGates.gitOperations.stagedPaths(runLoop.request.repoRoot)
    if (!staged.ok) {
      runLoop.collaborators.checkpointContinued1.blockCheckpointScope(
        runLoop,
        precedingPhaseId,
        branch,
        staged.error,
        blockedReason,
      )
      return null
    }
    return staged.value.orEmpty().split(OWNED_PATH_DELIMITER)
      .map(String::trim)
      .filter(String::isNotBlank)
  }

  internal fun prepareCheckpointScope(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    branch: String,
    blockedReason: (String, String) -> String,
  ): CheckpointScopePreparation? {
    val resolved = runLoop.recorder.loadResolvedBranch(runLoop.request.workflowId, runLoop.request.dbPathOverride)
    val worktreeDelta = runLoop.collaborators.checkpointContinued1.checkpointWorktreeDelta(
      runLoop,
      resolved?.baselineOwnedPathsForCheckpoint().orEmpty(),
    )
      ?: run {
        runLoop.collaborators.checkpointContinued1.blockCheckpointScope(
          runLoop,
          precedingPhaseId,
          branch,
          "the owned-path inventory could not be read",
          blockedReason,
        )
        return null
      }
    val stagedPaths = stagedCheckpointPaths(runLoop, precedingPhaseId, branch, blockedReason) ?: return null
    val persistedOwned = resolved?.workflowOwnedPaths.orEmpty()
    val evictedFeatureSpecs = persistedOwned
      .filter { path -> isFeatureSpecPathForIssue(path, runLoop.request.issueKey) }
      .toSet()
    val phaseWritten = runLoop.collaborators.checkpoint.phaseWrittenPaths(
      runLoop,
      precedingPhaseId,
      worktreeDelta,
      persistedOwned,
    )
      .filterNot { it in evictedFeatureSpecs }
    val writingIntroduced = runLoop.collaborators.checkpoint.writingPhaseIntroducedPaths(runLoop, worktreeDelta)
    val seedOwned = (
      resolved?.workflowOwnedPaths.orEmpty() +
        phaseWritten.takeIf { runLoop.collaborators.checkpoint.mayExtendOwnedInventory(precedingPhaseId) }.orEmpty() +
        writingIntroduced
      ).distinct()
    val deletedPaths = absorbableDeletedPaths(
      deleted = runLoop.collaborators.checkpoint.checkpointDeletedPaths(runLoop),
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
  internal fun checkpointOwnedInventory(
    runLoop: FeatureTaskRuntimeRunLoop,
    preparation: CheckpointScopePreparation,
  ): List<String> = reconcileCheckpointPathInventory(
    repoRoot = runLoop.request.repoRoot,
    issueKey = runLoop.request.issueKey,
    specReference = runLoop.request.runInvariants.specReference,
    paths = (preparation.seedOwned + preparation.deletedPaths)
      .filterNot { path -> isFeatureSpecPathForIssue(path, runLoop.request.issueKey) },
  )
}
