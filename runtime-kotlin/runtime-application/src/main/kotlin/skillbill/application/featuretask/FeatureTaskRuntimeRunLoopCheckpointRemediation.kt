package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.contracts.JsonCodec
import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.pathContentIdentities
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.ports.workflow.gitops.stagePaths
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch

object FeatureTaskRuntimeRunLoopCheckpointRemediation {
  fun concurrentlyModifiedOwnedPaths(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    ownedPaths: List<String>,
  ): List<String> {
    val captured = runLoop.session.phaseContentIdentities[phaseId] ?: return emptyList()
    val current = runLoop.phaseGates.gitOperations.pathContentIdentities(runLoop.request.repoRoot, ownedPaths)
    if (!current.ok) return emptyList()
    val now = FeatureTaskRuntimeRunLoopLaunch.parseContentIdentities(current.value.orEmpty())
    return captured.filter { (path, identity) -> path in now && now[path] != identity }.keys.sorted()
  }

  fun blockCheckpointScope(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    branch: String,
    error: String,
    blockedReason: (String, String) -> String,
  ): FeatureTaskRuntimeCheckpointDecision? {
    FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpoint(runLoop, precedingPhaseId, branch, error, blockedReason)
    return null
  }

  fun checkpointWorktreeDelta(runLoop: FeatureTaskRuntimeRunLoop, baselineOwnedPaths: List<String>): List<String>? {
    val owned = runLoop.phaseGates.gitOperations.repositoryOwnedPaths(runLoop.request.repoRoot)
    if (!owned.ok) return null
    val baseline = baselineOwnedPaths.toSet()
    return owned.value.orEmpty()
      .split(OWNED_PATH_DELIMITER)
      .map(String::trim)
      .filter(String::isNotBlank)
      .filterNot { it in baseline }
      .filterNot(::isRuntimePrivatePath)
      .distinct()
      .sorted()
  }

  fun recordRemediationBaseSha(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    commitSha: String? = null,
  ): Boolean {
    if (!isGoalContinuationRun(runLoop.request)) return true
    if (FeatureTaskRuntimeRunLoopPlanningBranch.goalReviewStateOrNull(runLoop) == null) return true
    val baseSha = commitSha?.trim()?.takeIf(String::isNotBlank) ?: run {
      val head = runLoop.phaseGates.gitOperations.headCommitSha(runLoop.request.repoRoot)
      if (!head.ok || head.value.isBlank()) {
        return FeatureTaskRuntimeRunLoopRepairReceipt.blockRemediationBaseSha(
          runLoop,
          precedingPhaseId,
          head.error.ifBlank { "HEAD resolved to an empty sha." },
        )
      }
      head.value.trim()
    }
    return runCatching {
      runLoop.goalContinuationRecorder.updateReviewState(
        runLoop.request.workflowId,
        runLoop.request.dbPathOverride,
      ) { state ->
        state.copy(remediationBaseSha = baseSha)
      }
    }.fold(
      onSuccess = { recorded ->
        if (recorded != null) {
          true
        } else {
          FeatureTaskRuntimeRunLoopRepairReceipt.blockRemediationBaseSha(
            runLoop,
            precedingPhaseId,
            "the review runLoop.state could not be updated.",
          )
        }
      },
      onFailure = { error ->
        FeatureTaskRuntimeRunLoopRepairReceipt.blockRemediationBaseSha(
          runLoop,
          precedingPhaseId,
          error.message.orEmpty(),
        )
      },
    )
  }

  internal fun completedImplementFixProducedOutputs(run: PhaseRun, outputMap: Map<String, Any?>): Map<String, Any?>? =
    outputMap
      .takeIf {
        run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX &&
          it["status"] == STATUS_COMPLETED
      }
      ?.let { JsonCodec.anyToStringAnyMap(it["produced_outputs"]).orEmpty() }

