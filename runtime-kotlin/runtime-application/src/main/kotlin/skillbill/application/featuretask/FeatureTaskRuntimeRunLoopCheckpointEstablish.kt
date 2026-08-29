package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.workflow.repoRoot
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity

internal fun FeatureTaskRuntimeRunLoop.establishRemediationCheckpoint(
  precedingPhaseId: String,
  loopId: String,
): Boolean {
  if (remediationCheckpointSkippable()) {
    return recordRemediationBaseIfNeeded(precedingPhaseId, loopId, commitSha = null, parentSha = null)
  }
  val branch = requireNotNull(resolvedBranch)
  if (remediationCheckpointOffBranch(branch)) {
    return recordRemediationBaseIfNeeded(precedingPhaseId, loopId, commitSha = null, parentSha = null)
  }
  val scope = resolveCheckpointScope(precedingPhaseId, branch, ::remediationCheckpointBlockedReason) ?: return false
  return when (scope) {
    is FeatureTaskRuntimeCheckpointDecision.Skip ->
      recordRemediationBaseIfNeeded(precedingPhaseId, loopId, commitSha = null, parentSha = null)
    is FeatureTaskRuntimeCheckpointDecision.Block -> {
      blockAt(precedingPhaseId, scope.reason)
      false
    }
    is FeatureTaskRuntimeCheckpointDecision.Stage ->
      establishRemediationCheckpointStage(precedingPhaseId, branch, loopId, scope)
  }
}

internal fun FeatureTaskRuntimeRunLoop.commitRemediationCheckpoint(
  precedingPhaseId: String,
  branch: String,
  loopId: String,
  ownedPaths: List<String>,
): RemediationCheckpointCommit? {
  val prepared = prepareRemediationCommit(precedingPhaseId, branch, loopId, ownedPaths) ?: return null
  return finalizeRemediationCommit(prepared)
}

internal fun FeatureTaskRuntimeRunLoop.recordRemediationBaseIfNeeded(
  precedingPhaseId: String,
  loopId: String,
  commitSha: String?,
  parentSha: String?,
): Boolean {
  // Only the review_fix edge reserves a remediation review pass, so only it has a pre-fix base to
  // record. The audit_gap edge re-enters implement without one and must not be gated on it.
  if (loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) return true
  val recorded = recordRemediationBaseSha(precedingPhaseId, commitSha)
  if (recorded) return true
  if (commitSha != null) {
    rollbackRemediationCheckpointCommit(commitSha, parentSha, identityRecorded = true)
  }
  return false
}

/**
 * Restores the branch tip from the prior checkpoint identity commit when one exists; otherwise
 * removes the subtask commit and leaves the branch at its pre-subtask tip. Idempotent when HEAD
 * no longer names [commitSha].
 */
internal fun FeatureTaskRuntimeRunLoop.rollbackRemediationCheckpointCommit(
  commitSha: String,
  parentSha: String?,
  identityRecorded: Boolean,
) {
  val normalizedCommit = commitSha.trim()
  val head = phaseGates.gitOperations.headCommitSha(request.repoRoot)
  if (!head.ok || head.value.trim() != normalizedCommit) return
  val identities = checkpointIdentitiesForRollback(normalizedCommit)
  val restoreSha = remediationRollbackTargetSha(
    identities = identities,
    commitSha = normalizedCommit,
    parentSha = parentSha,
    identityRecorded = identityRecorded,
  ) ?: return
  val reset = phaseGates.gitOperations.resetSoftToCommit(request.repoRoot, restoreSha)
  if (!reset.ok) {
    recordRemediationRollbackDegradation(
      seam = "FeatureTaskRuntimeRunLoop.rollbackRemediationCheckpointCommit",
      valueUsed = restoreSha,
      valueExpected = "successful soft reset to restore target",
      cause = reset.error.ifBlank { "resetSoftToCommit failed" },
    )
  }
}

