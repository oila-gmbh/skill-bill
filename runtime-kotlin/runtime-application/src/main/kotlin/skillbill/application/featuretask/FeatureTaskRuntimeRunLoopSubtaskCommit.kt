package skillbill.application.featuretask

import skillbill.application.featuretask.model.AppendCheckpointIdentityArgs
import skillbill.contracts.JsonCodec
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResultimport skillbill.ports.workflow.gitops.stagedPaths
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

@Inject
class FeatureTaskRuntimeRunLoopSubtaskCommit {
  internal fun unownedWorktreeCommitSha(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ): CommitPushFinalisation {
    if (isGoalContinuationRun(runLoop.request)) {
      return CommitPushBlocked(
        "needs_human: goal-subtask finalisation requires a resolved, unprotected, checked-out " +
          "branch owned by the subtask; no unowned HEAD commit can authorize review or finalisation.",
      )
    }
    val head = runLoop.phaseGates.gitOperations.headCommitSha(runLoop.request.repoRoot)
    val sha = head.value.orEmpty().trim().takeIf { head.ok && it.isNotBlank() }
      ?: return CommitPushNotApplicable
    runCatching {
      runLoop.diagnostics.warning(
        "seam=FeatureTaskRuntimeRunLoop.finaliseSubtaskCommit value_used='measured HEAD $sha' " +
          "value_expected=a runtime-finalised subtask commit for '${runLoop.request.issueKey}' " +
          "cause=the run has no resolved, unprotected, checked-out branch, so finalisation could not " +
          "stage, amend, or push and the commit sha degrades to whatever HEAD already names",
      )
    }
    return CommitPushSettled(
      revalidated(
        runLoop,
        run.phaseId,
        FeatureTaskRuntimeSubtaskFinalisation.withCommitSha(
          normalizedOutput.envelope,
          sha,
        ),
      ),
    )
  }

  internal fun reviewIdentityFailure(runLoop: FeatureTaskRuntimeRunLoop): String? =
    reviewIdentityFailureForSubtask(runLoop)

  fun finalisationBranch(runLoop: FeatureTaskRuntimeRunLoop): String? {
    val resolvedBranch = runLoop.session.resolvedBranch
    val failure = when {
      resolvedBranch == null -> "resolved subtask branch is missing"
      FeatureTaskRuntimeBranchSetup.protectedBranchName(resolvedBranch) != null ->
        "resolved subtask branch '$resolvedBranch' is protected"
      else -> {
        val head = runLoop.phaseGates.gitOperations.currentBranch(runLoop.request.repoRoot)
        when {
          !head.ok -> "checked-out subtask branch could not be read (${head.error})"
          head.value.trim() != resolvedBranch.trim() ->
            "checked-out branch '${head.value.trim()}' does not match durable subtask branch '$resolvedBranch'"
          else -> null
        }
      }
    }
    if (failure != null) {
      if (isGoalContinuationRun(runLoop.request)) {
        finalisationReconciliationFailure(
          runLoop,
          "FeatureTaskRuntimeRunLoopSubtaskCommit.finalisationBranch",
          failure,
          null,
        )
      }
      return null
    }
    return resolvedBranch
  }

  internal fun recordFinalisedCheckpointIdentity(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: RecordFinalisedCheckpointIdentityArgs,
  ): String? {
    val phaseId = args.phaseId
    val branch = args.branch
    val ledger = args.ledger
    val commitSha = args.commitSha
    val stagedPaths = args.stagedPaths
    val appended = runCatching {
      runLoop.recorder.appendCheckpointIdentity(
        AppendCheckpointIdentityArgs(
          workflowId = runLoop.request.workflowId,
          issueKey = runLoop.request.issueKey,
          subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString()
            ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
          branch = branch,
          phaseId = phaseId,
          loopId = null,
          generation = FeatureTaskRuntimeRunLoopCheckpoint.checkpointGeneration(runLoop, null),
          parentSha = ledger.commitSha,
          ownedPaths = stagedPaths,
          commitSha = commitSha,
        ),
      )
    }
    if (appended.getOrDefault(false)) return null
    val cause = appended.exceptionOrNull()?.message ?: "the workflow row was absent"
    runCatching {
      runLoop.diagnostics.warning(
        "seam=FeatureTaskRuntimeRunLoop.recordFinalisedCheckpointIdentity " +
          "value_used='no durable identity for finalised commit $commitSha' " +
          "value_expected=an appended checkpoint identity for '${runLoop.request.issueKey}' " +
          "cause=$cause",
      )
    }
    return "needs_human: the finalised subtask commit '$commitSha' was written but its durable " +
      "checkpoint identity could not be recorded ($cause), so it was not pushed. Without that pointer " +
      "a resumed run would open a second commit for this subtask instead of amending this one. Repair " +
      "the workflow store and resume; the commit is already on the branch."
  }
  fun revalidated(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    envelope: Map<String, Any?>,
  ): NormalizedFeatureTaskRuntimePhaseOutput = runLoop.outputValidator
    .validatePhaseOutput(JsonSupport.mapToJsonString(envelope), sourceLabel = phaseId)
    .requireAcceptedOutput(phaseId)
    .normalizedOutput
}