  fun establishRemediationCheckpoint(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    loopId: String,
  ): Boolean {
    if (FeatureTaskRuntimeRunLoopCheckpointRemediation.remediationCheckpointSkippable(runLoop)) {
      return FeatureTaskRuntimeRunLoopCheckpointRemediation.recordRemediationBaseIfNeeded(
        runLoop,
        precedingPhaseId,
        loopId,
        commitSha = null,
        parentSha = null,
      )
    }
    val branch = requireNotNull(runLoop.session.resolvedBranch)
    if (FeatureTaskRuntimeRunLoopCheckpointRemediation.remediationCheckpointOffBranch(runLoop, branch)) {
      return FeatureTaskRuntimeRunLoopCheckpointRemediation.recordRemediationBaseIfNeeded(
        runLoop,
        precedingPhaseId,
        loopId,
        commitSha = null,
        parentSha = null,
      )
    }
    val scope = FeatureTaskRuntimeRunLoopCheckpoint.resolveCheckpointScope(
      runLoop,
      precedingPhaseId,
      branch,
    ) { errorBranch, error ->
      FeatureTaskRuntimeRunLoopPlanningBranch.remediationCheckpointBlockedReason(
        errorBranch,
        error,
      )
    } ?: return false
    return when (scope) {
      is FeatureTaskRuntimeCheckpointDecision.Skip ->
        FeatureTaskRuntimeRunLoopCheckpointRemediation.recordRemediationBaseIfNeeded(
          runLoop,
          precedingPhaseId,
          loopId,
          commitSha = null,
          parentSha = null,
        )
      is FeatureTaskRuntimeCheckpointDecision.Block -> {
        FeatureTaskRuntimeRunLoopPlanningBranch.blockAt(runLoop, precedingPhaseId, scope.reason)
        false
      }
      is FeatureTaskRuntimeCheckpointDecision.Stage ->
        FeatureTaskRuntimeRunLoopCheckpointRemediation.establishRemediationCheckpointStage(
          runLoop,
          precedingPhaseId,
          branch,
          loopId,
          scope,
        )
    }
  }

  internal fun commitRemediationCheckpoint(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    branch: String,
    loopId: String,
    ownedPaths: List<String>,
  ): RemediationCheckpointCommit? {
    val prepared = FeatureTaskRuntimeRunLoopCheckpointRemediation.prepareRemediationCommit(
      runLoop,
      precedingPhaseId,
      branch,
      loopId,
      ownedPaths,
    ) ?: return null
    return FeatureTaskRuntimeRunLoopCheckpoint.finalizeRemediationCommit(runLoop, prepared)
  }

  fun recordRemediationBaseIfNeeded(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    loopId: String,
    commitSha: String?,
    parentSha: String?,
  ): Boolean {
    if (loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) return true
    val recorded = FeatureTaskRuntimeRunLoopCheckpointRemediation.recordRemediationBaseSha(
      runLoop,
      precedingPhaseId,
      commitSha,
    )
    if (recorded) return true
    if (commitSha != null) {
      rollbackRemediationCheckpointCommit(runLoop, commitSha, parentSha, identityRecorded = true)
    }
    return false
  }

  fun rollbackRemediationCheckpointCommit(
    runLoop: FeatureTaskRuntimeRunLoop,
    commitSha: String,
    parentSha: String?,
    identityRecorded: Boolean,
  ) {
    val normalizedCommit = commitSha.trim()
    val head = runLoop.phaseGates.gitOperations.headCommitSha(runLoop.request.repoRoot)
    if (!head.ok || head.value.trim() != normalizedCommit) return
    val identities = FeatureTaskRuntimeRunLoopCheckpoint.checkpointIdentitiesForRollback(
      runLoop,
      normalizedCommit,
    )
    val restoreSha = remediationRollbackTargetSha(
      runLoop,
      identities = identities,
      commitSha = normalizedCommit,
      parentSha = parentSha,
      identityRecorded = identityRecorded,
    ) ?: return
    val reset = runLoop.phaseGates.gitOperations.resetSoftToCommit(runLoop.request.repoRoot, restoreSha)
    if (!reset.ok) {
      recordRemediationRollbackDegradation(
        runLoop,
        seam = "FeatureTaskRuntimeRunLoop.rollbackRemediationCheckpointCommit",
        valueUsed = restoreSha,
        valueExpected = "successful soft reset to restore target",
        cause = reset.error.ifBlank { "resetSoftToCommit failed" },
      )
    }
  }

  fun recordRemediationRollbackDegradation(
    runLoop: FeatureTaskRuntimeRunLoop,
    seam: String,
    valueUsed: String,
    valueExpected: String,
    cause: String,
  ) {
    runLoop.goalContinuationRecorder.appendRemediationRollbackDegradationEvidence(
      workflowId = runLoop.request.workflowId,
      signal = RemediationDegradationSignal(
        seam = seam,
        valueUsed = valueUsed,
        valueExpected = valueExpected,
        cause = cause,
      ),
      dbOverride = runLoop.request.dbPathOverride,
    )
  }

