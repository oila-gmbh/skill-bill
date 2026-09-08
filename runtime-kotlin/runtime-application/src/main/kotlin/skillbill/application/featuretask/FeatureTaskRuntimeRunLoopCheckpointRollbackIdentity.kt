package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity

@Inject
class FeatureTaskRuntimeRunLoopCheckpointRollbackIdentity {
  internal fun finalizeRemediationCommit(
    runLoop: FeatureTaskRuntimeRunLoop,
    prepared: FeatureTaskRuntimeRunLoopCheckpointRemediationStage.RemediationCommitPrepared,
  ): RemediationCheckpointCommit? = finalizePreparedRemediationCommit(runLoop, prepared)

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
        val reconciliationError = FeatureTaskRuntimeSubtaskCommitReconciliationError(
          workflowId = runLoop.request.workflowId,
          issueKey = runLoop.request.issueKey,
          subtaskId = subtaskId,
          reason = "checkpoint identities for rollback could not be read " +
            "(${error.message.orEmpty().ifBlank { error::class.simpleName.orEmpty() }})",
          cause = error,
        )
        runLoop.diagnostics.warning(
          "record_kind=refusal seam=FeatureTaskRuntimeRunLoop.rollbackRemediationCheckpointCommit " +
            "value_used='${runLoop.request.workflowId}' value_expected=checkpoint identities for rollback " +
            "cause=${reconciliationError.reason}",
          reconciliationError,
        )
        throw reconciliationError
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
        generation = runLoop.collaborators.checkpointContinued5.checkpointGeneration(runLoop, loopId),
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
      val error = FeatureTaskRuntimeSubtaskCommitReconciliationError(
        workflowId = runLoop.request.workflowId,
        issueKey = identity.issueKey,
        subtaskId = identity.subtaskId,
        reason = cause,
      )
      runCatching {
        runLoop.diagnostics.warning(
          runLoop.collaborators.checkpointContinued5.ledgerUnavailableRecord(identity, cause),
          error,
        )
      }
      throw error
    }
    val recorded = requireNotNull(identities)
    return SubtaskCommitLedgerState(
      commitSha = recorded
        .filter { it.issueKey == identity.issueKey && it.subtaskId == identity.subtaskId }
        .maxByOrNull { it.sequenceNumber }
        ?.commitSha,
      nextSequenceNumber = (recorded.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
      branch = recorded
        .filter { it.issueKey == identity.issueKey && it.subtaskId == identity.subtaskId }
        .maxByOrNull { it.sequenceNumber }
        ?.branch,
    )
  }
}

private fun finalizePreparedRemediationCommit(
  runLoop: FeatureTaskRuntimeRunLoop,
  prepared: FeatureTaskRuntimeRunLoopCheckpointRemediationStage.RemediationCommitPrepared,
): RemediationCheckpointCommit? {
  val commit = runLoop.collaborators.checkpointContinued5.writeSubtaskCommit(
    runLoop,
    prepared.branch,
    prepared.message,
    prepared.subtaskIdentity,
    prepared.ownedPaths,
  )
  if (!commit.ok) {
    blockRemediationCommit(runLoop, prepared, commit.error)
    return null
  }
  val commitSha = commit.value.orEmpty().trim()
  if (commitSha.isBlank()) {
    blockRemediationCommit(runLoop, prepared, "remediation checkpoint commit returned an empty sha")
    return null
  }
  return persistRemediationCommit(runLoop, prepared, commitSha)
}

private fun blockRemediationCommit(
  runLoop: FeatureTaskRuntimeRunLoop,
  prepared: FeatureTaskRuntimeRunLoopCheckpointRemediationStage.RemediationCommitPrepared,
  reason: String,
) {
  runLoop.collaborators.checkpointContinued6.blockCheckpoint(
    runLoop,
    prepared.precedingPhaseId,
    prepared.branch,
    runLoop.collaborators.checkpointContinued5.withIndexRestoreOutcome(
      runLoop,
      reason,
      prepared.ownedPaths,
      prepared.indexSnapshot,
    ),
    runLoop.collaborators.checkpointContinued6.remediationCheckpointBlockedReasonFor(runLoop),
  )
}

private fun persistRemediationCommit(
  runLoop: FeatureTaskRuntimeRunLoop,
  prepared: FeatureTaskRuntimeRunLoopCheckpointRemediationStage.RemediationCommitPrepared,
  commitSha: String,
): RemediationCheckpointCommit? {
  val parentSha = runLoop.phaseGates.gitOperations.resolveCommit(runLoop.request.repoRoot, "$commitSha^")
    .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
  if (parentSha == null) {
    blockRemediationCommit(
      runLoop,
      prepared,
      "remediation checkpoint commit '$commitSha' has no resolvable parent for durable identity",
    )
    return null
  }
  val recorded = runLoop.collaborators.checkpointContinued5.recordCheckpointIdentity(
    runLoop,
    RecordCheckpointIdentityArgs(
      precedingPhaseId = prepared.precedingPhaseId,
      branch = prepared.branch,
      loopId = prepared.loopId,
      ownedPaths = prepared.ownedPaths,
      parentSha = parentSha,
      commitSha = commitSha,
      blockedReason = runLoop.collaborators.checkpointContinued6.remediationCheckpointBlockedReasonFor(runLoop),
    ),
  )
  if (!recorded) {
    runCatching {
      runLoop.collaborators.checkpointContinued2.rollbackRemediationCheckpointCommit(
        runLoop,
        commitSha,
        prepared.parentSha,
        identityRecorded = false,
      )
    }.onFailure { error ->
      if (error !is FeatureTaskRuntimeSubtaskCommitReconciliationError) throw error
    }
    return null
  }
  return RemediationCheckpointCommit(commitSha, parentSha)
}