private fun recordFinalisedCheckpointIdentityForRuntime(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: RecordFinalisedCheckpointIdentityArgs,
): String? {
  val parent = runLoop.phaseGates.gitOperations.resolveCommit(runLoop.request.repoRoot, "${args.commitSha}^")
  val parentSha = parent.value.orEmpty().trim().takeIf { parent.ok && it.isNotBlank() }
    ?: return finalisedParentFailure(runLoop, args, parent.error)
  val appended = runCatching {
    runLoop.recorder.appendCheckpointIdentity(
      AppendCheckpointIdentityArgs(
        workflowId = runLoop.request.workflowId,
        issueKey = runLoop.request.issueKey,
        subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString()
          ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
        branch = args.branch,
        phaseId = args.phaseId,
        loopId = null,
        generation = runLoop.collaborators.checkpointContinued5.checkpointGeneration(runLoop, null),
        parentSha = parentSha,
        ownedPaths = args.stagedPaths,
        commitSha = args.commitSha,
        dbOverride = runLoop.request.dbPathOverride,
      ),
    )
  }
  return if (appended.getOrDefault(false)) null else finalisedIdentityFailure(runLoop, args, appended)
}

private fun finalisedParentFailure(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: RecordFinalisedCheckpointIdentityArgs,
  detail: String?,
): String {
  val error = FeatureTaskRuntimeSubtaskCommitReconciliationError(
    workflowId = runLoop.request.workflowId,
    issueKey = runLoop.request.issueKey,
    subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString() ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
    reason = "finalised subtask commit '${args.commitSha}' has no resolvable parent ($detail); operator decision: " +
      "repair Git object access before pushing",
  )
  runLoop.diagnostics.warning(
    "record_kind=refusal seam=FeatureTaskRuntimeRunLoop.recordFinalisedCheckpointIdentity " +
      "value_used='${args.commitSha}' value_expected=the finalized commit parent cause=${error.reason}",
    error,
  )
  return "needs_human: ${error.message.orEmpty()}"
}

private fun finalisedIdentityFailure(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: RecordFinalisedCheckpointIdentityArgs,
  appended: Result<Boolean>,
): String {
  val cause = appended.exceptionOrNull()?.message ?: "the workflow row was absent"
  val error = FeatureTaskRuntimeSubtaskCommitReconciliationError(
    workflowId = runLoop.request.workflowId,
    issueKey = runLoop.request.issueKey,
    subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString() ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
    reason = "finalised subtask commit '${args.commitSha}' has no durable checkpoint identity ($cause); operator " +
      "decision: repair the workflow store before pushing",
    cause = appended.exceptionOrNull(),
  )
  runLoop.diagnostics.warning(
    "record_kind=refusal seam=FeatureTaskRuntimeRunLoop.recordFinalisedCheckpointIdentity " +
      "value_used='no durable identity for finalised commit ${args.commitSha}' " +
      "value_expected=an appended checkpoint identity for '${runLoop.request.issueKey}' cause=${error.reason}",
    error,
  )
  return "needs_human: ${error.message.orEmpty()} The commit remains on the branch; repair the workflow store and " +
    "resume before pushing."
}

internal fun finalisationReconciliationFailure(
  runLoop: FeatureTaskRuntimeRunLoop,
  seam: String,
  reason: String,
  cause: Throwable?,
): String {
  val error = FeatureTaskRuntimeSubtaskCommitReconciliationError(
    workflowId = runLoop.request.workflowId,
    issueKey = runLoop.request.issueKey,
    subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString() ?: "unknown",
    reason = "$reason; operator decision: repair durable subtask ownership before finalisation",
    cause = cause,
  )
  runCatching {
    runLoop.diagnostics.warning(
      "record_kind=refusal seam=$seam value_used='finalisation ownership' " +
        "value_expected=durable branch and phase evidence cause=${error.reason}",
      error,
    )
  }
  return "needs_human: ${error.message.orEmpty()}"
}