  fun remediationRollbackTargetSha(
    runLoop: FeatureTaskRuntimeRunLoop,
    identities: List<FeatureTaskRuntimeCheckpointIdentity>,
    commitSha: String,
    parentSha: String?,
    identityRecorded: Boolean,
  ): String? {
    val fallback = parentSha?.trim()?.takeIf(String::isNotBlank)
    val predecessor = rollbackPredecessor(identities, commitSha, identityRecorded) ?: return fallback
    return resolvedPredecessorSha(runLoop, predecessor) ?: fallback
  }

  fun rollbackPredecessor(
    identities: List<FeatureTaskRuntimeCheckpointIdentity>,
    commitSha: String,
    identityRecorded: Boolean,
  ): FeatureTaskRuntimeCheckpointIdentity? {
    val currentIdentity = if (identityRecorded) identities.lastOrNull { it.commitSha == commitSha } else null
    return when {
      currentIdentity != null && currentIdentity.sequenceNumber > 0 ->
        identities.find { it.sequenceNumber == currentIdentity.sequenceNumber - 1 }
      currentIdentity != null -> null
      else -> identities.maxByOrNull { it.sequenceNumber }
    }
  }

  fun resolvedPredecessorSha(
    runLoop: FeatureTaskRuntimeRunLoop,
    predecessor: FeatureTaskRuntimeCheckpointIdentity,
  ): String? {
    val predecessorCommitSha = predecessor.commitSha.trim()
    if (predecessorCommitSha.isBlank()) {
      recordRemediationRollbackDegradation(
        runLoop,
        seam = "FeatureTaskRuntimeRunLoop.remediationRollbackTargetSha",
        valueUsed = "(blank)",
        valueExpected = "resolvable predecessor identity commit",
        cause = "predecessor identity commit sha was missing or blank",
      )
      return null
    }
    val resolved = runLoop.phaseGates.gitOperations.resolveCommit(runLoop.request.repoRoot, predecessorCommitSha)
    val predecessorSha = resolved.value.orEmpty().trim().takeIf { resolved.ok && it.isNotBlank() }
    if (predecessorSha == null) {
      recordRemediationRollbackDegradation(
        runLoop,
        seam = "FeatureTaskRuntimeRunLoop.remediationRollbackTargetSha",
        valueUsed = predecessorCommitSha,
        valueExpected = "resolvable predecessor identity commit",
        cause = resolved.error.takeIf { !resolved.ok && it.isNotBlank() }
          ?: "predecessor commit '$predecessorCommitSha' did not resolve",
      )
    }
    return predecessorSha
  }

  fun checkpointEstablished(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    loopId: String?,
    intent: String,
    blockedReason: (String, String) -> String,
  ): Boolean {
    val branch = runLoop.session.resolvedBranch
    if (branch == null || FeatureTaskRuntimeBranchSetup.protectedBranchName(branch) != null) {
      return true
    }
    val head = runLoop.phaseGates.gitOperations.currentBranch(runLoop.request.repoRoot)
    if (!head.ok || head.value.trim() != branch.trim()) {
      return true
    }
    val scope = FeatureTaskRuntimeRunLoopCheckpoint.resolveCheckpointScope(
      runLoop,
      precedingPhaseId,
      branch,
      blockedReason,
    ) ?: return false
    return when (scope) {
      is FeatureTaskRuntimeCheckpointDecision.Skip -> true
      is FeatureTaskRuntimeCheckpointDecision.Block -> {
        FeatureTaskRuntimeRunLoopPlanningBranch.blockAt(runLoop, precedingPhaseId, scope.reason)
        false
      }
      is FeatureTaskRuntimeCheckpointDecision.Stage -> {
        if (scope.adoptedPaths.isNotEmpty()) {
          runCatching {
            runLoop.diagnostics.warning(adoptionWarning(branch, scope.adoptedPaths))
          }
        }
        FeatureTaskRuntimeRunLoopRepairReceipt.commitCheckpoint(
          runLoop,
          CommitCheckpointArgs(
            precedingPhaseId = precedingPhaseId,
            branch = branch,
            loopId = loopId,
            intent = intent,
            ownedPaths = scope.ownedPaths,
            blockedReason = blockedReason,
          ),
        )
      }
    }
  }