internal fun FeatureTaskRuntimeRunLoop.recordRemediationRollbackDegradation(
  seam: String,
  valueUsed: String,
  valueExpected: String,
  cause: String,
) {
  goalContinuationRecorder.appendRemediationRollbackDegradationEvidence(
    workflowId = request.workflowId,
    signal = RemediationDegradationSignal(
      seam = seam,
      valueUsed = valueUsed,
      valueExpected = valueExpected,
      cause = cause,
    ),
    dbOverride = request.dbPathOverride,
  )
}

internal fun FeatureTaskRuntimeRunLoop.remediationRollbackTargetSha(
  identities: List<FeatureTaskRuntimeCheckpointIdentity>,
  commitSha: String,
  parentSha: String?,
  identityRecorded: Boolean,
): String? {
  val fallback = parentSha?.trim()?.takeIf(String::isNotBlank)
  val predecessor = rollbackPredecessor(identities, commitSha, identityRecorded) ?: return fallback
  return resolvedPredecessorSha(predecessor) ?: fallback
}

internal fun FeatureTaskRuntimeRunLoop.rollbackPredecessor(
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

internal fun FeatureTaskRuntimeRunLoop.resolvedPredecessorSha(
  predecessor: FeatureTaskRuntimeCheckpointIdentity,
): String? {
  val predecessorCommitSha = predecessor.commitSha.trim()
  if (predecessorCommitSha.isBlank()) {
    recordRemediationRollbackDegradation(
      seam = "FeatureTaskRuntimeRunLoop.remediationRollbackTargetSha",
      valueUsed = "(blank)",
      valueExpected = "resolvable predecessor identity commit",
      cause = "predecessor identity commit sha was missing or blank",
    )
    return null
  }
  val resolved = phaseGates.gitOperations.resolveCommit(request.repoRoot, predecessorCommitSha)
  val predecessorSha = resolved.value.orEmpty().trim().takeIf { resolved.ok && it.isNotBlank() }
  if (predecessorSha == null) {
    recordRemediationRollbackDegradation(
      seam = "FeatureTaskRuntimeRunLoop.remediationRollbackTargetSha",
      valueUsed = predecessorCommitSha,
      valueExpected = "resolvable predecessor identity commit",
      cause = resolved.error.takeIf { !resolved.ok && it.isNotBlank() }
        ?: "predecessor commit '$predecessorCommitSha' did not resolve",
    )
  }
  return predecessorSha
}

/**
 * A checkpoint commits the inventory this workflow owns and nothing else. The trigger is the OWNED
 * delta, not a non-blank `git status`: a tree dirty only with someone else's work has nothing for
 * this workflow to checkpoint, and committing it would attribute their changes to this run.
 */
internal fun FeatureTaskRuntimeRunLoop.checkpointEstablished(
  precedingPhaseId: String,
  loopId: String?,
  intent: String,
  blockedReason: (String, String) -> String,
): Boolean {
  val branch = resolvedBranch
  if (branch == null || FeatureTaskRuntimeBranchSetup.protectedBranchName(branch) != null) {
    return true
  }
  val head = phaseGates.gitOperations.currentBranch(request.repoRoot)
  if (!head.ok || head.value.trim() != branch.trim()) {
    return true
  }
  val scope = resolveCheckpointScope(precedingPhaseId, branch, blockedReason) ?: return false
  return when (scope) {
    is FeatureTaskRuntimeCheckpointDecision.Skip -> true
    is FeatureTaskRuntimeCheckpointDecision.Block -> {
      blockAt(precedingPhaseId, scope.reason)
      false
    }
    is FeatureTaskRuntimeCheckpointDecision.Stage -> {
      if (scope.adoptedPaths.isNotEmpty()) {
        runCatching {
          diagnostics.warning(FeatureTaskRuntimeCheckpointScope.adoptionWarning(branch, scope.adoptedPaths))
        }
      }
      commitCheckpoint(
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
