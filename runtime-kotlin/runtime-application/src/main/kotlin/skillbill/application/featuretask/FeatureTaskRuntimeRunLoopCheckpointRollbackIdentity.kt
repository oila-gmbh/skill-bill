package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity

@Inject
class FeatureTaskRuntimeRunLoopCheckpointRollbackIdentity {
  internal fun finalizeRemediationCommit(
    runLoop: FeatureTaskRuntimeRunLoop,
    prepared: FeatureTaskRuntimeRunLoopCheckpointRemediationStage.RemediationCommitPrepared,
  ): RemediationCheckpointCommit? {
    val commit = runLoop.collaborators.checkpointContinued5.writeSubtaskCommit(
      runLoop,
      prepared.branch,
      prepared.message,
      prepared.subtaskIdentity,
    )
    if (!commit.ok) {
      runLoop.collaborators.checkpointContinued6.blockCheckpoint(
        runLoop,
        prepared.precedingPhaseId,
        prepared.branch,
        runLoop.collaborators.checkpointContinued5.withIndexRestoreOutcome(
          runLoop,
          commit.error,
          prepared.ownedPaths,
          prepared.indexSnapshot,
        ),
        runLoop.collaborators.checkpointContinued6.remediationCheckpointBlockedReasonFor(runLoop),
      )
      return null
    }
    val commitSha = commit.value.orEmpty().trim()
    if (commitSha.isBlank()) {
      runLoop.collaborators.checkpointContinued6.blockCheckpoint(
        runLoop,
        prepared.precedingPhaseId,
        prepared.branch,
        "remediation checkpoint commit returned an empty sha",
        runLoop.collaborators.checkpointContinued6.remediationCheckpointBlockedReasonFor(runLoop),
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
        parentSha = prepared.parentSha,
        commitSha = commitSha,
        blockedReason = runLoop.collaborators.checkpointContinued6.remediationCheckpointBlockedReasonFor(runLoop),
      ),
    )
    if (!recorded) {
      runLoop.collaborators.checkpointContinued2.rollbackRemediationCheckpointCommit(
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
        runLoop.collaborators.checkpointContinued2.recordRemediationRollbackDegradation(
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
    // A standalone run has no manifest to carry a name, so only a goal continuation missing one is a
    // degradation worth a record.
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
      val ledgerRecord = runLoop.collaborators.checkpointContinued5.ledgerUnavailableRecord(identity, cause)
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
}
