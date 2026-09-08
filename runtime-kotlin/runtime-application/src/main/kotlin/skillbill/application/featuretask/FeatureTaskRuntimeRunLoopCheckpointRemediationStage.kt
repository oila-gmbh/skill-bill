package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
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
      return true
    }
    val head = runLoop.phaseGates.gitOperations.currentBranch(runLoop.request.repoRoot)
    if (!head.ok || head.value.trim() != branch.trim()) {
      return true
    }
    val scope = runLoop.collaborators.checkpoint.resolveCheckpointScope(
      runLoop,
      precedingPhaseId,
      branch,
      blockedReason,
    ) ?: return false
    return when (scope) {
      is FeatureTaskRuntimeCheckpointDecision.Skip -> true
      is FeatureTaskRuntimeCheckpointDecision.Block -> {
        runLoop.collaborators.planningBranch.blockAt(runLoop, precedingPhaseId, scope.reason)
        false
      }
      is FeatureTaskRuntimeCheckpointDecision.Stage -> {
        if (scope.adoptedPaths.isNotEmpty()) {
          runCatching {
            runLoop.diagnostics.warning(adoptionWarning(branch, scope.adoptedPaths))
          }
        }
        runLoop.collaborators.repairReceipt.commitCheckpoint(
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

  /**
   * Resolves what this checkpoint may stage. Returns null when a git read failed and the phase was
   * already blocked; an unmeasurable inventory can never degrade into "owns nothing", because a
   * checkpoint reading that would skip silently and leave the phase's work uncommitted.
   */

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