  fun remediationCheckpointSkippable(runLoop: FeatureTaskRuntimeRunLoop): Boolean {
    val branch = runLoop.session.resolvedBranch
    return branch == null || FeatureTaskRuntimeBranchSetup.protectedBranchName(branch) != null
  }

  fun remediationCheckpointOffBranch(runLoop: FeatureTaskRuntimeRunLoop, branch: String): Boolean {
    val head = runLoop.phaseGates.gitOperations.currentBranch(runLoop.request.repoRoot)
    return !head.ok || head.value.trim() != branch.trim()
  }

  fun establishRemediationCheckpointStage(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    branch: String,
    loopId: String,
    scope: FeatureTaskRuntimeCheckpointDecision.Stage,
  ): Boolean {
    if (scope.adoptedPaths.isNotEmpty()) {
      runCatching {
        runLoop.diagnostics.warning(adoptionWarning(branch, scope.adoptedPaths))
      }
    }
    val committed = FeatureTaskRuntimeRunLoopCheckpointRemediation.commitRemediationCheckpoint(
      runLoop,
      precedingPhaseId = precedingPhaseId,
      branch = branch,
      loopId = loopId,
      ownedPaths = scope.ownedPaths,
    ) ?: return false
    return FeatureTaskRuntimeRunLoopCheckpointRemediation.recordRemediationBaseIfNeeded(
      runLoop,
      precedingPhaseId = precedingPhaseId,
      loopId = loopId,
      commitSha = committed.commitSha,
      parentSha = committed.parentSha,
    )
  }

  internal data class RemediationCommitPrepared(
    val precedingPhaseId: String,
    val branch: String,
    val loopId: String,
    val ownedPaths: List<String>,
    val indexSnapshot: String,
    val parentSha: String?,
    val subtaskIdentity: FeatureTaskRuntimeSubtaskCommitIdentity,
    val message: String,
  )

  internal fun prepareRemediationCommit(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    branch: String,
    loopId: String,
    ownedPaths: List<String>,
  ): RemediationCommitPrepared? {
    val snapshot = runLoop.phaseGates.gitOperations.captureIndexState(runLoop.request.repoRoot, ownedPaths)
    if (!snapshot.ok) {
      FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpoint(
        runLoop,
        precedingPhaseId,
        branch,
        snapshot.error,
        FeatureTaskRuntimeRunLoopCheckpoint.remediationCheckpointBlockedReasonFor(),
      )
      return null
    }
    val parentSha = runLoop.phaseGates.gitOperations.headCommitSha(runLoop.request.repoRoot)
      .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
    val staged = runLoop.phaseGates.gitOperations.stagePaths(runLoop.request.repoRoot, ownedPaths)
    if (!staged.ok) {
      FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpoint(
        runLoop,
        precedingPhaseId,
        branch,
        FeatureTaskRuntimeRunLoopCheckpoint.withIndexRestoreOutcome(
          runLoop,
          staged.error,
          ownedPaths,
          snapshot.value.orEmpty(),
        ),
        FeatureTaskRuntimeRunLoopCheckpoint.remediationCheckpointBlockedReasonFor(),
      )
      return null
    }
    val subtaskIdentity = FeatureTaskRuntimeRunLoopCheckpoint.subtaskCommitIdentity(runLoop)
    val message = FeatureTaskRuntimeRunLoopCheckpoint.checkpointCommitMessage(
      runLoop,
      CheckpointCommitMessageArgs(
        branch = branch,
        phaseId = precedingPhaseId,
        loopId = loopId,
        identity = subtaskIdentity,
        intent = FeatureTaskRuntimeCheckpointMessage.INTENT_REMEDIATION,
      ),
    )
    return RemediationCommitPrepared(
      precedingPhaseId = precedingPhaseId,
      branch = branch,
      loopId = loopId,
      ownedPaths = ownedPaths,
      indexSnapshot = snapshot.value.orEmpty(),
      parentSha = parentSha,
      subtaskIdentity = subtaskIdentity,
      message = message,
    )
  }
}

fun FeatureTaskRuntimeResolvedBranch.baselineOwnedPathsForCheckpoint(): List<String> =
  baselineOwnedPaths.ifEmpty { baselineUntrackedPaths }
