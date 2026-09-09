package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.AppendCheckpointIdentityArgs
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID

@Inject
class FeatureTaskRuntimeRunLoopCheckpointSubtaskCommitLedger {
  fun writeSubtaskCommit(
    runLoop: FeatureTaskRuntimeRunLoop,
    branch: String,
    message: String,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
    ownedPaths: List<String>,
  ): WorkflowGitOperationResult {
    val context = loadSubtaskCommitWriteContext(runLoop, branch, identity)
    val ledger = context.ledger ?: return reconciliationFailureResult(
      runLoop,
      identity,
      context.failure ?: "subtask commit write context could not be resolved",
      null,
    )
    context.ownershipFailure(isGoalContinuationRun(runLoop.request), branch, identity)?.let {
      return reconciliationFailureResult(runLoop, identity, it, null)
    }
    val decision = FeatureTaskRuntimeSubtaskCommitResolver.decide(
      identity = identity,
      durableCommitSha = ledger.commitSha,
      head = FeatureTaskRuntimeSubtaskCommitHeadState(
        sha = context.headSha,
        commitMessage = context.headMessage.takeIf { ledger.commitSha == null },
        isUnpushed = context.unpushed,
      ),
      sequenceNumber = ledger.nextSequenceNumber,
    )
    val committed = runLoop.phaseGates.gitOperations.writeSubtaskCommitPreservingHistory(
      SubtaskCommitPreservationRequest(
        repoRoot = runLoop.request.repoRoot,
        decision = decision,
        identity = identity,
        message = message,
        allowUnchangedIndex = false,
        ownedPaths = ownedPaths,
        record = { record -> runCatching { runLoop.diagnostics.warning(record) } },
      ),
    )
    return if (committed.ok && decision is FeatureTaskRuntimeSubtaskCommitCreate) {
      persistCreatedSubtaskCommitRef(runLoop, identity, ledger.nextSequenceNumber, committed)
    } else {
      committed
    }
  }

  fun reconcileBeforeReview(
    runLoop: FeatureTaskRuntimeRunLoop,
    precedingPhaseId: String,
    branch: String,
    blockedReason: (String, String) -> String,
  ): Boolean = reconcileBeforeReviewForLedger(runLoop, precedingPhaseId, branch, blockedReason)

  internal fun blockReconciliation(runLoop: FeatureTaskRuntimeRunLoop, request: BlockReconciliationRequest): Boolean =
    blockLedgerReconciliation(runLoop, request)

  fun branchHasUnpushedCommits(runLoop: FeatureTaskRuntimeRunLoop, branch: String): Boolean {
    val unpushed = runLoop.phaseGates.gitOperations.localBranchHasUnpushedCommits(runLoop.request.repoRoot, branch)
    return unpushed.ok && unpushed.value.orEmpty().trim().equals("true", ignoreCase = true)
  }

  fun ledgerUnavailableRecord(identity: FeatureTaskRuntimeSubtaskCommitIdentity, cause: String): String =
    "seam=FeatureTaskRuntimeRunLoop.subtaskCommitLedgerState value_used='no durable pointer, sequence 0' " +
      "value_expected=the recorded checkpoint-identity ledger for '${identity.issueKey}/${identity.subtaskId}' " +
      "cause=$cause"

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
    val recorded = runCatching {
      runLoop.recorder.appendCheckpointIdentity(
        AppendCheckpointIdentityArgs(
          workflowId = runLoop.request.workflowId,
          issueKey = runLoop.request.issueKey,
          subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString()
            ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
          branch = args.branch,
          phaseId = args.precedingPhaseId,
          loopId = args.loopId,
          generation = checkpointGeneration(runLoop, args.loopId),
          parentSha = args.parentSha,
          ownedPaths = args.ownedPaths,
          commitSha = args.commitSha,
          dbOverride = runLoop.request.dbPathOverride,
        ),
      )
    }
    if (recorded.getOrDefault(false)) return true
    val error = FeatureTaskRuntimeSubtaskCommitReconciliationError(
      workflowId = runLoop.request.workflowId,
      issueKey = runLoop.request.issueKey,
      subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString()
        ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
      reason = recorded.exceptionOrNull()?.message ?: "the workflow row was absent",
      cause = recorded.exceptionOrNull(),
    )
    runCatching {
      runLoop.diagnostics.warning(
        "seam=FeatureTaskRuntimeRunLoopCheckpointSubtaskCommitLedger.recordCheckpointIdentity " +
          "value_used='no durable checkpoint identity for ${args.commitSha}' value_expected=the committed " +
          "subtask identity cause=${error.reason}",
        error,
      )
    }
    return runLoop.collaborators.checkpointContinued6.blockCheckpoint(
      runLoop,
      args.precedingPhaseId,
      args.branch,
      error.message.orEmpty(),
      args.blockedReason,
    )
  }
}

internal data class BlockReconciliationRequest(
  val precedingPhaseId: String,
  val branch: String,
  val blockedReason: (String, String) -> String,
  val reason: String,
  val cause: Throwable? = null,
)
