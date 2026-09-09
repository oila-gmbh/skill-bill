package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError
import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.ports.workflow.gitops.stagedPaths

@Inject
class FeatureTaskRuntimeRunLoopCheckpoint {
  fun resolveCheckpointScope(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    branch: String,
    blockedReason: (String, String) -> String,
  ): FeatureTaskRuntimeCheckpointDecision? = resolveCheckpointScopeForRuntime(
    runLoop,
    precedingPhaseId,
    branch,
    blockedReason,
  )
  fun checkpointDeletedPaths(runLoop: FeatureTaskRuntimeRunLoop): List<String> {
    val status = runLoop.phaseGates.gitOperations.worktreeStatus(runLoop.request.repoRoot)
    if (!status.ok) return emptyList()
    return FeatureTaskRuntimePhaseSafetyPolicy.deletedPaths(status.value.orEmpty())
  }

  fun absorbableDeletedPaths(
    deleted: List<String>,
    ownedOrIntroduced: List<String>,
    phaseManifestDeleted: List<String> = emptyList(),
  ): List<String> {
    if (deleted.isEmpty()) return emptyList()
    val proven = (ownedOrIntroduced + phaseManifestDeleted).map(::normalizeRepoPath).toSet()
    return deleted.filter { normalizeRepoPath(it) in proven }
  }

  fun mayExtendOwnedInventory(phaseId: String): Boolean = phaseId in INVENTORY_EXTENDING_PHASES

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
    val introduced = writingRecords.flatMap { it.fileManifestIntroduced + it.fileManifestAfter }.distinct()
    return phaseWrittenPaths(worktreeDelta, introduced)
  }

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
    val owned = persistedInventory.toSet()
    val ownedStillDirty = record.fileManifestAfter.filter { it in owned }
    val manifest = (record.fileManifestIntroduced + ownedStillDirty).distinct()
    return phaseWrittenPaths(worktreeDelta, manifest)
  }

  fun persistOwnedInventory(
    runLoop: FeatureTaskRuntimeRunLoop,
    inventory: List<String>,
    persisted: List<String>,
    stagedPaths: List<String>,
  ): Boolean {
    if (inventory.sorted() == persisted.sorted()) return true
    val snapshot = if (stagedPaths.isEmpty()) {
      ""
    } else {
      val captured = runLoop.phaseGates.gitOperations.captureIndexState(runLoop.request.repoRoot, stagedPaths)
      if (!captured.ok) {
        val error = FeatureTaskRuntimeSubtaskCommitReconciliationError(
          workflowId = runLoop.request.workflowId,
          issueKey = runLoop.request.issueKey,
          subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString() ?: "unknown",
          reason = "the pre-ownership-persistence index could not be captured (${captured.error})",
        )
        runCatching {
          runLoop.diagnostics.warning(
            "record_kind=refusal seam=FeatureTaskRuntimeRunLoopCheckpoint.persistOwnedInventory " +
              "value_used='staged index' value_expected=restorable index snapshot cause=${error.reason}",
            error,
          )
        }
        return false
      }
      captured.value.orEmpty()
    }
    val written = runCatching {
      runLoop.recorder.recordWorkflowOwnedPaths(
        runLoop.request.workflowId,
        inventory,
        runLoop.request.dbPathOverride,
      )
    }.getOrDefault(false)
    if (written) return true
    val restoreFailure = if (stagedPaths.isNotEmpty()) {
      val restored = runLoop.phaseGates.gitOperations.restoreIndexState(
        runLoop.request.repoRoot,
        stagedPaths,
        snapshot,
      )
      restored.error.takeIf { !restored.ok }
    } else {
      null
    }
    val error = FeatureTaskRuntimeSubtaskCommitReconciliationError(
      workflowId = runLoop.request.workflowId,
      issueKey = runLoop.request.issueKey,
      subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString() ?: "unknown",
      reason = "durable workflow-owned path persistence failed; refusing checkpoint staging" +
        restoreFailure?.let { "; captured index restoration failed ($it)" }.orEmpty(),
    )
    runCatching {
      runLoop.diagnostics.warning(
        "record_kind=refusal seam=FeatureTaskRuntimeRunLoopCheckpoint.persistOwnedInventory " +
          "value_used='${inventory.size} paths' value_expected=durable workflow-owned path inventory " +
          "cause=${error.reason}",
        error,
      )
    }
    return false
  }

  internal fun stagedCheckpointPaths(
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
  ): CheckpointScopePreparation? = prepareCheckpointScopeForRuntime(
    runLoop,
    precedingPhaseId,
    branch,
    blockedReason,
  )
  internal fun checkpointOwnedInventory(
    runLoop: FeatureTaskRuntimeRunLoop,
    preparation: CheckpointScopePreparation,
  ): List<String> = reconcileCheckpointPathInventory(
    repoRoot = runLoop.request.repoRoot,
    specReference = runLoop.request.runInvariants.specReference,
    paths = (preparation.seedOwned + preparation.deletedPaths)
      .filterNot { path -> isFeatureSpecPathForIssue(path, runLoop.request.issueKey) }
      .filterNot(::isGovernedSpecPath)
      .filterNot(::isRuntimePrivatePath),
  )
}

