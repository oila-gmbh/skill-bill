package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity

@Inject
class FeatureTaskRuntimeRunLoopCheckpointRemediationRollback {
  internal fun commitRemediationCheckpoint(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    branch: String,
    loopId: String,
    ownedPaths: List<String>,
  ): RemediationCheckpointCommit? {
    val prepared = runLoop.collaborators.checkpointContinued3.prepareRemediationCommit(
      runLoop,
      precedingPhaseId,
      branch,
      loopId,
      ownedPaths,
    ) ?: return null
    return runLoop.collaborators.checkpointContinued4.finalizeRemediationCommit(runLoop, prepared)
  }

  fun recordRemediationBaseIfNeeded(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    loopId: String,
    commitSha: String?,
    parentSha: String?,
  ): Boolean {
    // Only the review_fix edge reserves a remediation review pass, so only it has a pre-fix base to
    // record. The audit_gap edge re-enters implement without one and must not be gated on it.
    if (loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) return true
    val recorded = runLoop.collaborators.checkpointContinued1.recordRemediationBaseSha(
      runLoop,
      precedingPhaseId,
      commitSha,
    )
    if (recorded) return true
    if (commitSha != null) {
      runCatching {
        rollbackRemediationCheckpointCommit(runLoop, commitSha, parentSha, identityRecorded = true)
      }.onFailure { error ->
        if (error !is FeatureTaskRuntimeSubtaskCommitReconciliationError) throw error
      }
    }
    return false
  }

  /**
   * Restores the branch tip from the prior checkpoint identity commit when one exists; otherwise
   * removes the subtask commit and leaves the branch at its pre-subtask tip. Idempotent when HEAD
   * no longer names [commitSha].
   */
  fun rollbackRemediationCheckpointCommit(
    runLoop: FeatureTaskRuntimeRunLoop,
    commitSha: String,
    parentSha: String?,
    identityRecorded: Boolean,
  ) {
    val normalizedCommit = commitSha.trim()
    val head = runLoop.phaseGates.gitOperations.headCommitSha(runLoop.request.repoRoot)
    if (!head.ok) {
      throw rollbackReconciliationError(
        runLoop,
        "rollback HEAD could not be read (${head.error}); operator decision: repair Git access before resuming",
        null,
      )
    }
    if (head.value.trim() != normalizedCommit) return
    val identities = runLoop.collaborators.checkpointContinued4.checkpointIdentitiesForRollback(
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
    val recorded = runCatching {
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
    if (recorded.isFailure) {
      throw rollbackReconciliationError(
        runLoop,
        "rollback degradation could not be recorded (${recorded.exceptionOrNull()?.message.orEmpty()}); " +
          "operator decision: repair the workflow store before resuming",
        recorded.exceptionOrNull(),
      )
    }
    throw rollbackReconciliationError(
      runLoop,
      "$seam failed: $cause; operator decision: repair Git state before resuming",
      null,
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

  private fun rollbackReconciliationError(
    runLoop: FeatureTaskRuntimeRunLoop,
    reason: String,
    cause: Throwable?,
  ): FeatureTaskRuntimeSubtaskCommitReconciliationError {
    val error = FeatureTaskRuntimeSubtaskCommitReconciliationError(
      workflowId = runLoop.request.workflowId,
      issueKey = runLoop.request.issueKey,
      subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString() ?: "unknown",
      reason = reason,
      cause = cause,
    )
    runCatching {
      runLoop.diagnostics.warning(
        "record_kind=refusal seam=FeatureTaskRuntimeRunLoopCheckpointRemediationRollback " +
          "value_used='${runLoop.request.workflowId}' value_expected=fail-closed remediation rollback " +
          "cause=${error.reason}",
        error,
      )
    }
    return error
  }

  /**
   * A checkpoint commits the inventory this workflow owns and nothing else. The trigger is the OWNED
   * delta, not a non-blank `git status`: a tree dirty only with someone else's work has nothing for
   * this workflow to checkpoint, and committing it would attribute their changes to this run.
   */
}
