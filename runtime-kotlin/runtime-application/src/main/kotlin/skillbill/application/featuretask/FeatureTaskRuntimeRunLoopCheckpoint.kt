package skillbill.application.featuretask

import skillbill.application.featuretask.model.AppendCheckpointIdentityArgs
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.ports.workflow.gitops.stagedPaths
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

object FeatureTaskRuntimeRunLoopCheckpoint {
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
        concurrentlyModifiedOwnedPaths = FeatureTaskRuntimeRunLoopCheckpointRemediation
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

  fun persistOwnedInventory(runLoop: FeatureTaskRuntimeRunLoop, inventory: List<String>, persisted: List<String>) {
    if (inventory.sorted() == persisted.sorted()) return
    runLoop.recorder.recordWorkflowOwnedPaths(runLoop.request.workflowId, inventory, runLoop.request.dbPathOverride)
  }

  private fun stagedCheckpointPaths(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    branch: String,
    blockedReason: (String, String) -> String,
  ): List<String>? {
    val staged = runLoop.phaseGates.gitOperations.stagedPaths(runLoop.request.repoRoot)
    if (!staged.ok) {
      FeatureTaskRuntimeRunLoopCheckpointRemediation.blockCheckpointScope(
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
    val worktreeDelta = FeatureTaskRuntimeRunLoopCheckpointRemediation.checkpointWorktreeDelta(
      runLoop,
      resolved?.baselineOwnedPathsForCheckpoint().orEmpty(),
    )
      ?: run {
        FeatureTaskRuntimeRunLoopCheckpointRemediation.blockCheckpointScope(
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
    val phaseWritten = FeatureTaskRuntimeRunLoopCheckpoint.phaseWrittenPaths(
      runLoop,
      precedingPhaseId,
      worktreeDelta,
      persistedOwned,
    )
      .filterNot { it in evictedFeatureSpecs }
    val writingIntroduced = FeatureTaskRuntimeRunLoopCheckpoint.writingPhaseIntroducedPaths(runLoop, worktreeDelta)
    val seedOwned = (
      resolved?.workflowOwnedPaths.orEmpty() +
        phaseWritten
          .takeIf { FeatureTaskRuntimeRunLoopCheckpoint.mayExtendOwnedInventory(precedingPhaseId) }
          .orEmpty() +
        writingIntroduced
      ).distinct()
    val deletedPaths = absorbableDeletedPaths(
      deleted = FeatureTaskRuntimeRunLoopCheckpoint.checkpointDeletedPaths(runLoop),
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

  internal fun finalizeRemediationCommit(
    runLoop: FeatureTaskRuntimeRunLoop,
    prepared: FeatureTaskRuntimeRunLoopCheckpointRemediation.RemediationCommitPrepared,
  ): RemediationCheckpointCommit? {
    val commit = FeatureTaskRuntimeRunLoopCheckpoint.writeSubtaskCommit(
      runLoop,
      prepared.branch,
      prepared.message,
      prepared.subtaskIdentity,
    )
    if (!commit.ok) {
      FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpoint(
        runLoop,
        prepared.precedingPhaseId,
        prepared.branch,
        FeatureTaskRuntimeRunLoopCheckpoint.withIndexRestoreOutcome(
          runLoop,
          commit.error,
          prepared.ownedPaths,
          prepared.indexSnapshot,
        ),
        FeatureTaskRuntimeRunLoopCheckpoint.remediationCheckpointBlockedReasonFor(),
      )
      return null
    }
    val commitSha = commit.value.orEmpty().trim()
    if (commitSha.isBlank()) {
      FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpoint(
        runLoop,
        prepared.precedingPhaseId,
        prepared.branch,
        "remediation checkpoint commit returned an empty sha",
        FeatureTaskRuntimeRunLoopCheckpoint.remediationCheckpointBlockedReasonFor(),
      )
      return null
    }
    val recorded = FeatureTaskRuntimeRunLoopCheckpoint.recordCheckpointIdentity(
      runLoop,
      RecordCheckpointIdentityArgs(
        precedingPhaseId = prepared.precedingPhaseId,
        branch = prepared.branch,
        loopId = prepared.loopId,
        ownedPaths = prepared.ownedPaths,
        parentSha = prepared.parentSha,
        commitSha = commitSha,
        blockedReason = FeatureTaskRuntimeRunLoopCheckpoint.remediationCheckpointBlockedReasonFor(),
      ),
    )
    if (!recorded) {
      FeatureTaskRuntimeRunLoopCheckpointRemediation.rollbackRemediationCheckpointCommit(
        runLoop,
        commitSha,
        prepared.parentSha,
        identityRecorded = false,
      )
      return null
    }
    return RemediationCheckpointCommit(commitSha = commitSha, parentSha = prepared.parentSha)
  }

  fun checkpointIdentitiesForRollback(
    runLoop: FeatureTaskRuntimeRunLoop,
    commitSha: String,
  ): List<FeatureTaskRuntimeCheckpointIdentity> {
    require(commitSha.isNotBlank()) { "rollback requires a non-blank commit sha" }
    val subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString()
      ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
    return runCatching {
      runLoop.recorder.loadCheckpointIdentities(runLoop.request.workflowId, runLoop.request.dbPathOverride)
    }.fold(
      onSuccess = { loaded -> loaded.orEmpty() },
      onFailure = { error ->
        FeatureTaskRuntimeRunLoopCheckpointRemediation.recordRemediationRollbackDegradation(
          runLoop,
          seam = "FeatureTaskRuntimeRunLoop.rollbackRemediationCheckpointCommit",
          valueUsed = runLoop.request.workflowId,
          valueExpected = "checkpoint identities for rollback",
          cause = "loadCheckpointIdentities failed: " +
            error.message.orEmpty().ifBlank { error::class.simpleName.orEmpty() },
        )
        emptyList()
      },
    )
      .filter { it.issueKey == runLoop.request.issueKey && it.subtaskId == subtaskId }
      .sortedBy { it.sequenceNumber }
  }

  fun subtaskCommitIdentity(runLoop: FeatureTaskRuntimeRunLoop): FeatureTaskRuntimeSubtaskCommitIdentity =
    FeatureTaskRuntimeSubtaskCommitIdentity(
      issueKey = runLoop.request.issueKey,
      subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString() ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
    )

  internal fun checkpointCommitMessage(runLoop: FeatureTaskRuntimeRunLoop, args: CheckpointCommitMessageArgs): String {
    val branch = args.branch
    val phaseId = args.phaseId
    val loopId = args.loopId
    val identity = args.identity
    val intent = args.intent
    val subtaskName = runLoop.request.goalContinuation?.subtaskName?.trim()?.takeIf(String::isNotBlank)
    if (subtaskName == null && runLoop.request.goalContinuation != null) {
      runCatching {
        runLoop.diagnostics.warning(
          FeatureTaskRuntimeCheckpointMessage.missingSubtaskNameRecord(identity.issueKey, identity.subtaskId),
        )
      }
    }
    return FeatureTaskRuntimeCheckpointMessage.build(
      issueKey = runLoop.request.issueKey,
      subtaskName = subtaskName,
      metadata = FeatureTaskRuntimeCheckpointMetadata(
        phaseId = phaseId,
        loopId = loopId,
        generation = FeatureTaskRuntimeRunLoopCheckpoint.checkpointGeneration(runLoop, loopId),
        branch = branch,
        intent = intent,
      ),
      identity = identity,
    )
  }

  internal fun subtaskCommitLedgerState(
    runLoop: FeatureTaskRuntimeRunLoop,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  ): SubtaskCommitLedgerState {
    val read = runCatching {
      runLoop.recorder.loadCheckpointIdentities(
        runLoop.request.workflowId,
        runLoop.request.dbPathOverride,
      )
    }
    val identities = read.getOrNull()
    val cause = read.exceptionOrNull()
      ?.let { "the checkpoint-identity store could not be read (${it.message ?: it::class.simpleName})" }
      ?: "no workflow row recorded any checkpoint identity for this run".takeIf { identities == null }
    if (cause != null) {
      val ledgerRecord = FeatureTaskRuntimeRunLoopCheckpoint.ledgerUnavailableRecord(identity, cause)
      runCatching { runLoop.diagnostics.warning(ledgerRecord) }
      return SubtaskCommitLedgerState(commitSha = null, nextSequenceNumber = 0)
    }
    val recorded = requireNotNull(identities)
    return SubtaskCommitLedgerState(
      commitSha = recorded
        .filter { it.issueKey == identity.issueKey && it.subtaskId == identity.subtaskId }
        .maxByOrNull { it.sequenceNumber }
        ?.commitSha,
      nextSequenceNumber = (recorded.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
    )
  }

  fun ledgerUnavailableRecord(identity: FeatureTaskRuntimeSubtaskCommitIdentity, cause: String): String =
    "seam=FeatureTaskRuntimeRunLoop.subtaskCommitLedgerState value_used='no durable pointer, sequence 0' " +
      "value_expected=the recorded checkpoint-identity ledger for '${identity.issueKey}/${identity.subtaskId}' " +
      "cause=$cause"

  fun writeSubtaskCommit(
    runLoop: FeatureTaskRuntimeRunLoop,
    branch: String,
    message: String,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  ): WorkflowGitOperationResult {
    val ledger = FeatureTaskRuntimeRunLoopCheckpoint.subtaskCommitLedgerState(runLoop, identity)
    val headSha = runLoop.phaseGates.gitOperations.headCommitSha(runLoop.request.repoRoot)
      .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
    val decision = FeatureTaskRuntimeSubtaskCommitResolver.decide(
      identity = identity,
      durableCommitSha = ledger.commitSha,
      head = FeatureTaskRuntimeSubtaskCommitHeadState(
        sha = headSha,
        commitMessage = if (ledger.commitSha == null && headSha != null) headCommitMessageOrNull(runLoop) else null,
        isUnpushed = branchHasUnpushedCommits(runLoop, branch),
      ),
      sequenceNumber = ledger.nextSequenceNumber,
    )
    return runLoop.phaseGates.gitOperations.writeSubtaskCommitPreservingHistory(
      SubtaskCommitPreservationRequest(
        repoRoot = runLoop.request.repoRoot,
        decision = decision,
        identity = identity,
        message = message,
        allowUnchangedIndex = false,
        record = { record -> runCatching { runLoop.diagnostics.warning(record) } },
      ),
    )
  }

  fun headCommitMessageOrNull(runLoop: FeatureTaskRuntimeRunLoop): String? =
    runLoop.phaseGates.gitOperations.headCommitMessage(runLoop.request.repoRoot).takeIf { it.ok }?.value

  fun branchHasUnpushedCommits(runLoop: FeatureTaskRuntimeRunLoop, branch: String): Boolean {
    val unpushed = runLoop.phaseGates.gitOperations.localBranchHasUnpushedCommits(runLoop.request.repoRoot, branch)
    return unpushed.ok && unpushed.value.orEmpty().trim().equals("true", ignoreCase = true)
  }

  fun withIndexRestoreOutcome(
    runLoop: FeatureTaskRuntimeRunLoop,
    error: String,
    ownedPaths: List<String>,
    snapshot: String,
  ): String {
    val restored = runLoop.phaseGates.gitOperations.restoreIndexState(runLoop.request.repoRoot, ownedPaths, snapshot)
    return if (restored.ok) {
      "$error; the pre-checkpoint index was restored and the working tree is unchanged"
    } else {
      "$error; the pre-checkpoint index could NOT be restored (${restored.error}) — inspect " +
        "`git status` before committing anything yourself"
    }
  }

  fun checkpointGeneration(runLoop: FeatureTaskRuntimeRunLoop, loopId: String?): Int = loopId?.let {
    runLoop.state.edgeIterationCount(it)
  } ?: 0

  internal fun recordCheckpointIdentity(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: RecordCheckpointIdentityArgs,
  ): Boolean {
    val precedingPhaseId = args.precedingPhaseId
    val branch = args.branch
    val loopId = args.loopId
    val ownedPaths = args.ownedPaths
    val parentSha = args.parentSha
    val commitSha = args.commitSha
    val blockedReason = args.blockedReason
    val recorded = runCatching {
      runLoop.recorder.appendCheckpointIdentity(
        AppendCheckpointIdentityArgs(
          workflowId = runLoop.request.workflowId,
          issueKey = runLoop.request.issueKey,
          subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString()
            ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
          branch = branch,
          phaseId = precedingPhaseId,
          loopId = loopId,
          generation = checkpointGeneration(runLoop, loopId),
          parentSha = parentSha,
          ownedPaths = ownedPaths,
          commitSha = commitSha,
          dbOverride = runLoop.request.dbPathOverride,
        ),
      )
    }
    return if (recorded.getOrDefault(false)) {
      true
    } else {
      FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpoint(
        runLoop,
        precedingPhaseId,
        branch,
        "checkpoint commit '$commitSha' was created but its durable identity record could not be " +
          "written (${recorded.exceptionOrNull()?.message ?: "the workflow row was absent"}), so the " +
          "commit cannot be attributed to this workflow's authority boundary",
        blockedReason,
      )
    }
  }

  fun remediationCheckpointBlockedReasonFor(): (String, String) -> String =
    { branch, error -> FeatureTaskRuntimeRunLoopPlanningBranch.remediationCheckpointBlockedReason(branch, error) }

  fun blockCheckpoint(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    branch: String,
    error: String,
    blockedReason: (String, String) -> String,
  ): Boolean {
    FeatureTaskRuntimeRunLoopPlanningBranch.blockAt(runLoop, precedingPhaseId, blockedReason(branch, error))
    return false
  }

  fun matchingBackwardEdge(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
  ): FeatureTaskRuntimeBackwardEdge? =
    runLoop.transitions.backwardEdges.firstOrNull { it.fromPhaseId == phaseId && it.triggeringVerdict == verdict }
}
