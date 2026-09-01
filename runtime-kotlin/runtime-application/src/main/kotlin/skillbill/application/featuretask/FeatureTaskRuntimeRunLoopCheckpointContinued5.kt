package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.AppendCheckpointIdentityArgs
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID

@Inject
class FeatureTaskRuntimeRunLoopCheckpointContinued5 {
  fun ledgerUnavailableRecord(identity: FeatureTaskRuntimeSubtaskCommitIdentity, cause: String): String =
    "seam=FeatureTaskRuntimeRunLoop.subtaskCommitLedgerState value_used='no durable pointer, sequence 0' " +
      "value_expected=the recorded checkpoint-identity ledger for '${identity.issueKey}/${identity.subtaskId}' " +
      "cause=$cause"

  /**
   * One subtask, one branch commit: the first checkpoint with staged content creates it and every
   * later checkpoint amends it. Failures return an error result so the caller's existing index-restore
   * reporting handles them exactly as a failed create.
   */
  fun writeSubtaskCommit(
    runLoop: FeatureTaskRuntimeRunLoop,
    branch: String,
    message: String,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  ): WorkflowGitOperationResult {
    val ledger = runLoop.collaborators.checkpointContinued4.subtaskCommitLedgerState(runLoop, identity)
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

  /**
   * A failed restore is worse than the failure that triggered it: the index is now in an unknown
   * runLoop.state and the operator has to know that before they touch the repository. It is reported in the
   * block reason rather than swallowed.
   */
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
    // The commit already exists; without its identity record the review input has no immutable
    // checkpoint to build from and no later phase can prove what this commit was allowed to own.
    return if (recorded.getOrDefault(false)) {
      true
    } else {
      runLoop.collaborators.checkpointContinued6.blockCheckpoint(
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
}
