package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.application.workflow.repoRoot
import skillbill.ports.workflow.gitops.pathContentIdentities
import skillbill.ports.workflow.gitops.stagedPaths

internal fun FeatureTaskRuntimeRunLoop.resolveCheckpointScope(
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
): FeatureTaskRuntimeCheckpointDecision? {
  val resolved = recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)
  val worktreeDelta = checkpointWorktreeDelta(resolved?.baselineOwnedPathsForCheckpoint().orEmpty())
    ?: return blockCheckpointScope(
      precedingPhaseId,
      branch,
      "the owned-path inventory could not be read",
      blockedReason,
    )
  val staged = phaseGates.gitOperations.stagedPaths(request.repoRoot)
  if (!staged.ok) {
    return blockCheckpointScope(precedingPhaseId, branch, staged.error, blockedReason)
  }
  val stagedPaths = staged.value.orEmpty().split(OWNED_PATH_DELIMITER)
    .map(String::trim)
    .filter(String::isNotBlank)
  val persistedOwned = resolved?.workflowOwnedPaths.orEmpty()
  // Governed feature specs a previous checkpoint already recorded as owned: the guard must not
  // re-report them as newly introduced, or the run hard-blocks forever on its own leftover state.
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
  val ownedInventory = reconcileCheckpointPathInventory(
    repoRoot = request.repoRoot,
    issueKey = request.issueKey,
    specReference = request.runInvariants.specReference,
    // The persisted inventory is the sole ownership authority. It is extended with the paths the
    // writing phases themselves wrote — never with whatever else happens to be dirty, which is how
    // someone else's concurrent edit used to be adopted and committed as this run's work.
    // Governed feature specs never become owned, so the persisted inventory contains implementation
    // paths only. The runtime never stages them; a human operator may already have committed them.
    // Delete sources that share a move parent with owned/introduced destinations are absorbed so a
    // package move can stage both halves.
    paths = (seedOwned + deletedPaths)
      .filterNot { path -> isFeatureSpecPathForIssue(path, request.issueKey) },
  )
  persistOwnedInventory(ownedInventory, resolved?.workflowOwnedPaths.orEmpty())
  checkpointOwnershipDecided = true
  // Nothing has been staged by this checkpoint yet, so every entry in the index arrived from
  // outside it. The scope decision keeps only the ones this run also owns: those are the genuinely
  // ambiguous overlaps. A purely foreign staged path is left alone, which is what lets a
  // concurrently prepared issue coexist without producing a false block.
  return FeatureTaskRuntimeCheckpointScope.decide(
    FeatureTaskRuntimeCheckpointScopeInput(
      issueKey = request.issueKey,
      ownedPaths = ownedInventory,
      phaseIntroducedPaths = phaseWritten,
      worktreeDeltaPaths = worktreeDelta,
      foreignStagedPaths = stagedPaths,
      concurrentlyModifiedOwnedPaths = concurrentlyModifiedOwnedPaths(precedingPhaseId, ownedInventory),
      deletedPaths = deletedPaths,
    ),
  )
}

internal fun FeatureTaskRuntimeRunLoop.checkpointDeletedPaths(): List<String> {
  val status = phaseGates.gitOperations.worktreeStatus(request.repoRoot)
  if (!status.ok) return emptyList()
  return FeatureTaskRuntimePhaseSafetyPolicy.deletedPaths(status.value.orEmpty())
}

/**
 * Delete sources that belong to this run's package move: they share a parent directory with an
 * already-owned or writing-phase-introduced destination. Unrelated deletes stay foreign.
 */
internal fun FeatureTaskRuntimeRunLoop.absorbableDeletedPaths(
  deleted: List<String>,
  ownedOrIntroduced: List<String>,
): List<String> {
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

internal fun FeatureTaskRuntimeRunLoop.mayExtendOwnedInventory(phaseId: String): Boolean =
  phaseId in INVENTORY_EXTENDING_PHASES

/**
 * Every checkpoint seam runs from a reader phase (audit before review, review before the fix edge),
 * so the preceding phase can never widen ownership on its own. The paths a writing phase introduced
 * and left dirty would then be excluded from both the checkpoint commit and the pathspec-limited
 * review input: work that is neither committed, blocked, nor reviewed. The durable per-phase
 * manifests of the writing phases carry that attribution, so the inventory grows from those and
 * from nothing else.
 */
internal fun FeatureTaskRuntimeRunLoop.writingPhaseIntroducedPaths(worktreeDelta: List<String>): List<String> {
  val records = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride).orEmpty()
  val writingRecords = INVENTORY_EXTENDING_PHASES.mapNotNull { records[it] }
  if (writingRecords.isEmpty()) {
    if (worktreeDelta.isNotEmpty()) {
      runCatching {
        diagnostics.warning(
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
  return FeatureTaskRuntimeCheckpointScope.phaseWrittenPaths(worktreeDelta, introduced)
}

/**
 * The subset of the working-tree delta the phase itself wrote, taken from its own durable
 * before/after file manifest. Without a manifest the run cannot tell its own writes from anyone
 * else's, so it degrades to the whole delta and records that it did: silently narrowing instead
 * would drop the phase's real work out of the checkpoint.
 */
internal fun FeatureTaskRuntimeRunLoop.phaseWrittenPaths(
  phaseId: String,
  worktreeDelta: List<String>,
  persistedInventory: List<String>,
): List<String> {
  val record = recorder.loadPhaseRecords(request.workflowId, request.dbPathOverride)?.get(phaseId)
  if (record == null) {
    if (worktreeDelta.isNotEmpty()) {
      runCatching {
        diagnostics.warning(
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
  return FeatureTaskRuntimeCheckpointScope.phaseWrittenPaths(worktreeDelta, manifest)
}

internal fun FeatureTaskRuntimeRunLoop.persistOwnedInventory(inventory: List<String>, persisted: List<String>) {
  if (inventory.sorted() == persisted.sorted()) return
  recorder.recordWorkflowOwnedPaths(request.workflowId, inventory, request.dbPathOverride)
}

/**
 * Owned paths whose content is no longer what the phase left there. The phase's own writes are
 * captured the moment it finishes, so a difference measured here is by definition somebody else's
 * edit landing while the run was between phases — the unstaged half of the overlap ambiguity.
 *
 * An absent capture (a resumed process, a phase that never launched here) yields no comparison
 * rather than a false accusation; the staged-overlap check still applies.
 */
internal fun FeatureTaskRuntimeRunLoop.concurrentlyModifiedOwnedPaths(
  phaseId: String,
  ownedPaths: List<String>,
): List<String> {
  val captured = phaseContentIdentities[phaseId] ?: return emptyList()
  val current = phaseGates.gitOperations.pathContentIdentities(request.repoRoot, ownedPaths)
  if (!current.ok) return emptyList()
  val now = parseContentIdentities(current.value.orEmpty())
  return captured.filter { (path, identity) -> path in now && now[path] != identity }.keys.sorted()
}

internal fun FeatureTaskRuntimeRunLoop.blockCheckpointScope(
  precedingPhaseId: String,
  branch: String,
  error: String,
  blockedReason: (String, String) -> String,
): FeatureTaskRuntimeCheckpointDecision? {
  blockCheckpoint(precedingPhaseId, branch, error, blockedReason)
  return null
}