private fun FeatureTaskRuntimeRunLoopCheckpoint.resolveCheckpointScopeForRuntime(
  runLoop: FeatureTaskRuntimeRunLoop,
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
): FeatureTaskRuntimeCheckpointDecision? {
  val preparation = prepareCheckpointScope(runLoop, precedingPhaseId, branch, blockedReason) ?: return null
  val ownedInventory = checkpointOwnedInventory(runLoop, preparation)
  val resolved = loadResolvedCheckpointBranch(runLoop, precedingPhaseId, branch, blockedReason) ?: return null
  if (!persistOwnedInventory(runLoop, ownedInventory, resolved.workflowOwnedPaths, preparation.stagedPaths)) {
    runLoop.collaborators.checkpointContinued1.blockCheckpointScope(
      runLoop,
      precedingPhaseId,
      branch,
      "durable subtask ownership could not be persisted; operator decision: repair the workflow store before " +
        "allowing checkpoint staging",
      blockedReason,
    )
    return null
  }
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

private fun loadResolvedCheckpointBranch(
  runLoop: FeatureTaskRuntimeRunLoop,
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
): FeatureTaskRuntimeResolvedBranch? = try {
  runLoop.recorder.loadResolvedBranch(runLoop.request.workflowId, runLoop.request.dbPathOverride)
    ?: blockMissingCheckpointBranch(runLoop, precedingPhaseId, branch, blockedReason)
} catch (error: IllegalStateException) {
  val reconciliationError = FeatureTaskRuntimeSubtaskCommitReconciliationError(
    workflowId = runLoop.request.workflowId,
    issueKey = runLoop.request.issueKey,
    subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString() ?: "unknown",
    reason = "the resolved workflow row could not be read (${error.message.orEmpty()}); refusing checkpoint scope",
    cause = error,
  )
  runCatching {
    runLoop.diagnostics.warning(
      "record_kind=refusal seam=FeatureTaskRuntimeRunLoopCheckpoint.resolveCheckpointScope " +
        "value_used='resolved workflow row' value_expected=durable resolved-branch ownership " +
        "cause=${reconciliationError.reason}",
      reconciliationError,
    )
  }
  runLoop.collaborators.checkpointContinued1.blockCheckpointScope(
    runLoop,
    precedingPhaseId,
    branch,
    reconciliationError.message.orEmpty(),
    blockedReason,
  )
  null
}

private fun blockMissingCheckpointBranch(
  runLoop: FeatureTaskRuntimeRunLoop,
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
): FeatureTaskRuntimeResolvedBranch? {
  val error = FeatureTaskRuntimeSubtaskCommitReconciliationError(
    workflowId = runLoop.request.workflowId,
    issueKey = runLoop.request.issueKey,
    subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString() ?: "unknown",
    reason = "the resolved workflow row is missing; refusing to persist or stage subtask ownership",
  )
  runLoop.collaborators.checkpointContinued1.blockCheckpointScope(
    runLoop,
    precedingPhaseId,
    branch,
    error.message.orEmpty(),
    blockedReason,
  )
  return null
}

private fun prepareCheckpointScopeForRuntime(
  runLoop: FeatureTaskRuntimeRunLoop,
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
): CheckpointScopePreparation? {
  val resolved = loadResolvedCheckpointBranch(runLoop, precedingPhaseId, branch, blockedReason) ?: return null
  val worktreeDelta = runLoop.collaborators.checkpointContinued1.checkpointWorktreeDelta(
    runLoop,
    resolved.baselineOwnedPathsForCheckpoint(),
  ) ?: return blockCheckpointScopePreparation(runLoop, precedingPhaseId, branch, blockedReason)
  val stagedPaths = runLoop.collaborators.checkpoint.stagedCheckpointPaths(
    runLoop, precedingPhaseId, branch, blockedReason,
  ) ?: return null
  return buildCheckpointScopePreparation(runLoop, precedingPhaseId, resolved, worktreeDelta, stagedPaths)
}

private fun blockCheckpointScopePreparation(
  runLoop: FeatureTaskRuntimeRunLoop,
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
): CheckpointScopePreparation? {
  runLoop.collaborators.checkpointContinued1.blockCheckpointScope(
    runLoop,
    precedingPhaseId,
    branch,
    "the owned-path inventory could not be read",
    blockedReason,
  )
  return null
}

private fun buildCheckpointScopePreparation(
  runLoop: FeatureTaskRuntimeRunLoop,
  precedingPhaseId: String,
  resolved: FeatureTaskRuntimeResolvedBranch,
  worktreeDelta: List<String>,
  stagedPaths: List<String>,
): CheckpointScopePreparation {
  val persistedOwned = resolved.workflowOwnedPaths.filterNot(::isGovernedSpecPath).filterNot(::isRuntimePrivatePath)
  val evictedFeatureSpecs = resolved.workflowOwnedPaths.filter(::isGovernedSpecPath).toSet()
  val phaseWritten = runLoop.collaborators.checkpoint.phaseWrittenPaths(
    runLoop,
    precedingPhaseId,
    worktreeDelta,
    persistedOwned,
  ).filterNot { it in evictedFeatureSpecs }
  val writingIntroduced = runLoop.collaborators.checkpoint.writingPhaseIntroducedPaths(runLoop, worktreeDelta)
  val seedOwned = (
    resolved.workflowOwnedPaths + phaseWritten.takeIf {
      runLoop.collaborators.checkpoint.mayExtendOwnedInventory(precedingPhaseId)
    }.orEmpty() + writingIntroduced
    ).distinct()
  val deletedPaths = runLoop.collaborators.checkpoint.absorbableDeletedPaths(
    deleted = runLoop.collaborators.checkpoint.checkpointDeletedPaths(runLoop),
    ownedOrIntroduced = seedOwned + phaseWritten,
    phaseManifestDeleted = runLoop.recorder.loadPhaseRecords(
      runLoop.request.workflowId,
      runLoop.request.dbPathOverride,
    ).orEmpty().values.flatMap { record ->
      record.fileManifestBefore.map(::normalizeRepoPath) - record.fileManifestAfter.map(::normalizeRepoPath).toSet()
    },
  )
  return CheckpointScopePreparation(
    worktreeDelta,
    stagedPaths,
    phaseWritten,
    writingIntroduced,
    seedOwned,
    deletedPaths,
  )
}
