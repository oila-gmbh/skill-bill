package skillbill.application.featuretask

import skillbill.application.workflow.repoRoot
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID

internal fun FeatureTaskRuntimeRunLoop.subtaskCommitIdentity(): FeatureTaskRuntimeSubtaskCommitIdentity =
  FeatureTaskRuntimeSubtaskCommitIdentity(
    issueKey = request.issueKey,
    subtaskId = request.goalContinuation?.subtaskId?.toString() ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
  )

internal fun FeatureTaskRuntimeRunLoop.checkpointCommitMessage(
  branch: String,
  phaseId: String,
  loopId: String?,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  intent: String,
): String {
  val subtaskName = request.goalContinuation?.subtaskName?.trim()?.takeIf(String::isNotBlank)
  // A standalone run has no manifest to carry a name, so only a goal continuation missing one is a
  // degradation worth a record.
  if (subtaskName == null && request.goalContinuation != null) {
    runCatching {
      diagnostics.warning(
        FeatureTaskRuntimeCheckpointMessage.missingSubtaskNameRecord(identity.issueKey, identity.subtaskId),
      )
    }
  }
  return FeatureTaskRuntimeCheckpointMessage.build(
    issueKey = request.issueKey,
    subtaskName = subtaskName,
    metadata = FeatureTaskRuntimeCheckpointMetadata(
      phaseId = phaseId,
      loopId = loopId,
      generation = checkpointGeneration(loopId),
      branch = branch,
      intent = intent,
    ),
    identity = identity,
  )
}

internal fun FeatureTaskRuntimeRunLoop.subtaskCommitLedgerState(
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
): SubtaskCommitLedgerState {
  val read = runCatching { recorder.loadCheckpointIdentities(request.workflowId, request.dbPathOverride) }
  val identities = read.getOrNull()
  val cause = read.exceptionOrNull()
    ?.let { "the checkpoint-identity store could not be read (${it.message ?: it::class.simpleName})" }
    ?: "no workflow row recorded any checkpoint identity for this run".takeIf { identities == null }
  if (cause != null) {
    runCatching { diagnostics.warning(ledgerUnavailableRecord(identity, cause)) }
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

internal fun FeatureTaskRuntimeRunLoop.ledgerUnavailableRecord(
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  cause: String,
): String = "seam=FeatureTaskRuntimeRunLoop.subtaskCommitLedgerState value_used='no durable pointer, sequence 0' " +
  "value_expected=the recorded checkpoint-identity ledger for '${identity.issueKey}/${identity.subtaskId}' " +
  "cause=$cause"

/**
 * One subtask, one branch commit: the first checkpoint with staged content creates it and every
 * later checkpoint amends it. Failures return an error result so the caller's existing index-restore
 * reporting handles them exactly as a failed create.
 */
internal fun FeatureTaskRuntimeRunLoop.writeSubtaskCommit(
  branch: String,
  message: String,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
): WorkflowGitOperationResult {
  val ledger = subtaskCommitLedgerState(identity)
  val headSha = phaseGates.gitOperations.headCommitSha(request.repoRoot)
    .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
  val decision = FeatureTaskRuntimeSubtaskCommitResolver.decide(
    identity = identity,
    durableCommitSha = ledger.commitSha,
    head = FeatureTaskRuntimeSubtaskCommitHeadState(
      sha = headSha,
      commitMessage = if (ledger.commitSha == null && headSha != null) headCommitMessageOrNull() else null,
      isUnpushed = branchHasUnpushedCommits(branch),
    ),
    sequenceNumber = ledger.nextSequenceNumber,
  )
  return phaseGates.gitOperations.writeSubtaskCommitPreservingHistory(
    repoRoot = request.repoRoot,
    decision = decision,
    identity = identity,
    message = message,
    allowUnchangedIndex = false,
    record = { record -> runCatching { diagnostics.warning(record) } },
  )
}

internal fun FeatureTaskRuntimeRunLoop.headCommitMessageOrNull(): String? =
  phaseGates.gitOperations.headCommitMessage(request.repoRoot).takeIf { it.ok }?.value

internal fun FeatureTaskRuntimeRunLoop.branchHasUnpushedCommits(branch: String): Boolean {
  val unpushed = phaseGates.gitOperations.localBranchHasUnpushedCommits(request.repoRoot, branch)
  return unpushed.ok && unpushed.value.orEmpty().trim().equals("true", ignoreCase = true)
}

/**
 * A failed restore is worse than the failure that triggered it: the index is now in an unknown
 * state and the operator has to know that before they touch the repository. It is reported in the
 * block reason rather than swallowed.
 */
internal fun FeatureTaskRuntimeRunLoop.withIndexRestoreOutcome(
  error: String,
  ownedPaths: List<String>,
  snapshot: String,
): String {
  val restored = phaseGates.gitOperations.restoreIndexState(request.repoRoot, ownedPaths, snapshot)
  return if (restored.ok) {
    "$error; the pre-checkpoint index was restored and the working tree is unchanged"
  } else {
    "$error; the pre-checkpoint index could NOT be restored (${restored.error}) — inspect " +
      "`git status` before committing anything yourself"
  }
}

internal fun FeatureTaskRuntimeRunLoop.checkpointGeneration(loopId: String?): Int = loopId?.let {
  state.edgeIterationCount(it)
} ?: 0
