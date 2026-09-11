package skillbill.engine.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.stagePaths

@Inject
class FeatureTaskRuntimeRunLoopCheckpointRemediationStage {
  fun checkpointEstablished(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    loopId: String?,
    intent: String,
    blockedReason: (String, String) -> String,
  ): Boolean {
    val branch = runLoop.session.resolvedBranch
    if (branch == null || FeatureTaskRuntimeBranchSetup.protectedBranchName(branch) != null) {
      return unavailableBranchResult(runLoop, precedingPhaseId, branch, blockedReason)
    }
    if (remediationCheckpointOffBranch(runLoop, branch)) {
      return unavailableCheckedOutBranchResult(runLoop, precedingPhaseId, branch, blockedReason)
    }
    if (!FeatureTaskRuntimeSubtaskCommitMigrationNormalizer.reconcile(
        runLoop,
        precedingPhaseId,
        branch,
        blockedReason,
      )
    ) {
      return false
    }
    return establishCheckpointScope(
      CheckpointScopeEstablishmentRequest(runLoop, precedingPhaseId, branch, loopId, intent, blockedReason),
    )
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
    val committed = runLoop.collaborators.checkpointContinued2.commitRemediationCheckpoint(
      runLoop,
      precedingPhaseId = precedingPhaseId,
      branch = branch,
      loopId = loopId,
      ownedPaths = scope.ownedPaths,
    ) ?: return false
    return runLoop.collaborators.checkpointContinued2.recordRemediationBaseIfNeeded(
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
      runLoop.collaborators.checkpointContinued6.blockCheckpoint(
        runLoop,
        precedingPhaseId,
        branch,
        snapshot.error,
        runLoop.collaborators.checkpointContinued6.remediationCheckpointBlockedReasonFor(runLoop),
      )
      return null
    }
    val parentSha = runLoop.phaseGates.gitOperations.headCommitSha(runLoop.request.repoRoot)
      .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
    val staged = runLoop.phaseGates.gitOperations.stagePaths(runLoop.request.repoRoot, ownedPaths)
    if (!staged.ok) {
      runLoop.collaborators.checkpointContinued6.blockCheckpoint(
        runLoop,
        precedingPhaseId,
        branch,
        runLoop.collaborators.checkpointContinued5.withIndexRestoreOutcome(
          runLoop,
          staged.error,
          ownedPaths,
          snapshot.value.orEmpty(),
        ),
        runLoop.collaborators.checkpointContinued6.remediationCheckpointBlockedReasonFor(runLoop),
      )
      return null
    }
    val subtaskIdentity = runLoop.collaborators.checkpointContinued4.subtaskCommitIdentity(runLoop)
    val message = runLoop.collaborators.checkpointContinued4.checkpointCommitMessage(
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

private fun unavailableBranchResult(
  runLoop: FeatureTaskRuntimeRunLoop,
  precedingPhaseId: String,
  branch: String?,
  blockedReason: (String, String) -> String,
): Boolean = if (isGoalContinuationRun(runLoop.request)) {
  runLoop.collaborators.checkpointContinued6.blockCheckpoint(
    runLoop,
    precedingPhaseId,
    branch.orEmpty(),
    "the goal child has no resolved, unprotected checked-out branch for its subtask commit",
    blockedReason,
  )
} else {
  true
}

private fun unavailableCheckedOutBranchResult(
  runLoop: FeatureTaskRuntimeRunLoop,
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
): Boolean = if (isGoalContinuationRun(runLoop.request)) {
  runLoop.collaborators.checkpointContinued6.blockCheckpoint(
    runLoop,
    precedingPhaseId,
    branch,
    "the resolved goal child branch is not the checked-out branch; refusing an unowned commit",
    blockedReason,
  )
} else {
  true
}

private data class CheckpointScopeEstablishmentRequest(
  val runLoop: FeatureTaskRuntimeRunLoop,
  val precedingPhaseId: String,
  val branch: String,
  val loopId: String?,
  val intent: String,
  val blockedReason: (String, String) -> String,
)

private fun establishCheckpointScope(request: CheckpointScopeEstablishmentRequest): Boolean {
  val runLoop = request.runLoop
  val scope = runLoop.collaborators.checkpoint.resolveCheckpointScope(
    runLoop,
    request.precedingPhaseId,
    request.branch,
    request.blockedReason,
  ) ?: return false
  return when (scope) {
    is FeatureTaskRuntimeCheckpointDecision.Skip -> runLoop.collaborators.checkpointContinued5.reconcileBeforeReview(
      runLoop,
      request.precedingPhaseId,
      request.branch,
      request.blockedReason,
    )
    is FeatureTaskRuntimeCheckpointDecision.Block -> {
      runLoop.collaborators.planningBranch.blockAt(runLoop, request.precedingPhaseId, scope.reason)
      false
    }
    is FeatureTaskRuntimeCheckpointDecision.Stage -> commitCheckpointScope(
      CommitCheckpointScopeRequest(
        runLoop,
        request.precedingPhaseId,
        request.branch,
        request.loopId,
        request.intent,
        scope,
        request.blockedReason,
      ),
    )
  }
}

private data class CommitCheckpointScopeRequest(
  val runLoop: FeatureTaskRuntimeRunLoop,
  val precedingPhaseId: String,
  val branch: String,
  val loopId: String?,
  val intent: String,
  val scope: FeatureTaskRuntimeCheckpointDecision.Stage,
  val blockedReason: (String, String) -> String,
)

private fun commitCheckpointScope(request: CommitCheckpointScopeRequest): Boolean {
  val runLoop = request.runLoop
  val branch = request.branch
  val scope = request.scope
  if (scope.adoptedPaths.isNotEmpty()) {
    runCatching { runLoop.diagnostics.warning(adoptionWarning(branch, scope.adoptedPaths)) }
  }
  return runLoop.collaborators.repairReceipt.commitCheckpoint(
    runLoop,
    CommitCheckpointArgs(
      precedingPhaseId = request.precedingPhaseId,
      branch = branch,
      loopId = request.loopId,
      intent = request.intent,
      ownedPaths = scope.ownedPaths,
      blockedReason = request.blockedReason,
    ),
  )
}
